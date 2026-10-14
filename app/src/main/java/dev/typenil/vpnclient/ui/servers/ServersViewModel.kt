package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.common.LatencyProbe
import dev.typenil.vpnclient.core.engine.singbox.NodeTlsSummary
import dev.typenil.vpnclient.core.subscription.model.isEncrypted
import dev.typenil.vpnclient.core.subscription.model.isTunnelAllowed
import dev.typenil.vpnclient.data.toDomain
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import dev.typenil.vpnclient.data.NodeLatencyRepository
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** How far the active latency run has got: [done] of [total] nodes have a
 *  verdict. */
data class LatencyProgress(
    val done: Int,
    val total: Int,
)

/** Nodes of one subscription, under its display name. [subscriptionName] is
 *  null when the profile row is gone — the screen renders the localized
 *  "Subscription N" fallback so the string can be translated. */
data class ServerGroup(
    val subscriptionId: Long,
    val subscriptionName: String?,
    val nodes: List<ServerNode>,
)

/** A selectable subscription filter — id + raw profile name (possibly
 *  blank; the screen applies the localized fallback). */
data class SubscriptionFilterOption(
    val id: Long,
    val name: String,
)

enum class ServerSortMode { Default, Latency, Name }

/** Resolved per-node user-facing state — the node row plus its persisted
 *  preferences (missing row = all defaults). [customName]/[isHidden] are
 *  consumed for display + management; [isEnabled] gates VPN membership. */
data class ServerNode(
    val entity: NodeEntity,
    val favorite: Boolean,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val customName: String? = null,
    /** Read-only TLS posture of the stored outbound — computed at the
     *  engine-boundary layer so the UI never parses outbound JSON. */
    val tls: NodeTlsSummary = NodeTlsSummary.fromOutboundJson(entity.outboundJson),
    val encrypted: Boolean = isEncrypted(entity.toDomain()),
    val tunnelAllowed: Boolean = isTunnelAllowed(entity.toDomain()),
)

val ServerNode.id: String get() = entity.id

/** Display name — the local override wins over the provider label. */
val ServerNode.name: String get() = customName?.takeIf { it.isNotBlank() } ?: entity.name
val ServerNode.providerName: String get() = entity.name
val ServerNode.protocol: String get() = entity.protocol
val ServerNode.server: String get() = entity.server
val ServerNode.subscriptionId: Long get() = entity.subscriptionId

data class ServersUiState(
    val groups: List<ServerGroup> = emptyList(),
    /** Show only starred nodes — chip-driven filter, orthogonal to query. */
    val favoritesOnly: Boolean = false,
    val selectedNodeId: String? = null,
    /** The persisted pick is the "Auto / Fastest" urltest group, not a node. */
    val autoSelected: Boolean = false,
    /** Outbound tag → last measured delay; feeds the latency badges. */
    val delays: Map<String, Int> = emptyMap(),
    /** Tags a latency run has covered — distinguishes "timeout" from
     *  "never tested" on the badge. */
    val testedNodeIds: Set<String> = emptySet(),
    /** Last persisted verdict per node (method + time) — history, not a live
     *  reading: the screen shows it with its age and method, and an outdated
     *  one never counts as fresh for ordering ([NodeLatency.isFresh]). */
    val storedLatency: Map<String, NodeLatency> = emptyMap(),
    /** Connected → badges come from the engine's urltest (through the
     *  proxy); disconnected → direct TCP-connect probe. Names the
     *  measurement so the UI doesn't imply one means the other. */
    val connected: Boolean = false,
    // ---- search / sort / filters ----
    val query: String = "",
    val sortMode: ServerSortMode = ServerSortMode.Default,
    val subscriptionFilter: Long? = null,
    val protocolFilter: String? = null,
    /** Every enabled subscription with nodes — drives the sub filter row. */
    val subscriptionOptions: List<SubscriptionFilterOption> = emptyList(),
    /** Protocols present in the (search-filtered) node set — drives the
     *  protocol filter row. ProtocolType.name values, not labels. */
    val protocolOptions: List<String> = emptyList(),
    /** Reveal hidden nodes for management — default off. */
    val showHidden: Boolean = false,
    /** False when any filter/search is active — the screen keeps the flat
     *  "filtered" look instead of per-subscription headers. */
    val showHeaders: Boolean = true,
    /** True when the raw node set is empty (no subscriptions) — the screen
     *  shows the "add a subscription" prompt; false when filters merely
     *  excluded everything. */
    val noSubscriptions: Boolean = true,
)

