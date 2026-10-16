package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshOutcome
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionFormat
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Orchestrates add/refresh/remove for subscriptions.
 *
 * Refresh commits only a fully validated candidate: fetch → classify → parse →
 * engine validation must all succeed before any DB row changes, and the node
 * swap + success metadata land in a single transaction. A failure anywhere
 * leaves the previously persisted nodes untouched (last-known-good).
 *
 * Locking: the slow phase of a refresh — network fetch, parse, engine
 * validation — runs WITHOUT any lock, so one subscription's slow provider
 * never blocks unrelated operations (another subscription's refresh, a pref
 * toggle, a remove). Everything else is guarded by per-subscription mutexes
 * plus three leaf mutexes:
 *
 *  - [subLocks] — one mutex per subscription id, serializing each row's
 *    commit-bearing operations ([refresh] phase 2, [editUrl], [remove],
 *    [setEnabled]). Entries are never removed: dropping one would let a
 *    fresh mutex race an in-flight commit for a recycled id, and the map is
 *    bounded by the subscriptions created in this process.
 *  - [prefsMutex] — node-preference invariants that span the node table:
 *    orphan pruning and every conditional selected-node clear. The clear
 *    itself is one atomic DataStore edit, so a concurrent user pick can't be
 *    wiped; the mutex keeps read-check-clear sequences from interleaving
 *    with commits.
 *  - [expiryMutex] — the check→notify→mark expiry sequence, shared by the
 *    refresh post-commit path and the startup scan.
 *  - [manualMutex] — manual sentinel row creation/deletion
 *    ([importShareLink], [removeManualNode]).
 *
 * Lock order — leaves never take subscription locks, so there are no cycles:
 * [lockFor] → [prefsMutex] / [expiryMutex], and [manualMutex] → [prefsMutex].
 */
/**
 * Retry transient transport/server failures only. HTTP 429 is intentionally
 * excluded because providers may require a longer Retry-After interval.
 */
internal fun isRetryableSubscriptionFetchError(error: SubscriptionError): Boolean =
    when (error) {
        SubscriptionError.Network, SubscriptionError.Timeout -> true
        is SubscriptionError.Http -> error.code == 408 || error.code == 425 ||
            error.code in 500..599
        else -> false
    }

