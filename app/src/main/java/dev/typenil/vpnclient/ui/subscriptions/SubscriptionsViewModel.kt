package dev.typenil.vpnclient.ui.subscriptions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.HwidConsent
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.subscription.identificationErrorToken
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.ui.common.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A snackbar message waiting to be shown — held in state so it survives
 *  the destination not being composed when the failure lands. [id] lets the
 *  screen re-trigger its effect when one message replaces another. */
data class PendingMessage(
    val id: Long,
    val body: UserMessage,
)

/** State of the add dialog's request; failures stay inside the dialog. */
sealed interface AddStatus {
    data object Idle : AddStatus
    data object Working : AddStatus
    data class Failed(val failure: AddFailure) : AddStatus
    data object Succeeded : AddStatus
}

data class SubscriptionsUiState(
    val profiles: List<SubscriptionProfile> = emptyList(),
    /** subscriptionId → enabled node count (computed from observeEnabled). */
    val nodeCounts: Map<Long, Int> = emptyMap(),
    /** Manually imported nodes in display order — the manual row's detail
     *  sheet lists them so a bad paste can be undone. */
    val manualNodes: List<NodeEntity> = emptyList(),
    val refreshingIds: Set<Long> = emptySet(),
    val pendingMessage: PendingMessage? = null,
)