/** Engine-reported surface: connection state + per-outbound delays and the
 *  covered tags marked by runs of the ATTACHED engine. [urlTested] is
 *  session-scoped — a previous engine's verdicts must not mark a fresh
 *  session's unmeasured tags (that would render as a fake timeout). */
private data class EngineSurface(
    val connected: Boolean,
    val delays: Map<String, Int>,
    val urlTested: Set<String>,
)

/** Direct-probe surface: per-node results + the tags a run has covered,
 *  plus retained urltest outcomes kept in their own keys so a proxy-path
 *  measurement never masquerades as a TCP-connect one. [urlTested] tracks
 *  engine-run terminal verdicts; [urlTestDelays] preserves their delay
 *  values across a disconnect that empties the live group surface. Both
 *  are RETAINED state for the disconnected surface — while connected the
 *  badges read [UrlTestSession], scoped to the attached engine. */
internal data class ProbeSurface(
    val delays: Map<String, Int>,
    val tested: Set<String>,
    val urlTested: Set<String> = emptySet(),
    val urlTestDelays: Map<String, Int> = emptyMap(),
)

/** The raw inputs filtering/sorting operate on — bundled so the filter
 *  combine stays under the 5-flow arity limit. */
private data class NodeSurface(
    val nodes: List<ServerNode>,
    val subscriptionNames: Map<Long, String>,
    val stored: Map<String, NodeLatency>,
)

/** User-controlled list transforms — each is a Flow input to combine. */
private data class ListControls(
    val query: String,
    val sortMode: ServerSortMode,
    val subscriptionFilter: Long?,
    val protocolFilter: String?,
    val favoritesOnly: Boolean,
    /** Reveal hidden nodes for management — they stay dimmed/managed, not
     *  selectable. Default false: hidden means "leave the picker". */
    val showHidden: Boolean,
)

