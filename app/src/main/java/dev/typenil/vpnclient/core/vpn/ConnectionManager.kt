package dev.typenil.vpnclient.core.vpn

import android.content.Intent
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.resolveSelectionTarget
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routing/per-app snapshot the live session is actually running with. The
 * service reports it when it (re)launches an engine; the UI shows this
 * instead of the current settings, which may not be applied yet.
 */
data class AppliedSessionConfig(
    val routeMode: RouteMode,
    val perAppMode: PerAppMode,
    /** The applied include/exclude list. Compared as a set — swapping one app
     *  for another keeps the count but is still an unapplied change. The UI
     *  shows only its size. */
    val perAppPackages: Set<String>,
    /** LAN bypass the engine was compiled with — saved-vs-applied tracking. */
    val bypassLan: Boolean = false,
    /** Compiled DNS profile summary ("policy:cloudflare"-style) — displayed
     *  in Session Details. Null when the engine didn't report one. */
    val dnsProfileSummary: String? = null,
    val dnsModeKey: String? = null,
    val dnsUpstreamKey: String? = null,
)

/**
 * Provides the engine config for the currently selected node.
 * Implemented by the subscription/config layer — keeps the VPN layer free
 * of subscription and core-format details.
 */
interface NodeConfigProvider {
    /** Compile the engine config for the selected node, or null if none.
     *  The provider derives its own underlay view — the caller doesn't
     *  have to pass the physical network. */
    suspend fun compileSelected(): EngineConfig?

    /** Whether the physical underlay currently offers global IPv6 — fed by
     *  ClientVpnService's NOT_VPN-tracked network. Lives on the provider so
     *  the compile path doesn't need a ConnectivityManager read that could
     *  pick the VPN's own default network while a session is live. */
    var underlayHasIpv6: Boolean

    /** Acquire ownership of the underlay-IPv6 snapshot — returns an epoch
     *  token the caller must later pass to [releaseUnderlayEpoch]. A new
     *  epoch invalidates any prior snapshot so the next compile re-probes
     *  the physical network instead of trusting a previous session's
     *  cached value. */
    fun acquireUnderlayEpoch(): Long

    /** Release the epoch when the tracker goes away. Only the owner that
     *  acquired [epoch] can invalidate it — a stale teardown from an older
     *  session must not clobber a snapshot a newer owner has already pushed. */
    fun releaseUnderlayEpoch(epoch: Long)

    /** Push the physical underlay's IPv6 posture into the provider — the
     *  service calls this from its NOT_VPN tracker so a first compile can
     *  distinguish "not yet reported" (falls back to a physical probe) from
     *  an authoritative "no v6". */
    fun reportUnderlay(hasIpv6: Boolean)

    /** Persisted server pick — the desired outbound for any live session. */
    val selectedNodeId: Flow<String?>

    /** Display summary for a node id, or null when the node is gone. */
    suspend fun nodeSummary(id: String): NodeSummary?

    /** Check policy before a live switch, which does not pass through compilation. */
    suspend fun isSelectionAllowed(id: String): Boolean

    /**
     * Roll the persisted pick from [rejected] back to [to], only if it still equals
     * [rejected] (atomic: a newer user pick is never overwritten). Returns whether
     * it was applied.
     */
    suspend fun revertSelectionIf(rejected: String, to: String): Boolean = false

    /**
     * Fingerprint of the enabled node set's tunnel-relevant content. Changes
     * whenever the compiled outbound list would change — the signal a live
     * session must rebuild on. Insensitive to row order and to display-only
     * edits. Never null: an empty set has its own stable digest, so "nothing
     * enabled" can't be confused with "no reading yet".
     */
    val enabledNodeSetFingerprint: Flow<String>

    /**
     * Fingerprint of the node set the most recent [compileSelected] ran
     * against — the baseline a live session compares
     * [enabledNodeSetFingerprint] to. Null until this process compiles.
     */
    val compiledNodeSetFingerprint: StateFlow<String?>
}

/**
 * Process-wide connection orchestrator: owns the [VpnConnectionState] state
 * machine and coordinates UI intents ↔ [ClientVpnService] ↔ [VpnEngine].
 *
 * Same-process wiring: the service injects this singleton and reports engine
 * lifecycle; the UI calls [connect]/[disconnect].
 *
 * Invariants:
 * - every connect attempt gets a [sessionGeneration]; service callbacks and
 *   collector emissions tagged with an older generation are ignored, so a
 *   stale session can never corrupt current state;
 * - telemetry (stats/groups) may only mutate an existing `Connected` payload —
 *   it never creates or resurrects lifecycle state;
 * - engine terminal events are recorded and teardown converges through the
 *   service; the terminal error is published by [onServiceStopped];
 * - `pendingSession` is an in-memory handoff only (durable recovery is
 *   handled at the service level in a later slice).
 */