@Singleton
class SubscriptionRepository
    @Inject
    constructor(
        private val subscriptionDao: SubscriptionDao,
        private val nodeDao: NodeDao,
        private val nodePreferenceDao: NodePreferenceDao,
        private val fetcher: SubscriptionFetcher,
        private val classifier: SubscriptionClassifier,
        private val dispatcher: SubscriptionParserDispatcher,
        private val validator: SubscriptionCandidateValidator,
        private val transactions: DbTransactionRunner,
        private val scheduler: SubscriptionRefreshScheduler,
        private val settings: SubscriptionSettings,
        private val expiryNotifier: SubscriptionExpiryNotifier,
        private val uriListParser: UriListParser,
    ) {
        /** Per-subscription commit locks — see the class kdoc for the scheme. */
        private val subLocks = ConcurrentHashMap<Long, Mutex>()

        /** Monotonic refresh-sequence per subscription id. Every [refresh]
         *  takes its ticket at call time; [commitRefresh] and [failRefresh]
         *  compare it under the row's lock, so a slow earlier attempt can
         *  never overwrite a newer success/failure for the same URL — the
         *  fetchedUrl check alone can't tell two same-URL refreshes apart. */
        private val refreshSeq = ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>()

        private fun nextRefreshSeq(id: Long): Long =
            refreshSeq.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }
                .incrementAndGet()

        private fun isStaleAttempt(
            id: Long,
            seq: Long,
        ): Boolean =
            (refreshSeq[id]?.get() ?: seq) != seq

        private val prefsMutex = Mutex()
        private val expiryMutex = Mutex()
        private val manualMutex = Mutex()

        private val json = Json { ignoreUnknownKeys = true }

        private fun lockFor(id: Long): Mutex = subLocks.computeIfAbsent(id) { Mutex() }

        /** Rows of adds still in flight: hidden from [profiles] so a subscription that may
         *  yet fail never shows up half-made. */
        private val pendingAdds = MutableStateFlow<Set<Long>>(emptySet())

        /** Makes "insert the row + mark it pending" atomic towards [pruneAbandonedAdds]. */
        private val addGate = Mutex()

        val profiles: Flow<List<SubscriptionProfile>> =
            combine(subscriptionDao.observeAll(), pendingAdds) { list, pending ->
                list.filter { it.id !in pending }.map { it.toDomain() }
            }

        /** Nodes of the manual/local sentinel row in display order — drives
         *  the detail sheet's per-node delete list. Empty until the first
         *  import creates the row. */
        val manualNodes: Flow<List<NodeEntity>> =
            nodeDao.observeForSubscriptionUrl(MANUAL_SUBSCRIPTION_URL)

        suspend fun add(
            url: String,
            requestedName: String?,
            allowInsecureHttp: Boolean = false,
        ): Result<RefreshOutcome> {
            val trimmed = url.trim()
            val parsed =
                runCatching { trimmed.toHttpUrl() }.getOrNull()
                    ?: return Result.failure(SubscriptionError.BadUrl)
            // Reject a cleartext URL before a row exists — a subscription that can
            // never fetch should not be persisted at all.
            if (!parsed.isHttps && !allowInsecureHttp) {
                return Result.failure(SubscriptionError.InsecureTransport)
            }
            val entity =
                SubscriptionEntity(
                    name = requestedName?.trim().orEmpty().ifEmpty { deriveName(trimmed) },
                    url = trimmed,
                    // The flag is meaningless on https — don't persist dead state.
                    allowInsecureHttp = allowInsecureHttp && !parsed.isHttps,
                    createdAtEpochMs = Instant.now().toEpochMilli(),
                    lastUpdatedAtEpochMs = null,
                    lastAttemptAtEpochMs = null,
                    lastError = null,
                    enabled = true,
                    userInfoJson = null,
                    supportUrl = null,
                    updateIntervalMinutes = null,
                    announce = null,
                    fallbackUrl = null,
                )
            val id =
                addGate.withLock {
                    subscriptionDao.insert(entity).also { id -> pendingAdds.update { it + id } }
                }
            try {
                val result = refresh(id)
                // All-or-nothing: the row exists only to give the fetch an id. A failed first
                // refresh leaves no row, nodes or scheduled work (nothing was scheduled yet —
                // that happens only on a committed success).
                if (result.isFailure) rollbackAdd(id)
                return result
            } catch (e: Throwable) {
                withContext(NonCancellable) { rollbackAdd(id) }
                throw e
            } finally {
                pendingAdds.update { it - id }
            }
        }

        private suspend fun rollbackAdd(id: Long) {
            try {
                lockFor(id).withLock { deleteRows(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w(TAG, "add rollback failed sub=$id: ${e.javaClass.simpleName}")
            }
        }

        /**
         * Startup cleanup for an add whose process died mid-request: a row that never
         * refreshed successfully (`lastUpdatedAtEpochMs` is written only by markSuccess /
         * updateUrlAndMarkSuccess) and owns no nodes can't be anything but a leftover.
         * The manual sentinel and rows of adds still in flight are never touched.
         */
        suspend fun pruneAbandonedAdds(): Int {
            val candidates =
                addGate.withLock {
                    val pending = pendingAdds.value
                    subscriptionDao.getAll().filter { it.id !in pending }
                }
            var removed = 0
            for (candidate in candidates) {
                lockFor(candidate.id).withLock {
                    val sub = subscriptionDao.get(candidate.id) ?: return@withLock
                    if (isManualSubscription(sub.url) || sub.lastUpdatedAtEpochMs != null ||
                        nodeDao.countForSubscription(sub.id) > 0
                    ) {
                        return@withLock
                    }
                    deleteRows(sub.id)
                    scheduler.cancel(sub.id)
                    removed++
                }
            }
            return removed
        }

        /**
         * Ids for the refresh-on-launch pass. Prunes abandoned adds FIRST and only then
         * reads the rows, so a leftover from an interrupted add can never be fetched —
         * the order is the contract, not an accident of two coroutines racing. A prune failure
         * is logged and the pass continues with whatever rows exist.
         */
        suspend fun launchRefreshCandidates(): List<Long> {
            // A failed prune must not cost the user their update-always refresh: log and go on.
            try {
                val removed = pruneAbandonedAdds()
                if (removed > 0) SecureLog.i(TAG, "pruned $removed abandoned subscription row(s)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w(TAG, "abandoned add prune failed: ${e.javaClass.simpleName}")
            }
            return subscriptionDao.getAll().filter { it.updateAlways }.map { it.id }
        }

        /** Prefs, nodes and the row go together or not at all. */
        private suspend fun deleteRows(id: Long) {
            prefsMutex.withLock {
                transactions.run {
                    // Prefs go first while the node rows still exist — the DAO prunes by
                    // sub-select on nodes, so ordering matters.
                    nodePreferenceDao.deleteForSubscription(id)
                    nodeDao.deleteForSubscription(id)
                    subscriptionDao.delete(id)
                }
            }
        }

        /** Which entry point asked for the refresh — the pre-fetch gate and
         *  the pre-commit re-check differ per trigger. */
        private enum class RefreshTrigger { Manual, Launch, Periodic }

        /**
         * Refresh one subscription in two phases: everything slow (fetch,
         * parse, engine validation) runs outside any lock in
         * [prepareRefresh]; only the commit + post-commit bookkeeping hold
         * this subscription's lock in [commitRefresh]. A slow provider
         * therefore never blocks other subscriptions or user operations.
         *
         * Manual refresh is unconditional: even a Disabled-policy row may be
         * pulled explicitly by the user, and a manual commit is never
         * re-gated.
         */
        suspend fun refresh(id: Long): Result<RefreshOutcome> =
            refresh(id, RefreshTrigger.Manual)

        /**
         * Launch-triggered refresh (`update-always` startup pass): re-checks
         * eligibility on the CURRENT row at fetch entry — the caller's
         * `getAll()` snapshot can be seconds stale while this refresh queued
         * behind earlier ones, so a subscription disabled (enabled=false or
         * policy Disabled) after the snapshot must not fetch. Only the
         * manual refresh stays unconditional — Disabled policy allows
         * explicit user pulls; the periodic worker is gated by
         * [periodicSkipReasonLocked]. A skipped row reports
         * [SubscriptionError.Superseded]: a typed "nothing was written, row
         * lives on" result, not a row deletion.
         */
        suspend fun refreshOnLaunch(id: Long): Result<RefreshOutcome> =
            refresh(id, RefreshTrigger.Launch)

        /**
         * Periodic refresh (`SubscriptionRefreshWorker` slot): eligibility is
         * the same interval resolution the scheduler used — re-checked on
         * the CURRENT row under the subscription lock BEFORE any ticket or
         * fetch, because a periodic job can outlive the change that made it
         * obsolete (policy Disabled, enabled=false, a global off the row
         * inherits, a provider hint that no longer resolves). Unlike the
         * launch gate, `updateAlways` is irrelevant — a periodic slot exists
         * on interval alone.
         *
         * Skip results: [SubscriptionError.NotFound] when the row is gone
         * (the worker may drop its job); [SubscriptionError.Superseded] for
         * every "skipped, nothing written, job stays" case — never treated
         * as a deletion. The commit path re-gates at [commitRefresh] entry
         * too: a policy or global interval flipped while any fetch was in
         * flight (including the under-lock replacement fetch of the
         * url-changed re-pass) must not see its stale candidate committed.
         */
        suspend fun refreshPeriodic(id: Long): Result<RefreshOutcome> =
            refresh(id, RefreshTrigger.Periodic)

        /** Launch eligibility on the CURRENT row — see [refreshOnLaunch]. */
        private suspend fun isLaunchEligible(id: Long): Boolean =
            subscriptionDao.get(id)?.let { sub ->
                !isManualSubscription(sub.url) && sub.enabled && sub.updateAlways &&
                    RefreshPolicy.fromStorage(sub.refreshPolicy, sub.refreshFixedMinutes) !=
                        RefreshPolicy.Disabled
            } == true

        /**
         * Periodic-run eligibility on the CURRENT row — caller holds
         * [lockFor]. Returns the typed skip result: [SubscriptionError.NotFound]
         * when the row is gone, [SubscriptionError.Superseded] when nothing
         * should fetch (manual sentinel, enabled=false, Disabled policy, or
         * no resolvable interval — the exact predicate the scheduler used to
         * enqueue), null when the run may proceed.
         */
        private suspend fun periodicSkipReasonLocked(id: Long): SubscriptionError? {
            val sub = subscriptionDao.get(id) ?: return SubscriptionError.NotFound
            if (isManualSubscription(sub.url)) return SubscriptionError.Superseded
            val scheduled =
                resolveRefreshIntervalMinutes(
                    policy =
                        RefreshPolicy.fromStorage(
                            sub.refreshPolicy,
                            sub.refreshFixedMinutes,
                        ),
                    userOverrideMinutes = settings.autoRefreshMinutes.first(),
                    providerMinutes = sub.updateIntervalMinutes,
                    enabled = sub.enabled,
                )
            return if (scheduled == null) SubscriptionError.Superseded else null
        }

        private suspend fun refresh(
            id: Long,
            trigger: RefreshTrigger,
        ): Result<RefreshOutcome> {
            val attemptAt = Instant.now().toEpochMilli()
            // Phase 1 — no lock held. The fetch is cancellable (OkHttp call
            // cancellation propagates), so a cancelled caller frees nothing
            // because it never held anything.
            // Gated launches and periodic runs re-read eligibility on the
            // CURRENT row immediately before the fetch — the caller's
            // snapshot can be seconds stale while this run queued behind
            // earlier refreshes. These gates run BEFORE taking a refresh
            // ticket: a skipped scheduled run must not supersede a manual
            // refresh parked in validation — Disabled blocks scheduled work,
            // never the user's explicit pull.
            val seq =
                when (trigger) {
                    RefreshTrigger.Manual -> nextRefreshSeq(id)
                    RefreshTrigger.Launch -> {
                        val eligible =
                            try {
                                isLaunchEligible(id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Same typed-failure contract as a phase-1 error.
                                return lockFor(id).withLock {
                                    Result.failure(failRefresh(id, attemptAt, e))
                                }
                            }
                        if (!eligible) {
                            // Superseded, not NotFound: nothing was fetched or
                            // written and the row lives on — a skipped run,
                            // not a deletion.
                            SecureLog.i(TAG, "launch refresh skipped — row no longer eligible sub=$id")
                            return Result.failure(SubscriptionError.Superseded)
                        }
                        nextRefreshSeq(id)
                    }
                    RefreshTrigger.Periodic ->
                        // Admission is atomic: the eligibility re-read and the
                        // sequence ticket happen under one lock hold — a
                        // skipped run allocates no ticket, and a ticketed
                        // run was eligible at ticket time.
                        try {
                            lockFor(id).withLock {
                                periodicSkipReasonLocked(id)?.let { skip ->
                                    SecureLog.i(
                                        TAG,
                                        "periodic refresh skipped sub=$id: ${skip.safeMessage()}",
                                    )
                                    return Result.failure(skip)
                                }
                                nextRefreshSeq(id)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return lockFor(id).withLock {
                                Result.failure(failRefresh(id, attemptAt, e))
                            }
                        }
                }
            val prepared =
                try {
                    prepareRefresh(id, attemptAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Stale attempt: a newer refresh already ran — don't
                    // overwrite its lastError with this one's late failure.
                    // Superseded (not NotFound): the periodic worker must
                    // treat this as a skipped run, never as a deleted row.
                    return if (isStaleAttempt(id, seq)) {
                        Result.failure(SubscriptionError.Superseded)
                    } else {
                        lockFor(id).withLock {
                            if (isStaleAttempt(id, seq)) {
                                Result.failure(SubscriptionError.Superseded)
                            } else {
                                Result.failure(failRefresh(id, attemptAt, e))
                            }
                        }
                    }
                }
            // Phase 2 — commit under this subscription's own lock. The catch
            // keeps the Result contract: a DB failure must surface as a typed
            // failure, not escape as an exception.
            return lockFor(id).withLock {
                if (isStaleAttempt(id, seq)) {
                    // A newer refresh is in flight (or already committed) —
                    // this candidate must not overwrite its node set.
                    SecureLog.i(TAG, "dropping stale refresh result sub=$id")
                    return@withLock Result.failure(SubscriptionError.Superseded)
                }
                try {
                    // The periodic re-gate lives inside commitRefresh so the
                    // url-changed re-pass can't bypass it; a DAO/DataStore
                    // failure there lands in this catch as a typed failure.
                    commitRefresh(id, prepared, trigger)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(failRefresh(id, prepared.attemptAt, e))
                }
            }
        }

        /**
         * Refresh with [lockFor] already held — the stale-candidate re-pass
         * of [commitRefresh] and [editUrl]'s same-URL delegation land here.
         * Failure logging stays "refresh failed" in both callers.
         */
        private suspend fun refreshLocked(
            id: Long,
            trigger: RefreshTrigger,
        ): Result<RefreshOutcome> {
            val attemptAt = Instant.now().toEpochMilli()
            // Take the same generation ticket refresh() takes: a caller that
            // reached here through the stale re-pass already proved the URL
            // match, but an even-newer refresh may have ticketed meanwhile.
            val seq = nextRefreshSeq(id)
            if (isStaleAttempt(id, seq)) {
                return Result.failure(SubscriptionError.Superseded)
            }
            return try {
                commitRefresh(id, prepareRefresh(id, attemptAt), trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(failRefresh(id, attemptAt, e))
            }
        }

        /** Re-read consent for every request, including transport fallback. */
        private suspend fun fetchWithConsent(url: String, allowInsecure: Boolean) =
            fetcher.fetch(
                url,
                if (settings.hwidConsent.first() == HwidConsent.Allowed) {
                    settings.getOrCreateHwid()
                } else null,
                allowInsecure,
            )

        /**
         * Retry only failures that may clear up on their own. Invalid links,
         * auth/device-limit responses, parse failures and rejected configs are
         * not retried. One short retry helps with flaky mobile networks without
         * hammering a provider or turning one tap into a long retry storm.
         */
        private suspend fun fetchWithRetry(url: String, allowInsecure: Boolean): FetchedSubscription {
            var retries = 0
            while (true) {
                try {
                    return fetchWithConsent(url, allowInsecure)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionError) {
                    if (!isRetryableSubscriptionFetchError(e) || retries >= MAX_FETCH_RETRIES) {
                        throw e
                    }
                    retries++
                    SecureLog.i(TAG, "transient subscription fetch failure; retry=$retries")
                    delay(FETCH_RETRY_DELAY_MS * retries)
                }
            }
        }

        /**
         * Phase 1: row lookup, gated fetch (fallback once on transport failures),
         * parse and engine validation. No DB writes; ID creation is an atomic
         * consent-guarded DataStore edit.
         */
        private suspend fun prepareRefresh(
            id: Long,
            attemptAt: Long,
        ): PreparedRefresh {
            val sub = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
            // The manual sentinel row is local-only — it must never
            // be fetched: no refresh job is enqueued for it and
            // updateAlways is never set, so reaching here is a
            // caller bug. Fail fast rather than feed the fetcher a
            // non-URL.
            if (isManualSubscription(sub.url)) throw SubscriptionError.NotFound
            var usedFallback = false
            val body =
                try {
                    fetchWithRetry(sub.url, sub.allowInsecureHttp)
                } catch (e: SubscriptionError) {
                    // Provider-published fallback URL: retry once on transport
                    // failures only — HTTP/parse/policy errors are authoritative.
                    val fallback = sub.fallbackUrl
                    if ((e is SubscriptionError.Network || e is SubscriptionError.Timeout) &&
                        fallback != null
                    ) {
                        SecureLog.i(TAG, "primary fetch failed sub=$id — trying fallback")
                        usedFallback = true
                        fetchWithRetry(fallback, sub.allowInsecureHttp)
                    } else {
                        throw e
                    }
                }
            val candidate =
                try {
                    parseAndValidate(id, body.body, body.contentType)
                } catch (e: SubscriptionError.EmptyResult) {
                    // No nodes: say why only when the provider's own userinfo does.
                    throw inactiveReason(body.userInfo, Instant.now().epochSecond)
                        ?.let(SubscriptionError::Inactive) ?: e
                }
            return PreparedRefresh(
                // The row URL the body was fetched from — commitRefresh re-reads
                // the row and re-fetches when an editUrl changed it mid-flight.
                fetchedUrl = sub.url,
                attemptAt = attemptAt,
                usedFallback = usedFallback,
                body = body,
                candidate = candidate,
            )
        }

        /**
         * Classify → parse (off the caller's dispatcher) → dedupe → engine
         * validation. Shared by [refresh] and [editUrl]; performs no DB
         * writes, so it is safe to run outside any lock.
         */
        private suspend fun parseAndValidate(
            id: Long,
            body: ByteArray,
            contentType: String?,
        ): ParsedCandidate {
            val classified = classifier.classify(body, contentType)
            // Parsing is CPU-bound over up to 8 MiB — keep it off the caller's
            // (often main) dispatcher. Duplicate node ids would emit duplicate
            // outbound tags — dedupe before validate + commit.
            val parsed =
                withContext(Dispatchers.Default) {
                    dispatcher.parse(classified.format, classified.body, id)
                }
            val nodes = parsed.nodes.distinctBy { it.id }
            if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()

            // Validate the candidate against the engine BEFORE touching the DB:
            // parsed-but-unusable nodes must never replace a working set.
            try {
                validator.validate(nodes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SubscriptionError.ConfigRejected
            }

            return ParsedCandidate(
                entities =
                    nodes.mapIndexed { index, n ->
                        NodeEntity(
                            id = n.id,
                            subscriptionId = id,
                            name = n.name,
                            protocol = n.protocol.name,
                            server = n.server,
                            port = n.port,
                            outboundJson = n.outboundJson,
                            rawUri = n.rawUri,
                            position = index,
                        )
                    },
                skipped = parsed.skipped,
                format = classified.format,
            )
        }

        /**
         * Phase 2 of [refresh]: assumes [lockFor] is held. Re-reads the row
         * first — if it vanished, nothing is written; if an [editUrl] changed
         * the URL while phase 1 was in flight, the prepared candidate is
         * stale and the whole refresh re-runs under the held lock (editUrl
         * holds the same lock across its own fetch + commit, so the URL
         * cannot change again). Throws on failure; callers map to Result.
         *
         * A periodic run is re-gated HERE, at the single commit entry, rather
         * than only in [refresh]'s phase 2: eligibility was checked at
         * admission, but policy/enabled can flip while a fetch is in flight —
         * and a global-interval flip needs no subscription lock at all, so
         * it can land even during this method's under-lock replacement fetch.
         * The re-gate therefore also guards the url-changed re-pass's second
         * entry. A skip returns the typed failure without writing; a
         * DAO/DataStore read failure escapes to the caller's [failRefresh]
         * catch. Manual commits are never re-gated.
         */
        private suspend fun commitRefresh(
            id: Long,
            prepared: PreparedRefresh,
            trigger: RefreshTrigger,
        ): Result<RefreshOutcome> {
            if (trigger == RefreshTrigger.Periodic) {
                periodicSkipReasonLocked(id)?.let { skip ->
                    SecureLog.i(TAG, "dropping periodic refresh — no longer eligible sub=$id")
                    return Result.failure(skip)
                }
            }
            val current = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
            if (current.url != prepared.fetchedUrl) {
                SecureLog.i(TAG, "url changed mid-refresh sub=$id — re-fetching under lock")
                return refreshLocked(id, trigger)
            }
            // Node swap + success metadata commit atomically — a crash between
            // them can't leave nodes updated but the subscription flagged stale.
            transactions.run {
                nodeDao.replaceForSubscription(id, prepared.candidate.entities)
                subscriptionDao.markSuccess(
                    id = id,
                    updatedAt = Instant.now().toEpochMilli(),
                    attemptAt = prepared.attemptAt,
                    userInfoJson = prepared.body.userInfo?.let { json.encodeToString(it) },
                    supportUrl = prepared.body.supportUrl,
                    updateIntervalMinutes = prepared.body.updateIntervalMinutes,
                    announce = prepared.body.announce,
                    updateAlways = prepared.body.updateAlways,
                    // A fallback response that doesn't re-publish its own
                    // fallback-url must not erase the endpoint that just
                    // worked — keep the stored one in that case.
                    fallbackUrl =
                        prepared.body.fallbackUrl
                            ?: if (prepared.usedFallback) current.fallbackUrl else null,
                )
            }
            SecureLog.i(
                TAG,
                "refreshed sub=$id nodes=${prepared.candidate.entities.size}" +
                    " skipped=${prepared.candidate.skipped.size} fmt=${prepared.candidate.format}",
            )
            postCommitRefresh(id, prepared.body)
            return Result.success(
                RefreshOutcome(
                    nodeCount = prepared.candidate.entities.size,
                    skipped = prepared.candidate.skipped,
                ),
            )
        }

        /**
         * Post-commit bookkeeping for [refresh] — runs while the subscription
         * lock is still held and must not flip a committed success into a
         * reported failure, so every failure is logged and swallowed.
         */
        private suspend fun postCommitRefresh(
            id: Long,
            committed: FetchedSubscription,
        ) {
            runCatching {
                var sub = subscriptionDao.get(id) ?: return@runCatching
                // Provider-declared migration: repoint the stored URL at the
                // new location. The header was already validated (public
                // http(s) URL / bare domain) at fetch time.
                val migrated =
                    committed.movedPermanentlyTo ?: committed.newUrl
                        ?: committed.newDomain?.let { domain ->
                            runCatching {
                                sub.url
                                    .toHttpUrl()
                                    .newBuilder()
                                    .host(domain)
                                    .build()
                                    .toString()
                            }.getOrNull()
                        }
                if (migrated != null && migrated != sub.url &&
                    isMigrationAllowed(migrated, sub.allowInsecureHttp)
                ) {
                    subscriptionDao.update(sub.copy(url = migrated))
                    sub = subscriptionDao.get(id) ?: return@runCatching
                    SecureLog.i(TAG, "subscription migrated sub=$id")
                }
                prefsMutex.withLock {
                    // A refresh can retire node ids (credential rotation gives a
                    // node a new id). Prefs are never migrated across ids — the
                    // match would be a guess — but orphaned rows are pruned so
                    // the table can't grow unboundedly.
                    nodePreferenceDao.deleteOrphans()
                    // If the selected node vanished (disabled subs' nodes count as
                    // unusable too), clear it so the next connect picks a sane
                    // default. The conditional clear can't wipe a selection the
                    // user made concurrently. The Auto sentinel has no node row —
                    // it must never be treated as a vanished selection.
                    val selected = settings.selectedNodeId.first()
                    // Usable = enabled-sub AND not pref-disabled — a selection
                    // pointing at a user-disabled node must fall back too.
                    if (selected != null && selected != NodeSelection.AUTO_ID &&
                        nodeDao.getUsable().none { it.id == selected }
                    ) {
                        settings.clearSelectedNodeIdIf(selected)
                        SecureLog.i(TAG, "cleared selection — selected node vanished in refresh")
                    }
                }
                // Apply profile-title only when the name is still the
                // auto-derived host — never overwrite a name the user typed.
                if (!committed.profileTitle.isNullOrBlank() &&
                    sub.name == deriveName(sub.url)
                ) {
                    subscriptionDao.update(sub.copy(name = committed.profileTitle))
                    sub = sub.copy(name = committed.profileTitle)
                }
                // (Re)register background refresh — the provider interval or
                // the user's policy may have changed mid-flight; `sub` was
                // re-read (and possibly repointed) after commit, so it is the
                // freshest snapshot held under the lock.
                rescheduleLocked(sub)
                // Expiry alert: once per expiry value, inside the warning
                // window. The alerted-key embeds the expiry so a renewal
                // re-arms the alert. The check→notify→mark sequence is
                // serialized with the startup scan via [expiryMutex].
                expiryMutex.withLock {
                    val expire = committed.userInfo?.expireEpochSeconds
                    if (expiryAlertDue(
                            expire,
                            System.currentTimeMillis(),
                            alreadyAlerted =
                                expire != null &&
                                    expiryAlreadyAlerted(
                                        settings.expiryAlerted.first(),
                                        id,
                                        expire,
                                    ),
                        )
                    ) {
                        if (expiryNotifier.notifyExpiring(id, sub.name, expire!!)) {
                            settings.markExpiryAlerted(expiryAlertKey(id, expire))
                        }
                    }
                }
            }.onFailure {
                SecureLog.w(TAG, "post-commit bookkeeping failed sub=$id: ${it.javaClass.simpleName}")
            }
        }

        /**
         * Import pasted share links (`vless://…`, `vmess://…`, …) as manual
         * nodes. The nodes are stored under the reserved
         * [MANUAL_SUBSCRIPTION_URL] row — a sentinel that is never fetched
         * (refresh() fails fast on it, no refresh job is ever enqueued for
         * it), so its node rows are append/upsert only, not
         * replace-per-refresh.
         *
         * The same last-known-good contract as [refresh] applies: the parse
         * AND the engine validation must pass before anything is committed,
         * and a multi-line paste imports every usable link. A sentinel row
         * created for a failed first import is removed again so an empty
         * "Manual servers" entry never lingers.
         *
         * Idempotent on node id: node ids are content hashes salted with the
         * manual row's id, so re-importing a known link upserts the same row
         * in place and the same link under a remote subscription stays a
         * distinct row. The parse runs off the calling dispatcher.
         *
         * Guarded by [manualMutex] end to end: two concurrent imports must
         * not both create a sentinel row, and [removeManualNode] must not
         * drop the row between this lookup and the commit.
         */
        suspend fun importShareLink(uri: String): Result<RefreshOutcome> =
            manualMutex.withLock {
                var subId: Long? = null
                var createdRow = false
                var committed = false
                try {
                    subId = subscriptionDao.findIdByUrl(MANUAL_SUBSCRIPTION_URL)
                    if (subId == null) {
                        // Insert the sentinel row first — node identity is
                        // salted with the subscription id, so the parse must
                        // run against the id that will persist the node.
                        subId = subscriptionDao.insert(manualSubscriptionEntity())
                        createdRow = true
                    }
                    val parsed =
                        withContext(Dispatchers.Default) {
                            uriListParser.parse(uri.trim(), subId)
                        }
                    // Nothing commits before the engine accepts the candidate —
                    // a parser-valid link whose outbound sing-box rejects would
                    // otherwise break the compiled config of every other
                    // subscription too.
                    try {
                        validator.validate(parsed.nodes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw SubscriptionError.ConfigRejected
                    }
                    // Append/upsert: a re-import REPLACEs the same primary
                    // key in place; ids are deduped by construction. Every
                    // parsed node is kept — the paste was the user's intent.
                    val basePosition =
                        nodeDao
                            .forSubscription(subId)
                            .maxOfOrNull { it.position + 1 } ?: 0
                    val entities =
                        parsed.nodes.mapIndexed { index, node ->
                            node.toEntity(subId, basePosition + index)
                        }
                    transactions.run { nodeDao.upsertAll(entities) }
                    committed = true
                    SecureLog.i(TAG, "share-link imported sub=$subId nodes=${entities.size}")
                    Result.success(
                        RefreshOutcome(nodeCount = entities.size, skipped = parsed.skipped),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionError) {
                    SecureLog.w(TAG, "share-link import failed: ${e.safeMessage()}")
                    Result.failure(e)
                } catch (e: Exception) {
                    // e.message can embed the pasted URI — log the type only.
                    SecureLog.w(TAG, "share-link import failed: ${e.javaClass.simpleName}", e)
                    Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
                } finally {
                    // In `finally` so the cancellation path is covered too —
                    // an aborted first import must not leave an empty
                    // "Manual servers" row behind. NonCancellable: the write
                    // has to outlive the cancelled caller.
                    if (!committed) {
                        withContext(NonCancellable) { dropEmptyManualRow(subId, createdRow) }
                    }
                }
            }

        /**
         * Remove the manual row when this call created it and nothing was
         * committed into it — a failed first import must not leave an empty
         * "Manual servers" entry behind. Best-effort: the row is recreated
         * on the next import anyway.
         */
        private suspend fun dropEmptyManualRow(subId: Long?, createdRow: Boolean) {
            if (!createdRow || subId == null) return
            runCatching {
                if (nodeDao.countForSubscription(subId) == 0) {
                    subscriptionDao.delete(subId)
                }
            }.onFailure {
                SecureLog.w(TAG, "manual row rollback failed: ${it.javaClass.simpleName}")
            }
        }

        private fun ProxyNode.toEntity(
            subscriptionId: Long,
            position: Int,
        ) = NodeEntity(
            id = id,
            subscriptionId = subscriptionId,
            name = name,
            protocol = protocol.name,
            server = server,
            port = port,
            outboundJson = outboundJson,
            rawUri = rawUri,
            position = position,
        )

        private fun manualSubscriptionEntity() =
            SubscriptionEntity(
                name = MANUAL_SUBSCRIPTION_NAME,
                url = MANUAL_SUBSCRIPTION_URL,
                createdAtEpochMs = Instant.now().toEpochMilli(),
                // Populated at import so the card doesn't claim the row was
                // never refreshed.
                lastUpdatedAtEpochMs = Instant.now().toEpochMilli(),
                lastAttemptAtEpochMs = null,
                lastError = null,
                enabled = true,
                userInfoJson = null,
                supportUrl = null,
                updateIntervalMinutes = null,
                announce = null,
                fallbackUrl = null,
                allowInsecureHttp = false,
            )

        /**
         * Re-check persisted expiry on process start — the refresh path only
         * evaluates expiry after a successful fetch, so a manual-only or
         * long-interval subscription would never alert. Runs under
         * [expiryMutex]: the check→notify→mark sequence must be serialized
         * with refresh's own expiry check or both can notify for the same
         * expiry on startup.
         */
        suspend fun checkPersistedExpiryAlerts() =
            expiryMutex.withLock {
                subscriptionDao.getAll().forEach { sub ->
                    val info =
                        sub.userInfoJson
                            ?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() }
                    val expire = info?.expireEpochSeconds ?: return@forEach
                    if (expiryAlertDue(
                            expire,
                            System.currentTimeMillis(),
                            alreadyAlerted =
                                expiryAlreadyAlerted(
                                    settings.expiryAlerted.first(),
                                    sub.id,
                                    expire,
                                ),
                        )
                    ) {
                        if (expiryNotifier.notifyExpiring(sub.id, sub.name, expire)) {
                            settings.markExpiryAlerted(expiryAlertKey(sub.id, expire))
                        }
                    }
                }
            }

        /**
         * Flip the enabled flag and reconcile the world around it:
         *  - the periodic refresh job is re-resolved (schedule() cancels the
         *    job when `enabled = false`, re-enqueues when re-enabled under the
         *    current override/provider interval);
         *  - a selected node owned by a now-disabled subscription is cleared
         *    with the same conditional clear refresh uses — the selection can't
         *    silently point at a node the compile path no longer emits.
         */
        suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) = lockFor(id).withLock {
            subscriptionDao.get(id) ?: return@withLock
            subscriptionDao.setEnabled(id, enabled)
            if (!enabled) {
                prefsMutex.withLock {
                    val selected = settings.selectedNodeId.first()
                    if (selected != null &&
                        nodeDao.forSubscription(id).any { it.id == selected }
                    ) {
                        settings.clearSelectedNodeIdIf(selected)
                    }
                }
            }
            try {
                rescheduleLocked(subscriptionDao.get(id) ?: return@withLock)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w(TAG, "reschedule after toggle failed sub=$id: ${e.javaClass.simpleName}")
            }
        }

        /**
         * (Re)register the refresh job described by [sub] — callers hold
         * [lockFor] and pass a row read inside that lock, so a stale entity
         * snapshot can't schedule over a newer user override. The manual
         * sentinel row is never schedulable; a stray job for it is cancelled
         * instead.
         */
        private suspend fun rescheduleLocked(sub: SubscriptionEntity) {
            if (isManualSubscription(sub.url)) {
                scheduler.cancel(sub.id)
                return
            }
            scheduler.schedule(
                subscriptionId = sub.id,
                providerMinutes = sub.updateIntervalMinutes,
                policy = RefreshPolicy.fromStorage(sub.refreshPolicy, sub.refreshFixedMinutes),
                userOverrideMinutes = settings.autoRefreshMinutes.first(),
                enabled = sub.enabled,
            )
        }

        /**
         * Persist a per-subscription refresh policy and re-resolve its job —
         * the write and the reschedule run under the row's own lock, so an
         * in-flight refresh's post-commit re-registration reads the NEW
         * policy, never the pre-write snapshot. The manual sentinel refuses:
         * it is local-only and never scheduled.
         *
         * A scheduler failure propagates: the persisted policy stays (it IS
         * saved — no fake success), but the caller must surface that the
         * reschedule didn't happen; the next reconcile repairs the job.
         */
        suspend fun setRefreshPolicy(
            id: Long,
            policy: RefreshPolicy,
        ) = lockFor(id).withLock {
            val sub = subscriptionDao.get(id) ?: return@withLock
            if (isManualSubscription(sub.url)) return@withLock
            subscriptionDao.updateRefreshPolicy(
                id,
                policy.storageKey,
                policy.storageFixedMinutes,
            )
            rescheduleLocked(subscriptionDao.get(id)!!)
        }

        /**
         * Reconcile scheduled work against current rows — the app's startup
         * (`inheritingOnly = false`) and global-change (`= true`) passes both
         * land here. Every row is re-read under its own lock before any
         * schedule call, so a reconcile can never overwrite a newer override
         * that landed between the list read and the lock; the inherit filter
         * is checked on that fresh row too, not on the caller's snapshot.
         * Orphan pruning runs after the pass.
         *
         * Returns true only when every listed row rescheduled AND the prune
         * completed. Any per-row or prune failure is logged and reported as
         * `false` — the caller must not treat the pass as done (the app
         * keeps its full-pass flag so the next emission retries a full
         * reconcile). Cancellation propagates.
         */
        suspend fun reconcileRefreshSchedules(inheritingOnly: Boolean): Boolean {
            var complete = true
            val ids = subscriptionDao.getAll().map { it.id }.toSet()
            ids.forEach { id ->
                lockFor(id).withLock {
                    try {
                        val current = subscriptionDao.get(id) ?: return@withLock
                        // A row whose first add is still in flight may yet be rolled back —
                        // it must not get a job (add schedules it itself on success).
                        if (id in pendingAdds.value) return@withLock
                        // Only inheriting rows can observe a global change.
                        if (inheritingOnly &&
                            RefreshPolicy.fromStorage(
                                current.refreshPolicy,
                                current.refreshFixedMinutes,
                            ) != RefreshPolicy.InheritGlobal
                        ) {
                            return@withLock
                        }
                        rescheduleLocked(current)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        complete = false
                        SecureLog.w(TAG, "reconcile failed sub=$id: ${e.javaClass.simpleName}")
                    }
                }
            }
            try {
                // Per-candidate existence is re-checked inside the scheduler
                // against the CURRENT row — `ids` is already a stale snapshot
                // by the time its queue is queried, and a subscription added
                // since must not be pruned as an orphan.
                scheduler.reconcile { subscriptionDao.get(it) != null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false
                SecureLog.w(TAG, "refresh orphan reconcile failed: ${e.javaClass.simpleName}")
            }
            return complete
        }

        /** User-visible name; blank input keeps the current one. */
        suspend fun rename(
            id: Long,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            subscriptionDao.updateName(id, trimmed)
        }

        /**
         * Repoint the subscription at [newUrl]: the candidate is fetched,
         * classified, parsed, and engine-validated through the same pipeline as
         * [refresh] BEFORE anything commits. On success the URL repoint, the
         * node swap, and the success metadata land in a single transaction — a
         * failure anywhere leaves the stored URL and the last-known-good nodes
         * untouched.
         *
         * The whole fetch + commit runs under this subscription's lock: a
         * concurrent [refresh] of the same row either commits first (this
         * edit then proceeds normally) or sees the new URL in its phase-2
         * re-read and re-fetches — a stale candidate is never committed.
         */
        suspend fun editUrl(
            id: Long,
            newUrl: String,
        ): Result<RefreshOutcome> =
            lockFor(id).withLock {
                val trimmed = newUrl.trim()
                val attemptAt = Instant.now().toEpochMilli()
                val result =
                    try {
                        val sub = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
                        val candidate =
                            runCatching { trimmed.toHttpUrl() }.getOrNull()
                                ?: throw SubscriptionError.BadUrl
                        // The transport gate is the stored per-subscription opt-in,
                        // same as refresh() — an https row pointed at cleartext is
                        // rejected unless the user opted in.
                        if (!candidate.isHttps && !sub.allowInsecureHttp) {
                            throw SubscriptionError.InsecureTransport
                        }
                        if (trimmed == sub.url) {
                            // Same-URL edit is a manual refresh — never gated.
                            return@withLock refreshLocked(id, RefreshTrigger.Manual)
                        }
                        val body = fetchWithRetry(trimmed, sub.allowInsecureHttp)
                        val parsed = parseAndValidate(id, body.body, body.contentType)
                        // URL repoint + node swap + success metadata atomically.
                        transactions.run {
                            nodeDao.replaceForSubscription(id, parsed.entities)
                            subscriptionDao.updateUrlAndMarkSuccess(
                                id = id,
                                url = trimmed,
                                updatedAt = Instant.now().toEpochMilli(),
                                attemptAt = attemptAt,
                                userInfoJson = body.userInfo?.let { json.encodeToString(it) },
                                supportUrl = body.supportUrl,
                                updateIntervalMinutes = body.updateIntervalMinutes,
                                announce = body.announce,
                                updateAlways = body.updateAlways,
                                fallbackUrl = body.fallbackUrl,
                            )
                        }
                        SecureLog.i(TAG, "edited url sub=$id nodes=${parsed.entities.size} skipped=${parsed.skipped.size} fmt=${parsed.format}")
                        Result.success(
                            RefreshOutcome(nodeCount = parsed.entities.size, skipped = parsed.skipped),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SubscriptionError) {
                        SecureLog.w(TAG, "editUrl failed sub=$id: ${e.safeMessage()}")
                        runCatching { subscriptionDao.markAttempt(id, attemptAt, e.safeMessage()) }
                        Result.failure(e)
                    } catch (e: Exception) {
                        SecureLog.w(TAG, "editUrl failed sub=$id: ${e.javaClass.simpleName}", e)
                        runCatching { subscriptionDao.markAttempt(id, attemptAt, "unexpected error") }
                        Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
                    }

                if (result.isSuccess) {
                    // A replaced node set can drop the selected node — same
                    // conditional cleanup refresh runs post-commit.
                    runCatching {
                        prefsMutex.withLock {
                            val selected = settings.selectedNodeId.first()
                            // AUTO_ID has no node row — never a vanished selection.
                            // Same usable-set rule as refresh: a pref-disabled
                            // selected node must fall back, not compile-fail.
                            if (selected != null && selected != NodeSelection.AUTO_ID &&
                                nodeDao.getUsable().none { it.id == selected }
                            ) {
                                settings.clearSelectedNodeIdIf(selected)
                            }
                        }
                        subscriptionDao.get(id)?.let { rescheduleLocked(it) }
                    }.onFailure {
                        SecureLog.w(TAG, "post-editUrl bookkeeping failed sub=$id: ${it.javaClass.simpleName}")
                    }
                }
                return result
            }

        /**
         * Delete one manually imported node. The manual row's nodes are
         * append-only (no refresh replaces them), so this is the only way to
         * undo a bad paste. The row itself goes once its last node does — an
         * empty "Manual servers" entry has nothing to show and would be
         * recreated on the next import anyway.
         */
        suspend fun removeManualNode(nodeId: String) =
            manualMutex.withLock {
                val node = nodeDao.get(nodeId) ?: return@withLock
                val sub = subscriptionDao.get(node.subscriptionId) ?: return@withLock
                // Only the manual sentinel is per-node removable — a remote
                // subscription's nodes are owned by its refresh.
                if (!isManualSubscription(sub.url)) return@withLock
                prefsMutex.withLock {
                    // One transaction: the row cleanup must not be a second
                    // write that re-emits the node set and triggers a duplicate
                    // tunnel rebuild for a single logical change.
                    transactions.run {
                        nodeDao.delete(nodeId)
                        // The node's user prefs die with it — manual nodes are
                        // never re-created, so a pref row could only linger as
                        // an orphan.
                        nodePreferenceDao.delete(nodeId)
                        // Nothing left to show — the sentinel row goes too, so an
                        // empty "Manual servers" entry never lingers.
                        if (nodeDao.countForSubscription(node.subscriptionId) == 0) {
                            subscriptionDao.delete(node.subscriptionId)
                        }
                    }
                    // A selection can't outlive its node — same conditional clear
                    // the refresh path uses (a concurrent pick isn't wiped).
                    settings.clearSelectedNodeIdIf(nodeId)
                }
                SecureLog.i(TAG, "manual node removed sub=${node.subscriptionId}")
            }

        suspend fun remove(id: Long) {
            // Serialized with this row's refresh commit: a refresh that already
            // fetched must commit before the row disappears, not after —
            // otherwise node rows would be re-inserted against a deleted
            // (later reusable) subscription id. The slow fetch phase stays
            // outside the lock, so remove() is never blocked by a hung fetch;
            // the committing refresh re-reads the row and reports NotFound.
            lockFor(id).withLock {
                // The manual sentinel row is structural — the UI hides its
                // delete affordance; refuse a stray call so the row can't
                // vanish and orphan its node rows' owner name.
                val sub = subscriptionDao.get(id) ?: return@withLock
                if (isManualSubscription(sub.url)) {
                    SecureLog.w(TAG, "refused to remove manual sentinel sub=$id")
                    return@withLock
                }
                // Rows commit atomically — a crash between deletes would orphan a
                // subscription with no nodes and no scheduled recovery. The work is
                // cancelled only after commit so a failed delete leaves the retry job.
                deleteRows(id)
                scheduler.cancel(id)
            }
        }

        // ---- per-node user preferences (slice: favorites → node control) ----

        /**
         * Toggle a node's favorite flag. Guarded by [prefsMutex] — a commit
         * can't observe a half-applied prefs write, and the toggle never
         * waits on an unrelated subscription's refresh. Prefs are stored by
         * node id and survive node-table replacement.
         */
        suspend fun setNodeFavorite(
            nodeId: String,
            favorite: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setFavorite(nodeId, favorite)
        }

        /** Local display name override — blank clears back to provider name. */
        suspend fun setNodeCustomName(
            nodeId: String,
            customName: String?,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setCustomName(nodeId, customName?.takeIf { it.isNotBlank() })
        }

        /** Hidden nodes disappear from user lists but stay in the effective
         *  node set (hiding is presentation; disabling is routing). */
        suspend fun setNodeHidden(
            nodeId: String,
            hidden: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setHidden(nodeId, hidden)
        }

        /**
         * Enable/disable a node for VPN use. Disabling the currently selected
         *  node falls back the selection (same conditional clear refresh uses
         *  — a concurrent user pick isn't wiped). Node-set fingerprint changes
         *  follow automatically via observeUsable, rebuilding a live session.
         */
        suspend fun setNodeEnabled(
            nodeId: String,
            enabled: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setEnabled(nodeId, enabled)
            if (!enabled) {
                val selected = settings.selectedNodeId.first()
                if (selected == nodeId) {
                    settings.clearSelectedNodeIdIf(nodeId)
                }
            }
        }

        /**
         * Record a failed attempt and return the typed failure to report.
         * Non-subscription throwables are collapsed to a fixed string — their
         * messages can embed request URLs. Marking a row that no longer
         * exists is a harmless no-op.
         */
        private suspend fun failRefresh(
            id: Long,
            attemptAt: Long,
            e: Exception,
        ): SubscriptionError {
            val typed = e as? SubscriptionError ?: SubscriptionError.ParseFailed(e.javaClass.simpleName)
            val persisted =
                if (e is SubscriptionError) {
                    SecureLog.w(TAG, "refresh failed sub=$id: ${e.safeMessage()}")
                    e.safeMessage()
                } else {
                    SecureLog.w(TAG, "refresh failed sub=$id: ${e.javaClass.simpleName}", e)
                    "unexpected error"
                }
            runCatching { subscriptionDao.markAttempt(id, attemptAt, persisted) }
            return typed
        }

        /** Phase-1 output of [refresh]: everything needed to commit, captured
         *  before the subscription lock is taken. */
        private data class PreparedRefresh(
            val fetchedUrl: String,
            val attemptAt: Long,
            val usedFallback: Boolean,
            val body: FetchedSubscription,
            val candidate: ParsedCandidate,
        )

        /** Parse + validation output shared by [refresh] and [editUrl]. */
        private data class ParsedCandidate(
            val entities: List<NodeEntity>,
            val skipped: List<SkippedNode>,
            val format: SubscriptionFormat,
        )

        private fun deriveName(url: String): String = runCatching { url.toHttpUrl().host }.getOrNull() ?: "subscription"

        private fun SubscriptionError.safeMessage(): String =
            when (this) {
                is SubscriptionError.Http -> identificationErrorToken()!!
                is SubscriptionError.Network -> "network unavailable"
                is SubscriptionError.Timeout -> "request timed out"
                is SubscriptionError.TooLarge -> "response too large"
                is SubscriptionError.UnsupportedFormat -> "unsupported format"
                is SubscriptionError.ParseFailed -> "parse failed"
                is SubscriptionError.EmptyResult -> "no usable nodes"
                is SubscriptionError.Inactive -> "no usable nodes"
                is SubscriptionError.BadUrl -> "bad url"
                is SubscriptionError.ConfigRejected -> "rejected by engine"
                is SubscriptionError.InsecureTransport -> "https required"
                is SubscriptionError.ForbiddenAddress -> "redirect to local address blocked"
                is SubscriptionError.DeviceIdentificationRejected,
                is SubscriptionError.DeviceLimitReached -> identificationErrorToken()!!
                is SubscriptionError.NotFound -> "subscription removed"
                is SubscriptionError.Superseded -> "superseded"
            }

        private suspend fun SubscriptionEntity.toDomain(): SubscriptionProfile =
            SubscriptionProfile(
                id = id,
                name = name,
                url = url,
                createdAt = Instant.ofEpochMilli(createdAtEpochMs),
                lastUpdatedAt = lastUpdatedAtEpochMs?.let(Instant::ofEpochMilli),
                lastAttemptAt = lastAttemptAtEpochMs?.let(Instant::ofEpochMilli),
                lastError = lastError,
                nodeCount = nodeDao.countForSubscription(id),
                enabled = enabled,
                userInfo = userInfoJson?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() },
                supportUrl = supportUrl,
                updateIntervalMinutes = updateIntervalMinutes,
                announce = announce,
                updateAlways = updateAlways,
                allowInsecureHttp = allowInsecureHttp,
                refreshPolicy =
                    RefreshPolicy.fromStorage(refreshPolicy, refreshFixedMinutes),
            )

        companion object {
            /**
             * Reserved `url` for the manual/local subscription row. The `manual://`
             * scheme is deliberately not http(s): fetchers only accept http(s),
             * so any path that treated the row as fetchable would fail — this
             * check just makes the failure fast and explicit. `url` is never
             * rendered raw (UI shows a redacted host or a flag check).
             */
            const val MANUAL_SUBSCRIPTION_URL = "manual://local"
            const val MANUAL_SUBSCRIPTION_NAME = "Manual servers"

            /** True for the manual row's [SubscriptionProfile] — not removable,
             *  not refreshable, not rendered as a normal subscription. */
            fun isManualSubscription(url: String): Boolean = url == MANUAL_SUBSCRIPTION_URL

            private const val TAG = "SubscriptionRepository"
            private const val MAX_FETCH_RETRIES = 1
            private const val FETCH_RETRY_DELAY_MS = 900L
        }
    }

/**
 * A provider-declared migration target is honored only when the stored
 * subscription's transport policy permits it: HTTPS always, cleartext HTTP
 * only under the per-subscription opt-in — otherwise the row would be
 * repointed somewhere the next fetch must reject, losing the working URL.
 */
internal fun isMigrationAllowed(
    migratedUrl: String,
    allowInsecureHttp: Boolean,
): Boolean {
    val url = runCatching { migratedUrl.toHttpUrl() }.getOrNull() ?: return false
    return url.isHttps || allowInsecureHttp
}