@HiltViewModel
class SubscriptionsViewModel
    @Inject
    constructor(
        private val repository: SubscriptionRepository,
        private val settings: SubscriptionSettings,
        nodeDao: NodeDao,
    ) : ViewModel() {
        private val pendingMessage = MutableStateFlow<PendingMessage?>(null)

        private val refreshing = MutableStateFlow<Set<Long>>(emptySet())

        private val _addStatus = MutableStateFlow<AddStatus>(AddStatus.Idle)
        val addStatus: StateFlow<AddStatus> = _addStatus
        private var addJob: Job? = null

        /** The dialog was closed or its result consumed; an in-flight add is
         *  cancelled (the repository rolls the unfinished row back). */
        fun clearAddStatus() {
            addJob?.cancel()
            addJob = null
            _addStatus.value = AddStatus.Idle
        }

        val uiState: StateFlow<SubscriptionsUiState> =
            combine(
                repository.profiles,
                nodeDao.observeEnabled(),
                refreshing,
                pendingMessage,
                repository.manualNodes,
            ) { profiles, nodes, refreshingIds, message, manualNodes ->
                SubscriptionsUiState(
                    profiles = profiles,
                    nodeCounts = profiles.associate { profile ->
                        profile.id to maxOf(profile.nodeCount, nodes.count { it.subscriptionId == profile.id })
                    },
                    manualNodes = manualNodes,
                    refreshingIds = refreshingIds,
                    pendingMessage = message,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SubscriptionsUiState(),
            )

        /** App-wide auto-refresh value — the detail sheet shows it so an
         *  inherited policy can display what it currently resolves to. */
        val globalAutoRefreshMinutes: StateFlow<Int> =
            settings.autoRefreshMinutes
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

        /** Queue a snackbar message; a newer failure replaces an unshown one.
         *  IDs come from a monotonic counter — a cleared message can't reuse a
         *  predecessor's id and lose its LaunchedEffect trigger. */
        private var nextMessageId = 0L

        private fun postMessage(
            @StringRes textRes: Int,
            fallback: String,
            args: List<Any> = emptyList(),
        ) {
            pendingMessage.update {
                PendingMessage(
                    id = ++nextMessageId,
                    body = UserMessage.Resource(textRes, args, fallback),
                )
            }
        }

        /** Raw-text variant for typed error messages produced upstream. */
        private fun postRawMessage(text: String) {
            pendingMessage.update {
                PendingMessage(id = ++nextMessageId, body = UserMessage.Raw(text))
            }
        }

        private fun postSubscriptionFailure(error: Throwable, @StringRes fallbackRes: Int, fallback: String, hasServers: Boolean = false) {
            val localized = (error as? SubscriptionError)?.identificationErrorToken()
                ?.let { subscriptionFailureMessage(it, hasServers) }
            if (localized != null) {
                pendingMessage.value = PendingMessage(++nextMessageId, localized)
                return
            }
            if (error is SubscriptionError.UnsupportedFormat && error.detail == "xray-json") {
                postMessage(
                    R.string.subs_xray_json_unsupported,
                    "Xray JSON subscriptions are not supported. In your panel, select sing-box, Clash, or base64/share links.",
                )
            } else {
                error.message?.let(::postRawMessage) ?: postMessage(fallbackRes, fallback)
            }
        }

        /** The screen consumed the message — clear it only if it's still the
         *  one that was displayed (a newer message may have replaced it). */
        fun acknowledgeMessage(id: Long) {
            pendingMessage.update { if (it?.id == id) null else it }
        }

        /** [skipped.size] skipped nodes, followed by the top reasons as
         *  "reason (n)" groups — reasons are provider-supplied raw text and
         *  stay untranslated; the wrapper sentence and the "+N more" tail
         *  come from string resources. */
        private fun skippedSummary(skipped: List<SkippedNode>) {
            val grouped = skipped.groupingBy { it.reason.take(MAX_REASON_LEN) }.eachCount()
            val shown =
                grouped.entries
                    .take(MAX_REASON_GROUPS)
                    .joinToString(", ") { (reason, count) -> "$reason ($count)" }
            val rest = grouped.size - MAX_REASON_GROUPS
            if (rest > 0) {
                postMessage(
                    R.string.subs_nodes_skipped_more,
                    "${skipped.size} nodes skipped: $shown, +$rest more",
                    listOf(skipped.size, shown, rest),
                )
            } else {
                postMessage(
                    R.string.subs_nodes_skipped,
                    "${skipped.size} nodes skipped: $shown",
                    listOf(skipped.size, shown),
                )
            }
        }

        private data class PendingImport(val url: String, val name: String?, val insecure: Boolean)
        private var pendingImport: PendingImport? = null
        private val _hwidPrompt = MutableStateFlow(false)
        val hwidPrompt: StateFlow<Boolean> = _hwidPrompt

        private fun dismissHwidPrompt() {
            pendingImport = null
            _hwidPrompt.value = false
        }

        /** Back / tap outside the consent dialog: the add is cancelled, no consent is
         *  written (stays Unset), and the user is told nothing was added. */
        fun cancelHwidPrompt() {
            if (pendingImport == null && !_hwidPrompt.value) return
            dismissHwidPrompt()
            _addStatus.value = AddStatus.Idle
            postMessage(R.string.subs_not_added, "Subscription not added")
        }

        fun chooseHwidConsent(allowed: Boolean) {
            // Working is set before the coroutine starts so a second tap on the
            // dialog's Add (or on a consent button) can't start a second add.
            if (pendingImport == null || _addStatus.value == AddStatus.Working) return
            _addStatus.value = AddStatus.Working
            addJob = viewModelScope.launch {
                val pending = pendingImport ?: return@launch
                dismissHwidPrompt()
                try {
                    settings.setHwidConsent(if (allowed) HwidConsent.Allowed else HwidConsent.Denied)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    pendingImport = pending
                    _hwidPrompt.value = true
                    _addStatus.value = AddStatus.Failed(AddFailure(AddFailureKind.Other))
                    return@launch
                }
                performAdd(pending.url, pending.name, pending.insecure)
            }
        }

        fun add(
            url: String,
            requestedName: String?,
            allowInsecureHttp: Boolean = false,
        ) {
            if (_addStatus.value == AddStatus.Working) return
            _addStatus.value = AddStatus.Working
            addJob = viewModelScope.launch {
                val candidate = ImportUrlExtractor.extract(null, url.trim(), null)
                val needsConsent = try {
                    candidate?.kind == ImportUrlExtractor.Kind.Subscription &&
                        settings.hwidConsent.first() == HwidConsent.Unset
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _addStatus.value = AddStatus.Failed(AddFailure(AddFailureKind.Other))
                    return@launch
                }
                if (needsConsent) {
                    pendingImport = PendingImport(url, requestedName, allowInsecureHttp)
                    _addStatus.value = AddStatus.Idle
                    _hwidPrompt.value = true
                } else {
                    performAdd(url, requestedName, allowInsecureHttp)
                }
            }
        }

        private suspend fun performAdd(url: String, requestedName: String?, allowInsecureHttp: Boolean) {
            val trimmed = url.trim()
            val candidate = ImportUrlExtractor.extract(null, trimmed, null)
            val result =
                if (candidate?.kind == ImportUrlExtractor.Kind.ShareLink) {
                    repository.importShareLink(candidate.url)
                } else {
                    repository.add(
                        candidate?.url ?: trimmed,
                        requestedName?.trim()?.ifEmpty { null } ?: candidate?.name,
                        allowInsecureHttp,
                    )
                }
            result
                .onSuccess { outcome ->
                    if (outcome.skipped.isNotEmpty()) {
                        skippedSummary(outcome.skipped)
                    }
                    _addStatus.value = AddStatus.Succeeded
                }.onFailure {
                    _addStatus.value = AddStatus.Failed(addFailureOf(it))
                }
        }

        fun refresh(id: Long) {
            if (id in refreshing.value) return
            refreshing.update { it + id }
            viewModelScope.launch {
                try {
                    repository
                        .refresh(id)
                        .onSuccess { outcome ->
                            if (outcome.skipped.isNotEmpty()) {
                                skippedSummary(outcome.skipped)
                            }
                        }.onFailure {
                            postSubscriptionFailure(it, R.string.subs_refresh_failed, "Refresh failed", (uiState.value.nodeCounts[id] ?: 0) > 0)
                        }
                } finally {
                    refreshing.update { it - id }
                }
            }
        }

        /** Refresh every remote subscription in order; local share links have no URL to fetch. */
        private var refreshAllJob: Job? = null

        fun refreshAll() {
            if (refreshAllJob?.isActive == true) return
            val ids = uiState.value.profiles
                .filterNot { SubscriptionRepository.isManualSubscription(it.url) }
                .map { it.id }
                .filterNot { it in refreshing.value }
            if (ids.isEmpty()) return

            // Reserve the whole batch immediately, preventing row taps or a second batch
            // from racing the same subscriptions while requests are queued.
            refreshing.update { it + ids }
            refreshAllJob = viewModelScope.launch {
                try {
                    for (id in ids) {
                        try {
                            repository.refresh(id)
                                .onSuccess { outcome ->
                                    if (outcome.skipped.isNotEmpty()) skippedSummary(outcome.skipped)
                                }
                                .onFailure {
                                    postSubscriptionFailure(
                                        it,
                                        R.string.subs_refresh_failed,
                                        "Refresh failed",
                                        (uiState.value.nodeCounts[id] ?: 0) > 0,
                                    )
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            postSubscriptionFailure(
                                e,
                                R.string.subs_refresh_failed,
                                "Refresh failed",
                                (uiState.value.nodeCounts[id] ?: 0) > 0,
                            )
                        }
                    }
                } finally {
                    refreshing.update { current -> current - ids.toSet() }
                    refreshAllJob = null
                }
            }
        }

        /**
         * Enable/disable — the repository flips the flag, reconciles the
         * periodic job, and clears the selection when the disabled subscription
         * owned the selected node. `refreshing` isn't touched: the toggle is
         * instant, not a fetch.
         */
        fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                runCatching { repository.setEnabled(id, enabled) }
                    .onFailure {
                        // A cancelled coroutine propagates — it must not
                        // surface a fake "failed" snackbar.
                        if (it is CancellationException) throw it
                        postMessage(R.string.subs_update_failed, "Failed to update subscription")
                    }
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                runCatching { repository.rename(id, name) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        postMessage(R.string.subs_rename_failed, "Rename failed")
                    }
            }
        }

        /**
         * Repoint the subscription URL — the repository fetches, parses, and
         * validates the candidate before committing anything, so a broken URL
         * can't clobber a working subscription (last-known-good). Runs under
         * `refreshing` so the UI shows it as in-flight.
         */
        fun editUrl(
            id: Long,
            newUrl: String,
        ) {
            viewModelScope.launch {
                refreshing.update { it + id }
                try {
                    repository
                        .editUrl(id, newUrl)
                        .onFailure {
                            postSubscriptionFailure(it, R.string.subs_url_update_failed, "URL update failed", (uiState.value.nodeCounts[id] ?: 0) > 0)
                        }
                } finally {
                    refreshing.update { it - id }
                }
            }
        }

        fun remove(id: Long) {
            viewModelScope.launch { repository.remove(id) }
        }

        /**
         * Persist a per-subscription auto-refresh policy. The repository
         * write+reschedule runs under the row's own lock; a failure surfaces
         * as a snackbar — the picker never looks like it saved when it
         * didn't.
         */
        fun setRefreshPolicy(
            id: Long,
            policy: RefreshPolicy,
        ) {
            viewModelScope.launch {
                runCatching { repository.setRefreshPolicy(id, policy) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        postMessage(R.string.subs_update_failed, "Failed to update subscription")
                    }
            }
        }

        /**
         * Delete one manually imported node — the only way to undo a bad paste,
         * since the manual row has no refresh to replace its nodes. The row
         * itself goes with its last node.
         */
        fun removeManualNode(nodeId: String) {
            viewModelScope.launch {
                runCatching { repository.removeManualNode(nodeId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        postMessage(R.string.subs_remove_node_failed, "Failed to remove server")
                    }
            }
        }

        private companion object {
            /** Provider-controlled tokens inside skip reasons are capped so a
             *  hostile subscription can't push a megabyte snackbar. */
            const val MAX_REASON_LEN = 48
            const val MAX_REASON_GROUPS = 4

        }
    }