@Singleton
class ConnectionManager
    @Inject
    constructor(
        private val serviceControl: ServiceControl,
        private val configProvider: NodeConfigProvider,
        private val postStartProbe: PostStartHealthProbe,
    ) {
        /** Engine config + session generation handed to the service (too big for extras). */
        class PendingSession(
            val config: EngineConfig,
            val generation: Long,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val mutex = Mutex()

        private val _state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Idle)
        val state: StateFlow<VpnConnectionState> = _state
        private val _selectionError = MutableStateFlow<VpnError?>(null)
        /** A rejected pick is not a terminal failure of the existing live tunnel. */
        val selectionError: StateFlow<VpnError?> = _selectionError

        /** Set (under [selectionMutex]) after a rejected pick was rolled back: the
         *  reconcile that rollback triggers must not clear the notice. */
        private var keepSelectionNotice = false

        /** The user dismissed the rejected-selection notice. */
        fun clearSelectionError() {
            keepSelectionNotice = false
            _selectionError.value = null
        }

        private var postStartJob: Job? = null
        private var postStartTokens: List<HealthEvidenceToken> = emptyList()
        private var checkedEngine: VpnEngine? = null
        private var checkedGeneration = -1L
        private var pathRetryJob: Job? = null
        private var retryCooldown: Job? = null
        private var retryGeneration = -1L
        private var pathRetries = 0
        private var preparingPathRetry = false
        private val healthStore = ConnectionHealthStore()
        val health: StateFlow<ConnectionHealth> = healthStore.state
        fun healthSnapshot(): ConnectionHealth? = healthStore.snapshot()

        private var demandJob: Job? = null

        /**
         * On-demand probe for a visible Home. Connected only, single-flight, health
         * writes only (token-gated). Deliberately separate from the post-start/path-retry
         * machinery: it never touches pathRetries, retryCooldown, the path revision or
         * postStartTokens, and yields to an auto check that is already in flight.
         */
        fun requestHealthCheck() {
            if (_state.value !is VpnConnectionState.Connected || teardownRequested || engine == null) return
            if (demandJob?.isActive == true || postStartJob?.isActive == true || pathRetryJob?.isActive == true) return
            val generation = sessionGeneration
            demandJob = scope.launch {
                val tokens = issueProbeTokens() ?: return@launch
                runHealthProbes(generation, tokens)
            }
        }

        private fun recordConsent(status: HealthStatus, reason: HealthReason) {
            healthStore.record(sessionGeneration, HealthLevel.VpnConsent, status, reason, HealthSource.VpnConsent, HealthScope.LocalRuntime)
        }

        private fun recordRuntimeStarted(generation: Long) {
            for (level in listOf(HealthLevel.TunEstablished, HealthLevel.EngineRunning)) {
                healthStore.record(generation, level, HealthStatus.Ok, HealthReason.Started, HealthSource.ServiceLifecycle, HealthScope.LocalRuntime)
            }
            schedulePostStartCheck(generation)
        }

        private fun schedulePathCheck(generation: Long) {
            pathRetryJob?.cancel()
            if (generation != sessionGeneration || _state.value !is VpnConnectionState.Connected) return
            pathRetryJob = scope.launch {
                delay(PATH_STABLE_MS)
                retryCooldown?.join()
                postStartJob?.cancelAndJoin() // include both probes' cancellation cleanup
                demandJob?.cancelAndJoin() // never two parallel probe requests
                if (generation != sessionGeneration || _state.value !is VpnConnectionState.Connected ||
                    teardownRequested || pathRetries >= MAX_PATH_RETRIES
                ) return@launch
                schedulePostStartCheck(generation, restart = true)
            }
        }

        private fun schedulePostStartCheck(generation: Long, restart: Boolean = false) {
            val runtime = engine ?: return
            if (!restart && checkedGeneration == generation && checkedEngine === runtime) return
            checkedGeneration = generation
            checkedEngine = runtime
            val previous = postStartJob
            previous?.cancel()
            val pathRevision = health.value.revision
            postStartTokens = emptyList()
            preparingPathRetry = restart
            postStartJob = scope.launch {
                previous?.join()
                // Let start/rebuild publish Connected and the service's initial underlay report land.
                yield()
                // Bounded initialization barrier: no retries and no endless wait for a silent core.
                withTimeoutOrNull(GROUPS_WAIT_MS + 100) {
                    runtime.groups.first { it.isNotEmpty() }
                    selectionMutex.withLock { }
                }
                yield()
                if (generation != sessionGeneration || engine !== runtime || teardownRequested ||
                    _state.value !is VpnConnectionState.Connected
                ) return@launch
                if (restart) {
                    if (pathRevision != health.value.revision || pathRetries >= MAX_PATH_RETRIES) return@launch
                    pathRetries++
                    retryCooldown = scope.launch { delay(PATH_RETRY_INTERVAL_MS) }
                    SecureLog.d(TAG, "post-start path check Restarted")
                }
                val tokens = issueProbeTokens() ?: return@launch
                postStartTokens = tokens
                preparingPathRetry = false
                runHealthProbes(generation, tokens)
            }
        }

        /** Captures the current traffic/success/dns tokens. A token is a read-only
         *  (generation, level, revision) snapshot — it never clears observations. */
        private fun issueProbeTokens(): List<HealthEvidenceToken>? =
            listOf(HealthLevel.TrafficForwarding, HealthLevel.LastSuccessfulCheck, HealthLevel.DnsReachable)
                .mapNotNull { healthStore.token(it) }.takeIf { it.size == 3 }

        /** DNS + HTTP probes, health writes only (token-gated). Shared by the post-start
         *  check and [requestHealthCheck]; touches no retry bookkeeping. */
        private suspend fun CoroutineScope.runHealthProbes(generation: Long, tokens: List<HealthEvidenceToken>) {
            val (trafficToken, successToken, dnsToken) = tokens
            // Independent of A-01: own level/token, health writes only — a DNS
            // failure is Degraded evidence, never a state change or reconnect.
            launch {
                val dnsChecked = System.nanoTime() / 1_000_000
                val dns = try {
                    withTimeoutOrNull(DnsProbe.DEFAULT_TIMEOUT_MS) { postStartProbe.checkDns() } ?: DnsCheckResult.Timeout
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.w(TAG, "post-start dns check threw")
                    DnsCheckResult.NotRun
                }
                SecureLog.d(TAG, "post-start dns check ${dns.name}") // enum name only
                // No re-check of session state here: every non-Connected publish and
                // path/runtime change invalidates the token, and observe() rejects
                // stale tokens and generation mismatches (same as A-01).
                val (status, dnsReason) = when (dns) {
                    DnsCheckResult.Answered -> HealthStatus.Ok to HealthReason.DnsAnswered
                    DnsCheckResult.Timeout -> HealthStatus.Degraded to HealthReason.DnsTimeout
                    DnsCheckResult.Failed -> HealthStatus.Degraded to HealthReason.DnsFailed
                    DnsCheckResult.NotRun -> return@launch // not verified, so not written
                }
                healthStore.observe(
                    HealthObservation(HealthLevel.DnsReachable, status, dnsReason, HealthSource.DnsQuery,
                        HealthScope.AppDnsQuery, generation, dnsChecked, Instant.now()),
                    dnsToken,
                )
            }
            val checked = System.nanoTime() / 1_000_000
            val result = try {
                withTimeoutOrNull(IpProbe.DEFAULT_TIMEOUT_MS) { postStartProbe.check() }
                    ?: IpCheckResult(null, null, "timeout")
            } catch (e: CancellationException) {
                // Session teardown/network loss cancelled the job — not a check result.
                throw e
            } catch (e: Exception) {
                // A throwing probe is degraded evidence, never a session
                // failure. The exception itself stays unlogged — it can
                // embed endpoint details.
                SecureLog.w(TAG, "post-start check threw")
                IpCheckResult(null, null, "network")
            }
            val reason = when {
                result.ok -> HealthReason.HttpResponseRouteUnverified
                result.error == "timeout" -> HealthReason.HttpTimeout
                result.error?.startsWith("http ") == true -> HealthReason.HttpError
                result.error == "unexpected response" -> HealthReason.HttpUnexpectedResponse
                else -> HealthReason.HttpNetworkError
            }
            SecureLog.d(TAG, "post-start ip check ${reason.name}") // outcome only, never the address
            val displayTime = Instant.now()
            healthStore.observe(
                HealthObservation(HealthLevel.TrafficForwarding,
                    if (result.ok) HealthStatus.Unverified else HealthStatus.Degraded,
                    reason, HealthSource.IpEcho, HealthScope.AppHttpRouteUnverified,
                    generation, checked, displayTime),
                trafficToken,
            )
            if (result.ok) {
                healthStore.observe(
                    HealthObservation(HealthLevel.LastSuccessfulCheck, HealthStatus.Ok,
                        reason, HealthSource.IpEcho, HealthScope.AppHttpRouteUnverified,
                        generation, checked, displayTime),
                    successToken,
                )
            }
        }

        /** Consent intent the UI must launch. */
        private val _prepareIntent = MutableStateFlow<Intent?>(null)
        val prepareIntent: StateFlow<Intent?> = _prepareIntent

        @Volatile
        var pendingSession: PendingSession? = null
            private set

        /** Monotonic in-process session id; incremented once per connect attempt. */
        private var sessionGeneration = 0L
        private var sessionNode: NodeSummary? = null

        /** The outbound the current engine's config was compiled with — its
         *  selector default; the urltest group tag for an Auto compile.
         *  Diverges from [sessionNode] after a live outbound switch. */
        private var compiledNodeId: String? = null

        /** Serializes selection reconciliation — every run re-reads the latest
         *  persisted pick, so queued runs converge instead of racing. */
        private val selectionMutex = Mutex()

        /** Bounded auto-reconnect bookkeeping: consecutive engine failures
         *  retry with backoff; a stable session or a user action resets it. */
        private var failureReconnectAttempts = 0
        private var failureReconnectJob: Job? = null
        private var failureResetJob: Job? = null
        private var lastFailureError: VpnError? = null

        init {
            scope.launch {
                var pathRevision = -1L
                health.collect { evidence ->
                    if (retryGeneration != evidence.generation) {
                        pathRetryJob?.cancel()
                        retryCooldown?.cancel()
                        retryCooldown = null
                        pathRetries = 0
                        retryGeneration = evidence.generation
                    }
                    // Revisions emit even when invalidated slots were already Unverified.
                    // Preparation tolerates initial path reports; an actual request never does.
                    val runtime = evidence.observations.first { it.level == HealthLevel.EngineRunning }
                    if (runtime.status != HealthStatus.Ok || healthStore.token(HealthLevel.EngineRunning) == null ||
                        postStartTokens.any { !healthStore.isCurrent(it) }
                    ) postStartJob?.cancel()
                    if (runtime.status != HealthStatus.Ok) pathRetryJob?.cancel()
                    if (pathRevision != evidence.revision) {
                        pathRevision = evidence.revision
                        // Initial preparation captures the latest path; a retry's preparation
                        // must itself restart its debounce if another path revision lands.
                        if (runtime.status == HealthStatus.Ok && (postStartTokens.isNotEmpty() || preparingPathRetry) && evidence.observations.any {
                                it.level in ConnectionHealthStore.PATH_LEVELS && it.reason == HealthReason.PathChanged
                            }
                        ) schedulePathCheck(evidence.generation)
                    }
                }
            }
            // The persisted pick is the desired outbound for any live session:
            // a tap in Servers lands here and is applied to the running engine.
            scope.launch {
                configProvider.selectedNodeId
                    .distinctUntilChanged()
                    .collect { reconcileSelection() }
            }
        }

        /**
         * Terminal error observed while a session was running; published by
         * [onServiceStopped] once the service finished teardown. A VPN that is
         * "failing but still up" is reported as failed only after TUN/core are
         * actually cleaned.
         */
        private var pendingTerminalError: VpnError? = null

        /** A teardown intent was already sent for this session — don't resend. */
        private var teardownRequested = false

        @Volatile private var engine: VpnEngine? = null
        private var coreLogStateJob: Job? = null

        private var previousCoreLines: List<String> = emptyList()

        data class CoreLogSnapshot(val lines: List<String>, val previousSession: Boolean)

        /** Only already-redacted bounded text survives detach, never an engine/config. */
        @Synchronized fun coreLogSessionSnapshot(): CoreLogSnapshot = engine?.let {
            CoreLogSnapshot(it.coreLogSnapshot(), previousSession = false)
        } ?: CoreLogSnapshot(previousCoreLines, previousSession = previousCoreLines.isNotEmpty())

        fun coreLogSnapshot(): List<String> = coreLogSessionSnapshot().lines

        @Synchronized fun clearCoreLogs() {
            previousCoreLines = emptyList()
            engine?.clearCoreLogs()
        }
        private var statsJob: Job? = null
        private var eventsJob: Job? = null
        private var groupsJob: Job? = null
        private var connectionsJob: Job? = null
        private var statusUpdatesJob: Job? = null

        /** Live outbound groups from the running engine; empty while detached. */
        private val _groups = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
        val groups: StateFlow<List<OutboundGroupInfo>> = _groups

        /** Monotonic id of the attached engine — bumped once per [attachEngine].
         *  An in-session rebuild attaches a fresh engine under the SAME
         *  [sessionGeneration], but measurement history (urlTestTime, freshness
         *  floors, run verdicts) belongs to the engine instance — consumers
         *  scoping per-engine state key on this, not on the generation. */
        private val _engineEpoch = MutableStateFlow(0L)
        val engineEpoch: StateFlow<Long> = _engineEpoch

        /** Whether the attached engine wants status updates — false under
         *  screen-off suppression and whenever no engine is attached. `true`
         *  means "not suppressed", not "channel connected" — the command
         *  client (re)connects asynchronously underneath. */
        private val _statusUpdatesEnabled = MutableStateFlow(false)
        val statusUpdatesEnabled: StateFlow<Boolean> = _statusUpdatesEnabled

        /** Live connections through the tunnel; empty while detached. */
        private val _activeConnections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
        val activeConnections: StateFlow<List<ConnectionInfo>> = _activeConnections

        /** Coarse transport of the physical underlay carrying the tunnel —
         *  reported by the service's network observer; UNKNOWN until classified
         *  or when there is no usable underlay. */
        private val _underlyingTransport = MutableStateFlow(UnderlyingTransport.UNKNOWN)
        val underlyingTransport: StateFlow<UnderlyingTransport> = _underlyingTransport

        /** Routing/per-app snapshot the service last applied to a live engine.
         *  Null outside a session — there is no applied config to describe. */
        private val _appliedSessionConfig = MutableStateFlow<AppliedSessionConfig?>(null)
        val appliedSessionConfig: StateFlow<AppliedSessionConfig?> = _appliedSessionConfig

        /** The in-flight compile of a connect attempt; null once committed or cancelled. */
        private var prepareJob: Job? = null

        /** User pressed Connect. */
        fun connect() {
            scope.launch { mutex.withLock { startConnect(resetFailureBudget = true) } }
        }

        /**
         * Shared connect body. [resetFailureBudget] distinguishes a user-initiated
         * connect (fresh auto-reconnect budget) from a retry driven by
         * reconnect() — resetting there would make the budget never exhaust.
         */
        private fun startConnect(resetFailureBudget: Boolean) {
            when (_state.value) {
                is VpnConnectionState.Connected,
                is VpnConnectionState.Connecting,
                is VpnConnectionState.Reconnecting,
                is VpnConnectionState.Stopping,
                // A consent intent may already be outstanding — a second
                // connect() would burn a generation and recompile.
                is VpnConnectionState.Preparing,
                is VpnConnectionState.PermissionRequired,
                -> return

                else -> Unit
            }
            if (resetFailureBudget) {
                failureReconnectJob?.cancel()
                failureReconnectAttempts = 0
                lastFailureError = null
            }
            // Preparing goes out before the compile (which may touch the
            // network), and the compile runs outside the mutex: the state is
            // honest while it runs and disconnect() can cancel it at once.
            publish(VpnConnectionState.Preparing(null))
            val job = scope.launch(start = CoroutineStart.LAZY) { prepare() }
            prepareJob = job
            job.start()
        }

        private suspend fun prepare() {
            val self = currentCoroutineContext()[Job]
            val compiled =
                try {
                    Result.success(configProvider.compileSelected())
                } catch (e: CancellationException) {
                    // Cancelled work is not a user-visible failure.
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            mutex.withLock {
                // A disconnect (or a newer attempt) already took over.
                if (prepareJob !== self || _state.value !is VpnConnectionState.Preparing) return
                prepareJob = null
                val config =
                    compiled.getOrElse { e ->
                        val error =
                            if (e is EngineError) {
                                VpnError.fromEngine(e)
                            } else {
                                VpnError.Unexpected(e.message ?: "config build failed")
                            }
                        publish(VpnConnectionState.Error(error, null))
                        return
                    }
                if (config == null) {
                    publish(VpnConnectionState.Error(VpnError.NoNodeSelected, null))
                    return
                }
                val generation = ++sessionGeneration
                healthStore.begin(generation)
                pendingSession = PendingSession(config, generation)
                sessionNode = config.node
                compiledNodeId = config.node.id

                val prepare = serviceControl.prepareVpn()
                if (prepare != null) {
                    recordConsent(HealthStatus.Unverified, HealthReason.ConsentRequired)
                    _prepareIntent.value = prepare
                    publish(VpnConnectionState.PermissionRequired)
                    return
                }
                recordConsent(HealthStatus.Ok, HealthReason.ConsentGranted)
                launchService(config)
            }
        }

        /** Cancels an in-flight [prepare] and returns to Idle; false when none runs. */
        private fun cancelPreparation(): Boolean {
            val job = prepareJob ?: return false
            prepareJob = null
            job.cancel()
            failureReconnectJob?.cancel()
            failureReconnectAttempts = 0
            lastFailureError = null
            pendingTerminalError = null
            publish(VpnConnectionState.Idle)
            return true
        }

        /** System VPN-consent dialog result. */
        fun onPermissionResult(granted: Boolean) {
            _prepareIntent.value = null
            scope.launch {
                mutex.withLock {
                    // Only a consent we actually requested counts — a duplicate or
                    // stale result must not launch a second service.
                    if (_state.value !is VpnConnectionState.PermissionRequired) {
                        return@withLock
                    }
                    val session = pendingSession
                    if (!granted) {
                        healthStore.end(sessionGeneration, HealthReason.ConsentDenied)
                        pendingSession = null
                        publish(
                            VpnConnectionState.Error(
                                VpnError.PermissionDenied,
                                session?.config?.node,
                            ),
                        )
                        return@withLock
                    }
                    if (session == null) {
                        publish(VpnConnectionState.Error(VpnError.NoNodeSelected, null))
                        return@withLock
                    }
                    recordConsent(HealthStatus.Ok, HealthReason.ConsentGranted)
                    launchService(session.config)
                }
            }
        }

        fun disconnect() {
            scope.launch {
                // Preparation holds no lock, so cancelling it never waits on
                // the mutex. Main-confined like the commit step below, so the
                // two can't interleave.
                if (cancelPreparation()) return@launch
                mutex.withLock {
                    if (_state.value is VpnConnectionState.Idle ||
                        _state.value is VpnConnectionState.Stopping
                    ) {
                        return@withLock
                    }
                    // A user disconnect ends the retry chain — a pending
                    // auto-reconnect must not fire after it.
                    failureReconnectJob?.cancel()
                    failureReconnectAttempts = 0
                    lastFailureError = null
                    // User intent supersedes any pending terminal error — a normal
                    // disconnect must land on Idle, not on a stale failure.
                    pendingTerminalError = null
                    publish(VpnConnectionState.Stopping)
                    // startService can throw under background-start restrictions —
                    // report instead of leaving the machine stuck in Stopping.
                    runCatching { serviceControl.startDisconnectService() }
                        .onFailure {
                            SecureLog.w(TAG, "disconnect intent failed")
                            publish(
                                VpnConnectionState.Error(
                                    VpnError.Unexpected("failed to stop service"),
                                    sessionNode,
                                ),
                            )
                        }
                }
            }
        }

        /**
         * Stop the live session and connect again with freshly compiled
         * settings — the apply path for changes baked into the config at
         * compile time (route mode, IPv6) and the fallback when a live
         * outbound switch can't be honored. Returns false when there is no
         * session to restart (Idle/Error already pick up new settings on the
         * next connect) or while a connect is still in its pre-service phase.
         */
        suspend fun reconnect(): Boolean =
            mutex.withLock {
                when (_state.value) {
                    is VpnConnectionState.Connected,
                    is VpnConnectionState.Connecting,
                    is VpnConnectionState.Reconnecting,
                    -> {
                        Unit
                    }

                    // Consent pending: drop the stale pending session and re-run
                    // connect() — it recompiles with the new settings and the
                    // already-granted consent skips the dialog.
                    is VpnConnectionState.PermissionRequired -> {
                        pendingSession = null
                        publish(VpnConnectionState.Idle)
                        startConnect(resetFailureBudget = false)
                        return@withLock true
                    }

                    else -> {
                        return@withLock false
                    }
                }
                pendingTerminalError = null
                publish(VpnConnectionState.Stopping)
                val sent =
                    runCatching { serviceControl.startDisconnectService() }
                        .onFailure { SecureLog.w(TAG, "disconnect intent failed") }
                        .isSuccess
                if (!sent) {
                    publish(
                        VpnConnectionState.Error(
                            VpnError.Unexpected("failed to stop service"),
                            sessionNode,
                        ),
                    )
                    return@withLock false
                }
                // Teardown converges through the service → onServiceStopped → Idle.
                // The bound keeps a wedged teardown from hanging the caller.
                val settled =
                    withTimeoutOrNull(RECONNECT_SETTLE_MS) {
                        _state.first {
                            it is VpnConnectionState.Idle || it is VpnConnectionState.Error
                        }
                    }
                if (settled == null) {
                    SecureLog.w(TAG, "reconnect: teardown did not settle")
                    return@withLock false
                }
                if (_state.value is VpnConnectionState.Error) return@withLock false
                startConnect(resetFailureBudget = false)
                true
            }

        private fun launchService(config: EngineConfig) {
            publish(VpnConnectionState.Connecting(config.node))
            // startForegroundService can throw under background-start
            // restrictions — a stuck "Connecting" would be a fake state.
            runCatching { serviceControl.startConnectService() }
                .onFailure {
                    SecureLog.w(TAG, "connect intent failed")
                    pendingSession = null
                    publish(
                        VpnConnectionState.Error(
                            VpnError.Unexpected("failed to start service"),
                            sessionNode,
                        ),
                    )
                }
        }

        // region service callbacks (same process)

        /**
         * Service created the engine — collect its outputs into state.
         * [generation] tags the session the engine belongs to; emissions tagged
         * with a stale generation are dropped.
         */
        @Synchronized fun attachEngine(
            engine: VpnEngine,
            generation: Long,
        ) {
            if (generation != sessionGeneration) {
                SecureLog.w(TAG, "attachEngine with stale generation — ignored")
                return
            }
            healthStore.invalidate(generation, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
            previousCoreLines = emptyList()
            this.engine = engine
            _engineEpoch.update { it + 1 }
            coreLogStateJob?.cancel()
            coreLogStateJob = scope.launch {
                state.collect { current ->
                    if (this@ConnectionManager.engine === engine && generation == sessionGeneration) {
                        engine.setCoreLogsEnabled(
                            current is VpnConnectionState.Connecting || current is VpnConnectionState.Connected,
                        )
                    }
                }
            }
            statsJob?.cancel()
            eventsJob?.cancel()
            groupsJob?.cancel()
            connectionsJob?.cancel()
            statusUpdatesJob?.cancel()
            statusUpdatesJob =
                scope.launch {
                    engine.statusUpdatesEnabled.collect { enabled ->
                        if (generation == sessionGeneration) {
                            _statusUpdatesEnabled.value = enabled
                        }
                    }
                }
            groupsJob =
                scope.launch {
                    engine.groups.collect { groups ->
                        if (generation != sessionGeneration) return@collect
                        if (effectiveOutboundChain(_groups.value) != effectiveOutboundChain(groups)) {
                            healthStore.invalidate(generation, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
                        }
                        _groups.value = groups
                        // While Auto is selected, the urltest group's measured
                        // winner is what Home should name — refresh the label as
                        // the group reports it.
                        val current = _state.value
                        if (
                            (current is VpnConnectionState.Connected ||
                                current is VpnConnectionState.Reconnecting) &&
                            (sessionNode?.id == NodeSelection.AUTO_ID ||
                                sessionNode?.id == NodeSelection.AUTO_RUSSIA_ID)
                        ) {
                            val selectedAutoId = sessionNode?.id ?: NodeSelection.AUTO_ID
                            val resolved = resolveAutoWinnerTag(groups, selectedAutoId)
                            if (resolved != null) {
                                updateSessionNode(selectedAutoId, resolvedTag = resolved)
                            }
                        }
                    }
                }
            connectionsJob =
                scope.launch {
                    engine.connections.collect { connections ->
                        if (generation == sessionGeneration) {
                            _activeConnections.value = connections
                        }
                    }
                }
            statsJob =
                scope.launch {
                    engine.stats.collect { stats ->
                        if (generation != sessionGeneration) return@collect
                        // Telemetry mutates a Connected payload only — it is never
                        // evidence that a session is alive.
                        val current = _state.value
                        if (current is VpnConnectionState.Connected) {
                            _state.value = current.copy(stats = stats, statsReceivedAtNanos = System.nanoTime())
                        }
                    }
                }
            eventsJob =
                scope.launch {
                    engine.events.collect { event ->
                        if (generation != sessionGeneration) return@collect
                        when (event) {
                            is EngineEvent.Failed -> {
                                onEngineTerminated(VpnError.fromEngine(event.error))
                            }

                            is EngineEvent.StoppedUnexpectedly -> {
                                onEngineTerminated(VpnError.EngineFailed("core terminated unexpectedly"))
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
            // A fresh engine starts on its compiled selector default — re-apply
            // the persisted pick so a rebuilt tunnel doesn't silently revert to
            // the node the session originally connected with.
            reconcileSelection()
        }

        /**
         * Detach collectors from the current engine. [generation] `-1` forces the
         * detach; a tagged call only acts when it still owns the session — a stale
         * teardown must not strip a newer session's collectors.
         */
        @Synchronized fun detachEngine(generation: Long = -1L) {
            if (generation >= 0 && generation != sessionGeneration) return
            // Copied here, not read lazily: the engine ring is frozen by stop(), but a detach
            // that precedes stop() (engine-terminated event) misses teardown lines — accepted.
            engine?.let { previousCoreLines = it.coreLogSnapshot().takeLast(500).map { line -> line.take(512) } }
            coreLogStateJob?.cancel()
            coreLogStateJob = null
            statsJob?.cancel()
            statsJob = null
            eventsJob?.cancel()
            eventsJob = null
            groupsJob?.cancel()
            groupsJob = null
            connectionsJob?.cancel()
            connectionsJob = null
            statusUpdatesJob?.cancel()
            statusUpdatesJob = null
            _groups.value = emptyList()
            _activeConnections.value = emptyList()
            _statusUpdatesEnabled.value = false
            engine = null
        }

        /**
         * Re-apply the persisted server pick to the live engine. Triggered by
         * selection changes and by every engine attach (connect, in-session
         * rebuild): the selector's compiled default is only right when the pick
         * hasn't moved since compile time.
         */
        private fun reconcileSelection() {
            scope.launch { applyDesiredSelection() }
        }

        /**
         * Serialized under [selectionMutex]: each run re-reads the latest pick,
         * so a burst of taps converges on the final one. A live switch updates
         * the session node so Home/notification show what the tunnel actually
         * uses; when the engine can't honor it (control channel down, group
         * missing) the pick is applied by a full reconnect instead.
         */
        private suspend fun applyDesiredSelection() =
            selectionMutex.withLock {
                val eng = engine ?: return@withLock
                val desired = configProvider.selectedNodeId.first()
                val selection = NodeSelection.fromId(desired) ?: return@withLock
                if (_state.value !is VpnConnectionState.Connected &&
                    _state.value !is VpnConnectionState.Reconnecting
                ) {
                    return@withLock
                }
                val allowed = configProvider.isSelectionAllowed(desired ?: return@withLock)
                if (engine !== eng || (_state.value !is VpnConnectionState.Connected &&
                        _state.value !is VpnConnectionState.Reconnecting)) return@withLock
                if (!allowed) {
                    _selectionError.value = VpnError.UnencryptedTransport
                    // Roll the persisted pick back to the node the tunnel is on, so a
                    // later reconnect doesn't fail on a pick the user was told was
                    // not applied. Compare-and-set: a newer user pick is never undone.
                    val running = sessionNode?.id
                    if (running != null && running != desired &&
                        configProvider.revertSelectionIf(rejected = desired, to = running)
                    ) {
                        // The reconcile this revert triggers must not clear the notice.
                        keepSelectionNotice = true
                    }
                    return@withLock
                }
                if (keepSelectionNotice) keepSelectionNotice = false else _selectionError.value = null
                if (desired != sessionNode?.id) {
                    healthStore.invalidate(sessionGeneration, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
                }
                // The outbound the pick resolves to: a node id, or the urltest
                // group's tag when the user picked Auto.
                val desiredTag =
                    when (selection) {
                        NodeSelection.Auto -> NodeSelection.AUTO_ID
                        NodeSelection.AutoRussia -> NodeSelection.AUTO_RUSSIA_ID
                        is NodeSelection.Node -> selection.id
                    }
                // Groups arrive after the command client connects — a fresh engine
                // reports none yet, so wait briefly before falling back.
                val groups =
                    withTimeoutOrNull(GROUPS_WAIT_MS) {
                        eng.groups.first { it.isNotEmpty() }
                    }
                if (engine !== eng) return@withLock // detached/replaced while waiting
                if (groups == null) {
                    // Control channel never came up: a live switch is impossible.
                    // Reconnect only when the compiled default isn't already the
                    // pick — otherwise the tunnel is on the right outbound anyway.
                    if (compiledNodeId != desiredTag) reconnect()
                    return@withLock
                }
                // "auto" sits inside the "proxy" selector's items, so the first
                // selectable group containing the tag is still the right target.
                val target = resolveSelectionTarget(groups, desiredTag)
                if (target == null) {
                    // The pick isn't in any selectable group (stale id, group
                    // vanished) — a reconnect compiles with it as the default.
                    if (compiledNodeId != desiredTag) reconnect()
                    return@withLock
                }
                if (groups.first { it.tag == target }.selected == desiredTag) {
                    // Engine already on the pick — just fix a stale label.
                    updateSessionLabel(selection, groups)
                    return@withLock
                }
                if (eng.selectOutbound(target, desiredTag)) {
                    updateSessionLabel(selection, groups)
                } else {
                    reconnect()
                }
            }

        /** Display label for the applied pick: [NodeSelection.Auto] resolves
         *  through the urltest group's measured winner; a node resolves its own
         *  summary. */
        private suspend fun updateSessionLabel(
            selection: NodeSelection,
            groups: List<OutboundGroupInfo>,
        ) {
            when (selection) {
                NodeSelection.Auto -> {
                    updateSessionNode(
                        NodeSelection.AUTO_ID,
                        resolvedTag = resolveAutoWinnerTag(groups, NodeSelection.AUTO_ID),
                    )
                }

                NodeSelection.AutoRussia -> {
                    updateSessionNode(
                        NodeSelection.AUTO_RUSSIA_ID,
                        resolvedTag = resolveAutoWinnerTag(groups, NodeSelection.AUTO_RUSSIA_ID),
                    )
                }

                is NodeSelection.Node -> {
                    updateSessionNode(selection.id)
                }
            }
        }

        /**
         * Resolves the selected outbound tag reported by the engine for the
         * auto/urltest group. Only explicit non-blank selections reported by
         * the core are honored; we never guess egress from delay metrics.
         */
        private fun resolveAutoWinnerTag(
            groups: List<OutboundGroupInfo>,
            groupTag: String = NodeSelection.AUTO_ID,
        ): String? {
            val autoGroup = groups.firstOrNull { it.tag == groupTag } ?: return null
            return autoGroup.selected?.takeIf { it.isNotBlank() }
        }

        /**
         * The outbound chain session traffic actually rides: the "proxy"
         * selector's pick, plus the urltest winner only when the selector
         * routes through Auto. Report order and background re-measurements of
         * a group that isn't carrying the tunnel (the urltest winner while a
         * manual node is selected) move no traffic, so they must not
         * invalidate path evidence.
         */
        private fun effectiveOutboundChain(groups: List<OutboundGroupInfo>): Pair<String?, String?> {
            val selector = groups.firstOrNull { it.tag == ConfigCompiler.SELECTOR_TAG }?.selected
            val winner =
                if (selector == NodeSelection.AUTO_ID || selector == NodeSelection.AUTO_RUSSIA_ID) {
                    groups.firstOrNull { it.tag == selector }?.selected
                } else {
                    null
                }
            return selector to winner
        }

        /** Point the session's displayed node at [id] — the engine's
         *  selector is the source of truth, so the label follows the switch.
         *  [resolvedTag] is the node the urltest group currently prefers; when
         *  present it is mapped back through the provider and shown as
         *  "Auto → <name>". */
        private suspend fun updateSessionNode(
            id: String,
            resolvedTag: String? = null,
        ) {
            var summary = configProvider.nodeSummary(id) ?: return
            if (summary.id == NodeSelection.AUTO_ID || summary.id == NodeSelection.AUTO_RUSSIA_ID) {
                // No member is impersonated: until the urltest group reports a
                // pick the label stays the generic "Auto".
                val resolved = resolvedTag?.let { configProvider.nodeSummary(it) }
                if (resolved != null) {
                    summary =
                        summary.copy(
                            name = "${summary.name} → ${resolved.name}",
                            // The leaf's real protocol, not the group's internal
                            // OTHER placeholder — the card describes the node the
                            // tunnel is actually using.
                            protocol = resolved.protocol,
                            server = resolved.server,
                        )
                }
            }
            sessionNode = summary
            when (val current = _state.value) {
                is VpnConnectionState.Connected -> {
                    publish(current.copy(node = summary))
                }

                is VpnConnectionState.Reconnecting -> {
                    publish(current.copy(node = summary))
                }

                else -> Unit
            }
        }

        /** Ask the engine to run urltest on [groupTag]; results arrive via [groups]. */
        suspend fun urlTest(groupTag: String) {
            engine?.urlTest(groupTag)
        }

        /**
         * Close one live connection by tracker id; the removal arrives through
         * [activeConnections] on the next core event. Returns false while
         * detached or when the engine rejected the close.
         */
        suspend fun closeConnection(id: String): Boolean = engine?.closeConnection(id) ?: false

        /**
         * Allocates the session generation for a service-driven start (process
         * restart, always-on) that has no `pendingSession`. Returns -1 when a
         * session is already live — the caller must bail instead of competing
         * with it. On success the state moves to Connecting so the rebuild is
         * visible and cancellable like a user-initiated connect.
         */
        fun adoptSession(node: NodeSummary): Long {
            when (_state.value) {
                is VpnConnectionState.Connected,
                is VpnConnectionState.Connecting,
                is VpnConnectionState.Reconnecting,
                is VpnConnectionState.Stopping,
                is VpnConnectionState.Preparing,
                is VpnConnectionState.PermissionRequired,
                -> return -1L

                else -> Unit
            }
            val generation = ++sessionGeneration
            healthStore.begin(generation)
            sessionNode = node
            compiledNodeId = node.id
            teardownRequested = false
            publish(VpnConnectionState.Connecting(node))
            return generation
        }

        private fun onEngineTerminated(error: VpnError) {
            // Serialized through the same launch+mutex path as the other state
            // transitions — a terminal event must not race a user disconnect.
            scope.launch {
                mutex.withLock {
                    // Only meaningful while a session is alive; teardown converges
                    // through the service so TUN/collectors are cleaned first.
                    when (_state.value) {
                        is VpnConnectionState.Connecting,
                        is VpnConnectionState.Connected,
                        is VpnConnectionState.Reconnecting,
                        -> {
                            failureResetJob?.cancel()
                            val attempt = ++failureReconnectAttempts
                            if (attempt <= MAX_FAILURE_RECONNECTS) {
                                // Bounded auto-reconnect: the error is parked and
                                // the session restarts once teardown settles — a
                                // transient core/network failure shouldn't drop
                                // the user to a dead Error state. Reconnecting
                                // stays published through the whole backoff +
                                // teardown window (no Stopping/Idle flicker).
                                lastFailureError = error
                                val current = _state.value
                                val node =
                                    when (current) {
                                        is VpnConnectionState.Connecting -> current.node
                                        is VpnConnectionState.Connected -> current.node
                                        is VpnConnectionState.Reconnecting -> current.node
                                        else -> sessionNode
                                    } ?: return@withLock
                                publish(
                                    VpnConnectionState.Reconnecting(
                                        node,
                                        VpnConnectionState.Reconnecting.Reason.CoreFailure,
                                        attempt,
                                    ),
                                )
                                if (!requestServiceTeardown()) {
                                    // The service is unreachable — no
                                    // onServiceStopped will ever arrive, so the
                                    // settle wait would publish Error while the
                                    // tunnel is still up. Error now is honest;
                                    // teardownRequested is cleared so a later
                                    // connect isn't blocked.
                                    detachEngine()
                                    publish(VpnConnectionState.Error(error, sessionNode))
                                    return@withLock
                                }
                                scheduleFailureReconnect(attempt)
                            } else {
                                // Budget exhausted — converge to a terminal error.
                                if (pendingTerminalError == null) pendingTerminalError = error
                                if (!requestServiceTeardown()) {
                                    // Unreachable service: no onServiceStopped
                                    // will arrive — publish the parked error now.
                                    detachEngine()
                                    val terminal = pendingTerminalError ?: error
                                    pendingTerminalError = null
                                    publish(VpnConnectionState.Error(terminal, sessionNode))
                                    return@withLock
                                }
                                // Watchdog: if the service never reports
                                // onServiceStopped (wedged teardown, unreachable
                                // service) the state must not sit in
                                // Reconnecting forever — the service-side stop
                                // deadline guarantees physical convergence.
                                failureReconnectJob?.cancel()
                                failureReconnectJob =
                                    scope.launch {
                                        val settled =
                                            withTimeoutOrNull(RECONNECT_SETTLE_MS) {
                                                _state.first {
                                                    it is VpnConnectionState.Idle ||
                                                        it is VpnConnectionState.Error
                                                }
                                            }
                                        if (settled == null) {
                                            // A user disconnect cancels this job,
                                            // but the timeout can win the race —
                                            // never clobber Stopping/Idle/Error.
                                            val s = _state.value
                                            if (s !is VpnConnectionState.Connected &&
                                                s !is VpnConnectionState.Connecting &&
                                                s !is VpnConnectionState.Reconnecting
                                            ) {
                                                return@launch
                                            }
                                            SecureLog.w(
                                                TAG,
                                                "terminal teardown did not settle",
                                            )
                                            detachEngine()
                                            val terminal =
                                                pendingTerminalError
                                                    ?: lastFailureError
                                                    ?: VpnError.EngineFailed("reconnect failed")
                                            pendingTerminalError = null
                                            publish(
                                                VpnConnectionState.Error(terminal, sessionNode),
                                            )
                                        }
                                    }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        /**
         * Ask the service to tear down the live session. Returns false when the
         * disconnect intent can't be delivered even after one retry — the
         * service is unreachable and no onServiceStopped will arrive, so the
         * caller must converge the state machine itself. [teardownRequested] is
         * cleared on failure so a later connect isn't blocked by a teardown
         * that never happened.
         */
        private fun requestServiceTeardown(): Boolean {
            if (teardownRequested) return true
            teardownRequested = true
            // startService can throw under background-start restrictions —
            // the state machine must survive it.
            repeat(2) { attempt ->
                try {
                    serviceControl.startDisconnectService()
                    return true
                } catch (e: Exception) {
                    SecureLog.w(TAG, "disconnect intent failed (attempt ${attempt + 1})")
                }
            }
            // A background-start restriction can reject startService even though
            // the service is alive. Context.stopService is the physical fallback:
            // onDestroy closes the TUN and reports onServiceStopped(generation).
            val stopping =
                runCatching { serviceControl.stopVpnService() }
                    .onFailure { SecureLog.w(TAG, "stopService fallback failed") }
                    .getOrDefault(false)
            if (stopping) return true
            teardownRequested = false
            return false
        }

        /**
         * One scheduled auto-reconnect at a time — a new failure replaces the
         * pending retry. Waits out the backoff, then waits for the teardown to
         * converge (onServiceStopped → Idle) before connecting again. A user
         * disconnect cancels the job, so a retry can never fire after it; a
         * wedged teardown surfaces the parked failure instead of hanging.
         */
        private fun scheduleFailureReconnect(attempt: Int) {
            failureReconnectJob?.cancel()
            failureReconnectJob =
                scope.launch {
                    delay(failureBackoffMs(attempt))
                    val settled =
                        withTimeoutOrNull(RECONNECT_SETTLE_MS) {
                            _state.first {
                                it is VpnConnectionState.Idle || it is VpnConnectionState.Error
                            }
                        }
                    when {
                        settled == null -> {
                            if (_state.value is VpnConnectionState.Reconnecting) {
                                publish(
                                    VpnConnectionState.Error(
                                        lastFailureError
                                            ?: VpnError.EngineFailed("reconnect failed"),
                                        sessionNode,
                                    ),
                                )
                            }
                        }

                        settled is VpnConnectionState.Error -> Unit

                        else -> {
                            mutex.withLock {
                                if (_state.value is VpnConnectionState.Idle) {
                                    startConnect(resetFailureBudget = false)
                                }
                            }
                        }
                    }
                }
        }

        /** Engine + tunnel up (openTun succeeded during start). */
        fun onServiceStarted(generation: Long) {
            if (!isCurrent(generation)) return
            val node = sessionNode ?: pendingSession?.config?.node ?: return
            pendingSession = null
            teardownRequested = false
            recordRuntimeStarted(generation)
            publish(VpnConnectionState.Connected(node, Instant.now(), null))
            // Fresh session — the label below is re-published by the next
            // underlay evaluation; reset so a previous session's transport
            // doesn't linger as fake state.
            _underlyingTransport.value = UnderlyingTransport.UNKNOWN
            // A session that stays up past the stability window proves the
            // failure was transient — the next failure gets a fresh budget.
            failureResetJob?.cancel()
            failureResetJob =
                scope.launch {
                    delay(FAILURE_STABLE_MS)
                    failureReconnectAttempts = 0
                    lastFailureError = null
                }
            // A pick made while Connecting missed the reconcile gate — apply it
            // now that the session is live.
            reconcileSelection()
        }

        fun onServiceFailed(
            error: VpnError,
            generation: Long,
        ) {
            if (!isCurrent(generation)) return
            failureResetJob?.cancel()
            detachEngine()
            pendingSession = null
            clearSessionObservations()
            publish(VpnConnectionState.Error(error, sessionNode), healthGeneration = generation)
        }

        /**
         * A service-driven start (process-death restore, always-on, boot) failed
         * before it could adopt a session. [onServiceFailed] can't express this:
         * there is no generation to match, and "no engine attached" is not proof
         * that the service still owns the user's intent — a `connect()` that
         * landed while the config was compiling has a pending session of its own,
         * and a stale restore must neither clear it nor publish over its state.
         *
         * The ownership test and the publish share the lock `connect()` takes, so
         * they can't interleave with a newer session starting.
         */
        fun onSessionlessStartFailed(error: VpnError) {
            scope.launch {
                mutex.withLock {
                    if (pendingSession != null) return@withLock
                    when (_state.value) {
                        VpnConnectionState.Idle,
                        is VpnConnectionState.Error,
                        -> Unit

                        // A session owns the state — this failure belongs to a
                        // start nobody is waiting on any more.
                        else -> return@withLock
                    }
                    clearSessionObservations()
                    publish(VpnConnectionState.Error(error, null))
                }
            }
        }

        fun onServiceStopped(generation: Long) {
            if (!isCurrent(generation)) return
            failureResetJob?.cancel()
            detachEngine()
            pendingSession = null
            teardownRequested = false
            clearSessionObservations()
            val error = pendingTerminalError
            pendingTerminalError = null
            val current = _state.value
            val next =
                when {
                    error != null -> VpnConnectionState.Error(error, sessionNode)

                    // A failure was already published (onServiceFailed / onRevoke) —
                    // teardown completion must not regress it to Idle.
                    current is VpnConnectionState.Error -> return

                    // A pending auto-reconnect waits on Idle here — publishing it is
                    // what resumes reconnect(); the brief Idle is invisible in
                    // practice because the retry republishes immediately.
                    else -> VpnConnectionState.Idle
                }
            publish(next, healthGeneration = generation)
        }

        /**
         * Physical network lost while Connected → Reconnecting (the tunnel is up
         * but starved). Called by the service's single network observer.
         */
        fun onUnderlyingNetworkLost(generation: Long = sessionGeneration) {
            scope.launch {
                mutex.withLock {
                    if (generation != sessionGeneration) return@withLock
                    healthStore.invalidate(generation, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
                    healthStore.record(generation, HealthLevel.UnderlyingNetwork, HealthStatus.Failed, HealthReason.NetworkLost, HealthSource.PlatformUnderlay, HealthScope.PhysicalUnderlay)
                    _underlyingTransport.value = UnderlyingTransport.UNKNOWN
                    val current = _state.value
                    if (current is VpnConnectionState.Connected) {
                        publish(
                            VpnConnectionState.Reconnecting(
                                current.node,
                                VpnConnectionState.Reconnecting.Reason.NetworkUnavailable,
                                attempt = 1,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Service reports the current physical underlay's transport — updates
         * the label without implying any state transition (a usable underlay can
         * exist while Connecting or Reconnecting for a dead engine).
         */
        fun reportUnderlyingTransport(transport: UnderlyingTransport) {
            _underlyingTransport.value = transport
        }

        /**
         * Service reports the routing/per-app plan it just applied to an engine.
         * Published so the UI can describe the running session instead of the
         * settings — a route-mode change is only real after a reconnect.
         */
        fun reportAppliedSessionConfig(config: AppliedSessionConfig) {
            _appliedSessionConfig.value = config
        }

        /** Evidence from an actual usable-network evaluation, not transport classification. */
        fun reportHealthUnderlay(available: Boolean, generation: Long, pathChanged: Boolean = false) {
            if (pathChanged) {
                if (generation == sessionGeneration) SecureLog.d(TAG, "health underlay PathChanged")
                healthStore.invalidate(generation, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
            }
            healthStore.record(
                generation,
                HealthLevel.UnderlyingNetwork,
                if (available) HealthStatus.Ok else HealthStatus.Failed,
                if (available) HealthReason.NetworkAvailable else HealthReason.NetworkLost,
                HealthSource.PlatformUnderlay,
                HealthScope.PhysicalUnderlay,
            )
        }

        fun onHealthStopping(generation: Long) {
            healthStore.end(generation)
        }

        fun reportHealthConsent(generation: Long) {
            healthStore.record(generation, HealthLevel.VpnConsent, HealthStatus.Ok, HealthReason.ConsentGranted, HealthSource.VpnConsent, HealthScope.LocalRuntime)
        }

        /** Session ended — the live observations describe a tunnel that no longer
         *  exists and must not linger as fake state. */
        private fun clearSessionObservations() {
            _underlyingTransport.value = UnderlyingTransport.UNKNOWN
            _appliedSessionConfig.value = null
        }

        /** A usable underlying network is back → resume Connected — but only
         *  when the Reconnecting was caused by the network loss itself. A
         *  rebuild or core-failure reconnect isn't resolved by the underlay
         *  coming back. */
        fun onUnderlyingNetworkAvailable(generation: Long = sessionGeneration) {
            scope.launch {
                mutex.withLock {
                    if (generation != sessionGeneration) return@withLock
                    healthStore.invalidate(generation, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
                    reportHealthUnderlay(true, generation)
                    val current = _state.value
                    // teardownRequested marks a failure-reconnect — the engine is
                    // dead and teardown is in flight, so Connected would be fake.
                    if (current is VpnConnectionState.Reconnecting &&
                        current.reason == VpnConnectionState.Reconnecting.Reason.NetworkUnavailable &&
                        !teardownRequested
                    ) {
                        publish(VpnConnectionState.Connected(current.node, Instant.now(), null))
                    }
                }
            }
        }

        /**
         * The service is rebuilding the TUN inside the live session (the per-app
         * package lists are baked into the fd at establish() time, so a policy
         * change needs a fresh establish). Surface it as a brief Reconnecting —
         * [onTunnelRebuilt] flips back once the new engine is up.
         */
        fun onTunnelRebuildStarted(generation: Long = sessionGeneration) {
            scope.launch {
                mutex.withLock {
                    if (generation != sessionGeneration) return@withLock
                    healthStore.invalidate(generation, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
                    val current = _state.value
                    if (current is VpnConnectionState.Connected) {
                        publish(
                            VpnConnectionState.Reconnecting(
                                current.node,
                                VpnConnectionState.Reconnecting.Reason.ApplyingChanges,
                                attempt = 1,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * The session's tunnel is back up after an in-session rebuild. Only
         * resolves a Reconnecting state — anything else (Stopping, an early
         * Connected from a network callback) is left alone. Goes through the
         * same launch+mutex path as [onTunnelRebuildStarted]: the mutex grants
         * in FIFO order, so "rebuilt" can never publish before "started".
         *
         * [node] is the outbound the rebuilt engine was compiled with — a rebuild
         * can follow a node-set change that removed the old pick, and the state
         * label must follow the engine, not the previous session.
         */
        fun onTunnelRebuilt(
            generation: Long,
            node: NodeSummary? = null,
        ) {
            scope.launch {
                mutex.withLock {
                    if (!isCurrent(generation)) return@withLock
                    if (node != null) {
                        sessionNode = node
                        compiledNodeId = node.id
                    }
                    val current = _state.value
                    // teardownRequested marks a failure-reconnect — the rebuilt
                    // engine is about to be torn down by the pending disconnect
                    // anyway, so Connected would be fake.
                    if (current is VpnConnectionState.Reconnecting && !teardownRequested) {
                        recordRuntimeStarted(generation)
                        publish(VpnConnectionState.Connected(node ?: current.node, Instant.now(), null))
                    }
                }
            }
        }

        /** onRevoke — the tunnel is already gone. */
        fun onServiceRevoked(generation: Long) {
            if (!isCurrent(generation)) return
            // Revoke is user/system intent — no auto-reconnect after it.
            failureReconnectJob?.cancel()
            failureResetJob?.cancel()
            failureReconnectAttempts = 0
            lastFailureError = null
            pendingSession = null
            pendingTerminalError = null
            detachEngine()
            clearSessionObservations()
            // Accepted sessionless callbacks still act on the manager-owned attempt.
            val healthGeneration = if (generation < 0) sessionGeneration else generation
            healthStore.end(healthGeneration, HealthReason.Revoked)
            publish(VpnConnectionState.Error(VpnError.PermissionRevoked, sessionNode), healthGeneration = healthGeneration)
        }

        // endregion

        /**
         * A callback applies only to the session that produced it. [generation]
         * `-1` means the service had no session to tag (stray start, post-teardown
         * onDestroy) — those reports apply only while no session is attached, so
         * a stale teardown can't clobber a live one.
         */
        private fun isCurrent(generation: Long): Boolean = if (generation < 0) engine == null else generation == sessionGeneration

        private fun publish(next: VpnConnectionState, healthGeneration: Long = sessionGeneration) {
            if (next !is VpnConnectionState.Connected) {
                pathRetryJob?.cancel()
                postStartJob?.cancel()
                demandJob?.cancel()
            }
            val acceptedHealthGeneration = if (healthGeneration < 0) sessionGeneration else healthGeneration
            when (next) {
                is VpnConnectionState.Error -> healthStore.end(acceptedHealthGeneration, HealthReason.StartFailed)
                VpnConnectionState.Idle, VpnConnectionState.Stopping -> healthStore.end(acceptedHealthGeneration)
                is VpnConnectionState.Reconnecting -> {
                    if (next.reason == VpnConnectionState.Reconnecting.Reason.CoreFailure) {
                        healthStore.invalidate(acceptedHealthGeneration, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
                    }
                }
                else -> Unit
            }
            val reason = (next as? VpnConnectionState.Reconnecting)?.reason?.name?.let { "($it)" } ?: ""
            SecureLog.d(TAG, "state ${state.value.javaClass.simpleName} -> ${next.javaClass.simpleName}$reason")
            if (next !is VpnConnectionState.Connected && next !is VpnConnectionState.Reconnecting) {
                keepSelectionNotice = false
                _selectionError.value = null
            }
            _state.value = next
        }

        companion object {
            const val TAG = "ConnectionManager"

            internal const val PATH_STABLE_MS = 3_000L
            internal const val PATH_RETRY_INTERVAL_MS = 60_000L
            internal const val MAX_PATH_RETRIES = 5

            /** Consecutive engine failures retried before giving up to Error. */
            internal const val MAX_FAILURE_RECONNECTS = 5

            /** A session stable this long proves the failure was transient. */
            internal const val FAILURE_STABLE_MS = 60_000L

            /** Exponential backoff: 1s, 2s, 4s, 8s, 16s. */
            internal fun failureBackoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 4))

            /** Bound on waiting for a fresh engine's outbound groups before
             *  falling back to a reconnect. */
            const val GROUPS_WAIT_MS = 5_000L

            /** Bound on waiting for teardown to settle inside [reconnect]. */
            const val RECONNECT_SETTLE_MS = 10_000L
        }
    }