@HiltViewModel
class ServersViewModel
    @Inject
    constructor(
        private val settings: SubscriptionSettings,
        private val connectionManager: ConnectionManager,
        private val nodeDao: NodeDao,
        private val nodePreferenceDao: NodePreferenceDao,
        private val latencyProbe: LatencyProbe,
        private val subscriptions: SubscriptionRepository,
        private val latencyRepository: NodeLatencyRepository,
    ) : ViewModel() {
        /** Direct TCP probe results — populated when testing while disconnected.
         *  Internal so teardown-commit tests can observe the cancelled run's
         *  retained verdicts after the UI surface is dead. */
        internal val probeSurface = MutableStateFlow(ProbeSurface(emptyMap(), emptySet()))

        // Search text — a substring match over name/server, case-insensitive.
        private val query = MutableStateFlow("")
        private val sortMode = MutableStateFlow(ServerSortMode.Default)

        /** Null = all enabled subscriptions. */
        private val subscriptionFilter = MutableStateFlow<Long?>(null)

        /** ProtocolType.name value; null = all protocols. */
        private val protocolFilter = MutableStateFlow<String?>(null)

        /** True = restrict the list to starred nodes. */
        private val favoritesOnly = MutableStateFlow(false)

        /** Reveal hidden nodes so the user can Unhide them — off by default
         *  since "hidden" is presentation, not disablement. */
        private val showHidden = MutableStateFlow(false)

        private val controls: StateFlow<ListControls> =
            combine(
                query,
                sortMode,
                subscriptionFilter,
                protocolFilter,
                favoritesOnly,
                showHidden,
            ) { values ->
                ListControls(
                    query = values[0] as String,
                    sortMode = values[1] as ServerSortMode,
                    subscriptionFilter = values[2] as Long?,
                    protocolFilter = values[3] as String?,
                    favoritesOnly = values[4] as Boolean,
                    showHidden = values[5] as Boolean,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    ListControls(
                        "",
                        ServerSortMode.Default,
                        null,
                        null,
                        false,
                        false,
                    ),
            )

        /** Connected-run state valid only for the engine attach it was
         *  captured under ([epoch] mirrors [ConnectionManager.engineEpoch]):
         *  per-tag urlTestTime floors — a covered tag's engine delay may only
         *  display once its urlTestTime is strictly newer — plus the tags this
         *  session's runs have marked tested. A reconnect or in-session
         *  rebuild attaches a fresh engine whose measurement history starts
         *  empty; state stamped with an older epoch is dead, never merged. */
        private data class UrlTestSession(
            val epoch: Long,
            val floors: Map<String, Long> = emptyMap(),
            val tested: Set<String> = emptySet(),
        )

        private val urlTestSession = MutableStateFlow(UrlTestSession(epoch = -1L))

        private val engineSurface: StateFlow<EngineSurface> =
            combine(
                connectionManager.state,
                connectionManager.groups,
                connectionManager.engineEpoch,
                urlTestSession,
            ) { state, groups, epoch, session ->
                val current = session.epoch == epoch
                EngineSurface(
                    connected = state is VpnConnectionState.Connected,
                    // Connected badges reflect urltest-group members only — a
                    // selector group's own items are not urltest coverage and
                    // their delay values must not surface as measurements.
                    delays =
                        groups
                            .asSequence()
                            .filter { it.type == URLTEST_GROUP_TYPE }
                            .flatMap { it.items.asSequence() }
                            .mapNotNull { item ->
                                val delay = item.urlTestDelayMs
                                val floor = if (current) session.floors[item.tag] else null
                                if (delay != null && delay > 0 &&
                                    (floor == null || item.urlTestTime > floor)
                                ) {
                                    item.tag to delay
                                } else {
                                    null
                                }
                            }.toMap(),
                    urlTested = if (current) session.tested else emptySet(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EngineSurface(connected = false, delays = emptyMap(), urlTested = emptySet()),
            )

        private val nodeSurface: StateFlow<NodeSurface> =
            combine(
                nodeDao.observeEnabled(),
                nodePreferenceDao.observeAll(),
                subscriptions.profiles,
                latencyRepository.latencies,
            ) { nodes, prefs, profiles, stored ->
                val prefById = prefs.associateBy { it.nodeId }
                NodeSurface(
                    nodes =
                        nodes.map { entity ->
                            val pref = prefById[entity.id]
                            ServerNode(
                                entity = entity,
                                favorite = pref?.isFavorite == true,
                                enabled = pref?.isEnabled ?: true,
                                hidden = pref?.isHidden == true,
                                customName = pref?.customName,
                            )
                        },
                    subscriptionNames = profiles.associate { it.id to it.name },
                    stored = stored,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NodeSurface(emptyList(), emptyMap(), emptyMap()),
            )

        val uiState: StateFlow<ServersUiState> =
            combine(
                nodeSurface,
                settings.selectedNodeId,
                engineSurface,
                probeSurface,
                controls,
            ) { surface, selectedId, engine, probe, ctl ->
                val names = surface.subscriptionNames
                // Connected: only live engine urltest numbers — a stale
                // direct-probe value must not pose as a "via proxy" reading.
                // Disconnected: TCP results plus retained urltest verdicts
                // (kept in their own keys — disjoint by construction).
                val delays =
                    if (engine.connected) {
                        engine.delays
                    } else {
                        probe.delays + probe.urlTestDelays
                    }
                val testedIds =
                    if (engine.connected) {
                        // Session-scoped marks only — retained verdicts from
                        // a dead engine would read as fake timeouts here.
                        engine.urlTested
                    } else {
                        probe.tested + probe.urlTested
                    }

                val trimmedQuery = ctl.query.trim()
                val searching = trimmedQuery.isNotEmpty()
                // Hidden nodes leave every picker unless the user asked to
                // manage them — the flag is presentation-only, the engine
                // still sees the node as usable.
                val filtered =
                    surface.nodes
                        .asSequence()
                        .filter { node -> ctl.showHidden || !node.hidden }
                        .filter { node ->
                            ctl.subscriptionFilter == null || node.subscriptionId == ctl.subscriptionFilter
                        }.filter { node ->
                            !ctl.favoritesOnly || node.favorite
                        }.filter { node ->
                            !searching ||
                                node.name.contains(trimmedQuery, ignoreCase = true) ||
                                node.server.contains(trimmedQuery, ignoreCase = true)
                        }.toList()

                // Protocol options come from the (sub-filtered) set so a stale
                // selection can't silently hide everything — the chip for the
                // active protocol always exists while it still has nodes.
                val protocolOptions =
                    filtered
                        .map { it.protocol }
                        .distinct()
                        .sortedBy { protocolLabel(it) }
                val effectiveProtocol =
                    if (ctl.protocolFilter != null && ctl.protocolFilter in protocolOptions) {
                        ctl.protocolFilter
                    } else {
                        null
                    }
                val afterProtocol =
                    filtered.filter {
                        effectiveProtocol == null || it.protocol == effectiveProtocol
                    }

                val sorted =
                    when (ctl.sortMode) {
                        // DB order: subscriptionId, position — already the "default".
                        ServerSortMode.Default -> {
                            afterProtocol
                        }

                        // Live value first; a node with a live verdict but no
                        // delay (timeout) sorts last. Only nodes with no live
                        // verdict fall back to the stored one — and only while
                        // it is fresh: outdated history never ranks.
                        ServerSortMode.Latency -> {
                            val now = latencyRepository.now()
                            afterProtocol.sortedBy { node ->
                                delays[node.id]
                                    ?: if (node.id in testedIds) {
                                        null
                                    } else {
                                        surface.stored[node.id]?.takeIf { it.isFresh(now) }?.latencyMs
                                    }
                                    ?: Int.MAX_VALUE
                            }
                        }

                        ServerSortMode.Name -> {
                            afterProtocol.sortedBy { it.name.lowercase() }
                        }
                    }

                // Group headers only make sense in the default unfiltered view —
                // searching, a single-subscription filter, or a non-default sort
                // present one flat result list instead.
                val flat =
                    searching || ctl.subscriptionFilter != null ||
                        ctl.favoritesOnly ||
                        ctl.sortMode != ServerSortMode.Default
                val groups =
                    if (flat) {
                        listOfNotNull(
                            if (sorted.isNotEmpty()) {
                                ServerGroup(
                                    subscriptionId = -1L,
                                    subscriptionName = "",
                                    nodes = sorted,
                                )
                            } else {
                                null
                            },
                        )
                    } else {
                        sorted
                            .groupBy { it.subscriptionId }
                            .map { (subId, groupNodes) ->
                                ServerGroup(
                                    subscriptionId = subId,
                                    // Raw profile name — null when the profile
                                    // row is missing; the screen renders the
                                    // localized fallback so it can translate.
                                    subscriptionName = names[subId],
                                    nodes = groupNodes,
                                )
                            }
                    }

                ServersUiState(
                    groups = groups,
                    selectedNodeId = selectedId,
                    autoSelected = selectedId == NodeSelection.AUTO_ID || selectedId == NodeSelection.AUTO_RUSSIA_ID,
                    delays = delays,
                    testedNodeIds = testedIds,
                    storedLatency = surface.stored,
                    connected = engine.connected,
                    query = ctl.query,
                    sortMode = ctl.sortMode,
                    subscriptionFilter = ctl.subscriptionFilter,
                    protocolFilter = effectiveProtocol,
                    favoritesOnly = ctl.favoritesOnly,
                    showHidden = ctl.showHidden,
                    subscriptionOptions =
                        surface.nodes
                            .map { it.subscriptionId }
                            .distinct()
                            .map { subId ->
                                SubscriptionFilterOption(
                                    id = subId,
                                    name = names[subId].orEmpty(),
                                )
                            },
                    protocolOptions = protocolOptions,
                    showHeaders = !flat,
                    noSubscriptions = surface.nodes.isEmpty(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ServersUiState(),
            )

        fun setQuery(value: String) {
            query.value = value
        }

        fun setSortMode(mode: ServerSortMode) {
            sortMode.value = mode
        }

        /** Toggle semantics: tapping the active chip clears the filter. */
        fun setSubscriptionFilter(id: Long?) {
            subscriptionFilter.value = id
        }

        fun setProtocolFilter(protocol: String?) {
            protocolFilter.value = protocol
        }

        /** Toggle semantics: tapping the active chip clears the filter. */
        fun setFavoritesOnly(enabled: Boolean) {
            favoritesOnly.value = enabled
        }

        fun setShowHidden(enabled: Boolean) {
            showHidden.value = enabled
        }

        /**
         * Star/unstar a node — writes only the prefs row; the node row itself
         * is subscription-owned and rewritten on every refresh. Routed
         * through the repository so the write is serialized against a
         * refresh commit.
         */
        fun toggleFavorite(nodeId: String) {
            viewModelScope.launch {
                val current = nodePreferenceDao.get(nodeId)?.isFavorite == true
                subscriptions.setNodeFavorite(nodeId, !current)
            }
        }

        /** Enable/disable VPN membership — disabling the selected node falls
         *  the selection back inside the repository (conditional, so a
         *  concurrent user pick isn't wiped). */
        fun setNodeEnabled(
            nodeId: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch { subscriptions.setNodeEnabled(nodeId, enabled) }
        }

        /** Presentation-only hide — the node stays usable for routing but
         *  leaves user lists (favorites/search). */
        fun setNodeHidden(
            nodeId: String,
            hidden: Boolean,
        ) {
            viewModelScope.launch { subscriptions.setNodeHidden(nodeId, hidden) }
        }

        /** Local display name — blank clears back to the provider name. */
        fun setNodeCustomName(
            nodeId: String,
            customName: String?,
        ) {
            viewModelScope.launch {
                subscriptions.setNodeCustomName(nodeId, customName?.takeIf { it.isNotBlank() })
            }
        }

        /**
         * Persist a node pick — ConnectionManager reconciles the live engine
         * with it (live selector switch, or a reconnect when the engine can't
         * apply it). Nothing else to do here.
         */
        fun selectNode(nodeId: String) {
            viewModelScope.launch {
                // A disabled node can't be picked — the compiled set won't
                // carry it, so the reconcile would chase a ghost selection.
                val usable = nodeDao.getUsable().map { it.id }.toSet()
                if (nodeId in usable) {
                    settings.setSelectedNodeId(nodeId)
                }
            }
        }

        /** Persist the "Auto / Fastest" pick — stored as the [NodeSelection.AUTO_ID]
         *  sentinel; the compiler/live reconciler route it to the urltest group. */
        fun selectAuto() {
            viewModelScope.launch { settings.setSelectedNodeId(NodeSelection.AUTO_ID) }
        }

        /** Persist the Russia-only automatic mode. */
        fun selectAutoRussia() {
            viewModelScope.launch { settings.setSelectedNodeId(NodeSelection.AUTO_RUSSIA_ID) }
        }

        /**
         * Latency probe over every enabled node. Connected: the engine's
         * urltest measures each node through its own outbound over the real
         * underlay (our sockets never enter the TUN) — the run covers
         * `urltest` group members only and waits, bounded, for fresh terminal
         * results. Disconnected: a direct TCP-connect probe per node — same
         * underlay, without a running core. Verdicts are persisted with their
         * method and time. A second press while a run is active is ignored —
         * no parallel runs; [cancelLatencyTest] stops the active one.
         */
        fun testLatency() {
            // Claim the run synchronously — two presses before the launched
            // coroutine is scheduled must not enqueue parallel runs.
            if (!_testing.compareAndSet(false, true)) return
            testJob =
                viewModelScope.launch {
                    try {
                        if (connectionManager.state.value is VpnConnectionState.Connected) {
                            runUrlTest()
                        } else {
                            runTcpTest()
                        }
                    } finally {
                        _progress.value = null
                        _testing.value = false
                    }
                }
        }

        /** Stop the active run. Probes in flight are cancelled; nodes the run
         *  had not reached keep their previous value (stored verdict) — they
         *  do not turn into timeouts. Verdicts that already arrived are kept. */
        fun cancelLatencyTest() {
            testJob?.cancel()
        }

        /** The screen went away: a direct-probe run has no one to show its
         *  progress to. A connected run is the engine's own urltest and is
         *  left to finish. */
        fun onScreenLeft() {
            if (connectionManager.state.value !is VpnConnectionState.Connected) cancelLatencyTest()
        }

        private var testJob: Job? = null

        private suspend fun runTcpTest() {
            val nodes = nodeDao.getEnabled()
            val ids = nodes.mapTo(HashSet()) { it.id }
            _progress.value = LatencyProgress(0, nodes.size)
            // The run invalidates what it covers on the session surface; a
            // node it never reaches (cancel) falls back to its stored verdict
            // instead of a stale in-memory one or a fake timeout.
            probeSurface.update {
                it.copy(
                    delays = it.delays - ids,
                    tested = it.tested - ids,
                    urlTested = it.urlTested - ids,
                    urlTestDelays = it.urlTestDelays - ids,
                )
            }
            val measured = mutableMapOf<String, Int?>()
            try {
                coroutineScope {
                    nodes.forEach { node ->
                        launch {
                            val delay = latencyProbe.measure(node.server, node.port)
                            // A connect that landed mid-probe must not
                            // publish a direct result into the
                            // connected-mode surface.
                            if (connectionManager.state.value is VpnConnectionState.Connected) {
                                return@launch
                            }
                            measured[node.id] = delay
                            _progress.value = LatencyProgress(measured.size, nodes.size)
                            probeSurface.update { surface ->
                                surface.copy(
                                    delays =
                                        if (delay != null) {
                                            surface.delays + (node.id to delay)
                                        } else {
                                            surface.delays - node.id
                                        },
                                    tested = surface.tested + node.id,
                                    // A fresh direct measurement
                                    // supersedes a retained urltest
                                    // verdict for this tag.
                                    urlTested = surface.urlTested - node.id,
                                    urlTestDelays = surface.urlTestDelays - node.id,
                                )
                            }
                        }
                    }
                }
            } finally {
                persistLatency(measured.toMap(), LatencyMethod.Tcp)
            }
        }

        /** Persist even while the run is being cancelled — what already
         *  arrived is a real verdict. A storage failure must not take the
         *  screen down; the session surface still holds the value. */
        private suspend fun persistLatency(
            results: Map<String, Int?>,
            method: LatencyMethod,
        ) {
            if (results.isEmpty()) return
            withContext(NonCancellable) {
                try {
                    latencyRepository.record(results, method)
                } catch (e: Exception) {
                    SecureLog.w(TAG, "latency verdicts not persisted", e)
                }
            }
        }

        /**
         * One bounded urlTest run over the engine's `urltest` groups.
         *
         * Freshness uses per-tag [OutboundItemInfo.urlTestTime] baselines
         * captured *before* dispatch — never an app clock (libbox reports
         * whole seconds). Terminal per covered tag:
         * - success — `urlTestDelayMs > 0 && urlTestTime > baseline`;
         * - failure — `baseline > 0 && urlTestTime == 0` (the core cleared a
         *   recorded result = probe failed);
         * - `baseline == 0 && urlTestTime == 0` stays ambiguous (never
         *   measured vs failed-without-mark) and rides out the bound.
         *
         * Cancellation — any non-Connected state, status-channel suppression
         * (screen off), or ViewModel teardown — ends the wait immediately:
         * covered tags with no recorded terminal result revert to untested
         * ("—"), never "timeout"; results that did arrive are kept. A bound
         * hit while still Connected marks only the still-pending tags
         * "timeout".
         */
        private suspend fun runUrlTest() {
            val snapshot = connectionManager.groups.value
            // tag → urlTestTime baseline; only `urltest` group members are
            // covered — selector groups and non-member nodes stay "—".
            val baseline: Map<String, Long> =
                snapshot.asSequence()
                    .filter { it.type == URLTEST_GROUP_TYPE }
                    .flatMap { it.items.asSequence() }
                    .associate { it.tag to it.urlTestTime }
            if (baseline.isEmpty()) return
            _progress.value = LatencyProgress(0, baseline.size)

            // The engine attach this run belongs to — floors and verdict
            // marks are scoped to its measurement history; a mid-run
            // reconnect must not let this run write into the next session.
            val epoch = connectionManager.engineEpoch.value

            // Recorded per emission — a disconnect empties the group surface,
            // so terminal results must be committed as they arrive or a
            // cancelled run would lose them (and report fake timeouts).
            val succeeded = mutableMapOf<String, Int>()
            val failed = mutableSetOf<String>()

            fun observe(groups: List<OutboundGroupInfo>) {
                for (group in groups) {
                    if (group.type != URLTEST_GROUP_TYPE) continue
                    for (item in group.items) {
                        val tag = item.tag
                        val base = baseline[tag] ?: continue
                        if (tag in succeeded || tag in failed) continue
                        val delay = item.urlTestDelayMs
                        when {
                            delay != null && delay > 0 && item.urlTestTime > base ->
                                succeeded[tag] = delay

                            base > 0L && item.urlTestTime == 0L -> failed += tag
                            // base == 0 && time == 0 stays pending —
                            // never-measured and failed-silently are
                            // indistinguishable at second granularity.
                        }
                    }
                }
                _progress.value = LatencyProgress(succeeded.size + failed.size, baseline.size)
            }

            // Stale values and verdicts for covered tags are invalid the
            // moment a proxy-path run covers them — clear now; terminal
            // outcomes re-mark their own tags at commit. The session floor
            // keeps the connected surface honest the same way: a pre-run
            // engine delay must not render as this run's result. A re-run
            // inside one session keeps marks for tags it doesn't cover.
            urlTestSession.update { session ->
                UrlTestSession(
                    epoch = epoch,
                    floors = baseline,
                    tested =
                        if (session.epoch == epoch) {
                            session.tested - baseline.keys
                        } else {
                            emptySet()
                        },
                )
            }
            probeSurface.update {
                it.copy(
                    delays = it.delays - baseline.keys,
                    tested = it.tested - baseline.keys,
                    urlTested = it.urlTested - baseline.keys,
                    urlTestDelays = it.urlTestDelays - baseline.keys,
                )
            }

            var cancelled = false
            try {
                // The bound covers dispatch + wait. `subscribed` is a real
                // readiness barrier — dispatch only starts after the watcher
                // has consumed its first combined frame, so a synchronous
                // result push (or a groups-clearing disconnect) inside the
                // first urlTest call is still observed. Dispatch is a
                // separate child: when the watcher lands terminal/cancel the
                // child is cancelled immediately — a hanging dispatch call
                // can't stretch the run to the bound.
                val frame =
                    withTimeoutOrNull(URLTEST_RUN_TIMEOUT_MS) {
                        coroutineScope {
                            val subscribed = CompletableDeferred<Unit>()
                            val watcher =
                                async {
                                    combine(
                                        connectionManager.state,
                                        connectionManager.statusUpdatesEnabled,
                                        connectionManager.groups,
                                    ) { state, updatesEnabled, groups ->
                                        Triple(state, updatesEnabled, groups)
                                    }.onEach {
                                        observe(it.third)
                                        subscribed.complete(Unit)
                                    }.first { (state, updatesEnabled, _) ->
                                        state !is VpnConnectionState.Connected ||
                                            !updatesEnabled ||
                                            succeeded.size + failed.size == baseline.size
                                    }
                                }
                            val dispatch =
                                async {
                                    subscribed.await()
                                    for (group in snapshot) {
                                        if (group.type != URLTEST_GROUP_TYPE) continue
                                        connectionManager.urlTest(group.tag)
                                    }
                                }
                            val f = watcher.await()
                            // Verdict decided — any in-flight dispatch is
                            // pointless now; cancel it rather than waiting.
                            dispatch.cancel()
                            f
                        }
                    }
                // Cancel semantics latch from the terminating frame — a fast
                // reconnect must not flip an observed cancel back into a
                // "timeout". The live reread can only ADD cancellation (a
                // trigger racing the bound), never remove a latched one.
                cancelled =
                    (frame != null &&
                        (frame.first !is VpnConnectionState.Connected || !frame.second)) ||
                        connectionManager.state.value !is VpnConnectionState.Connected ||
                        !connectionManager.statusUpdatesEnabled.value
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                // Non-suspending commits — run even while the scope dies.
                // Session marks only land if this run's engine is still the
                // attached one; a newer attach makes the whole bucket dead.
                urlTestSession.update { session ->
                    if (session.epoch == epoch) {
                        var tested = session.tested
                        for (tag in baseline.keys) {
                            if (tag in succeeded || tag in failed || !cancelled) {
                                tested += tag
                            } else {
                                tested -= tag
                            }
                        }
                        session.copy(tested = tested)
                    } else {
                        session
                    }
                }
                // Terminal outcomes land on the retained urltest surface
                // (kept across a disconnect that empties live groups); a
                // cancelled run's unmeasured tags revert to untested — "—",
                // never a fake "timeout".
                probeSurface.update { surface ->
                    var urlTested = surface.urlTested
                    var urlTestDelays = surface.urlTestDelays
                    for (tag in baseline.keys) {
                        when {
                            tag in succeeded -> {
                                urlTestDelays = urlTestDelays + (tag to succeeded.getValue(tag))
                                urlTested = urlTested + tag
                            }
                            tag in failed || !cancelled -> {
                                urlTested = urlTested + tag
                            }
                            else -> {
                                urlTested = urlTested - tag
                                urlTestDelays = urlTestDelays - tag
                            }
                        }
                    }
                    surface.copy(urlTested = urlTested, urlTestDelays = urlTestDelays)
                }
                persistLatency(
                    buildMap<String, Int?> {
                        for (tag in baseline.keys) {
                            when {
                                tag in succeeded -> put(tag, succeeded.getValue(tag))
                                // Terminal failure or bound hit while still
                                // connected = a real verdict; a cancelled
                                // run's unreached tags keep their old value.
                                tag in failed || !cancelled -> put(tag, null)
                            }
                        }
                    },
                    LatencyMethod.Proxy,
                )
            }
        }

        private val _testing = MutableStateFlow(false)

        /** True while a latency probe is in flight — drives the button spinner. */
        val testing: StateFlow<Boolean> = _testing

        private val _progress = MutableStateFlow<LatencyProgress?>(null)

        /** done/total of the active run — null while idle. */
        val progress: StateFlow<LatencyProgress?> = _progress

        companion object {
            /** sing-box outbound group type that performs urltest measurement. */
            internal const val URLTEST_GROUP_TYPE = "urltest"

            private const val TAG = "ServersVM"

            /** Bound on dispatch + wait for one urlTest run — the native probe
             *  horizon. Nodes still non-terminal at the bound are the only
             *  ones that get marked "timeout". */
            internal const val URLTEST_RUN_TIMEOUT_MS = 15_000L
        }
    }

/** Display label for a stored protocol tag — raw value as fallback. */
internal fun protocolLabel(protocol: String): String = runCatching { ProtocolType.valueOf(protocol) }.getOrNull()?.label ?: protocol
