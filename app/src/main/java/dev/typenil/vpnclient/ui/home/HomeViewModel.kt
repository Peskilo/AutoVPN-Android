package dev.typenil.vpnclient.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.DnsProfile
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.freshDelayMs
import dev.typenil.vpnclient.core.engine.pickBestLatency
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.isEncrypted
import dev.typenil.vpnclient.core.subscription.model.isTunnelAllowed
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.data.toDomain
import dev.typenil.vpnclient.core.vpn.AppliedSessionConfig
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.UnderlyingTransport
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.NodeLatencyRepository
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    /** The real connection state machine — rendered as-is, never invented. */
    val connection: VpnConnectionState = VpnConnectionState.Idle,
    val selectedNodeName: String? = null,
    val selectedNodeProtocol: String? = null,
    /** The persisted pick is Auto — no concrete node exists. */
    val autoSelected: Boolean = false,
    /** Best measured latency node name from urltest (if tested). Does NOT claim
     *  to be the guaranteed active egress when tolerance is in effect. */
    val bestLatencyNodeName: String? = null,
    val bestLatencyNodeProtocol: String? = null,
    /** Measured delay for the selected node, or Auto's best measured candidate. */
    val selectedDelayMs: Int? = null,
    /** Minutes since a REAL measurement that aged out of the fresh window; null when none exists or it is fresh. */
    val selectedDelayStaleMinutes: Int? = null,
    /** Live-path hint from fresh probe evidence; None outside Connected. */
    val healthHint: HomeHealthHint = HomeHealthHint.None,
    val subscriptionName: String? = null,
    val routingMode: RouteMode? = RouteMode.ALL,
    val routingPending: Boolean = false,
    /** Restart guard disabled auto-start — persistent warning, survives a
     *  denied notification permission (the alert notification may never
     *  have been seen). */
    val restartGuardTripped: Boolean = false,
    /** Real session details for the Connected bottom sheet — every field
     *  comes from a live source (engine groups, DataStore, the service's
     *  underlay tracker); null means "not reported", never a guess. */
    val sessionDetails: SessionDetails? = null,
    /** No node rows exist at all — the server row should offer the add
     *  prompt instead of "No server selected". */
    val noNodesAtAll: Boolean = true,
    /** Enabled subscription sources, not nodes or live VPN connections. */
    val enabledSubscriptionCount: Int = 0,
    /** Pickable entries for the server sheet: Auto first, then every
     *  enabled node. Empty when there are no nodes — the card then shows
     *  the add prompt instead of a picker. */
    val serverOptions: List<ServerOption> = emptyList(),
    /** The persisted pick — used to mark the current option in the sheet. */
    val selectedOptionId: String? = null,
    val selectionError: VpnError? = null,
)

/** A rejected desired pick must not relabel the still-running server card. */
internal fun HomeUiState.withAppliedServerOnRejection(): HomeUiState {
    if (selectionError == null) return this
    val node = when (val state = connection) {
        is VpnConnectionState.Connected -> state.node
        is VpnConnectionState.Reconnecting -> state.node
        else -> return this
    }
    return copy(selectedNodeName = node.name, selectedNodeProtocol = node.protocol.label,
        autoSelected = node.id == NodeSelection.AUTO_ID, selectedDelayMs = null,
        selectedDelayStaleMinutes = null)
}

/** One row in the Home server picker. [id] is the raw `selected_node_id`
 *  value to persist — [NodeSelection.AUTO_ID] for the Auto row. [searchText]
 *  carries the fields the picker's search matches (name + host) without
 *  widening what the row renders. */
data class ServerOption(
    val id: String,
    /** Literal node name, or null when [titleRes] carries the label (Auto). */
    val title: String? = null,
    /** Label resource — non-null only for entries without a real node name. */
    @param:StringRes val titleRes: Int? = null,
    val subtitle: String? = null,
    /** Subtitle resource — non-null only for the localized Auto subtitle. */
    @param:StringRes val subtitleRes: Int? = null,
    /** Host + name text the search matches; falls back to the Auto tag. */
    val searchText: String? = title,
    val encrypted: Boolean = true,
    val tunnelAllowed: Boolean = true,
)

/** Sheet payload — assembled per state emission so it stays in lockstep
 *  with the connection state machine. */
data class SessionDetails(
    /** Selector group's current pick, resolved to the display label —
     *  the group's `selected` is a raw outbound tag (node id hash or
     *  "auto"), which the UI must not render. */
    val activeOutbound: String?,
    /** Resource for [activeOutbound] when the tag is the Auto sentinel —
     *  resolves to the localized Auto label at render. */
    @param:StringRes val activeOutboundRes: Int? = null,
    /** The routing/per-app plan the live engine actually runs — null until
     *  the service reports one; never guessed from the current settings. */
    val applied: AppliedSessionConfig?,
    /** Settings that changed but aren't applied to the live session yet —
     *  string resources, resolved at render so the locale switch applies. */
    val pendingReconnect: List<Int>,
    /** Physical underlay label reported by the service's network tracker. */
    val underlay: UnderlyingTransport,
    /** Message of the most recent Error state this process observed —
     *  shown as history context, never presented as live state. */
    val lastError: String?,
    @param:StringRes val lastErrorRes: Int? = null,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        nodeDao: NodeDao,
        nodePreferenceDao: NodePreferenceDao,
        private val settings: SettingsRepository,
        subscriptions: SubscriptionRepository,
        private val latencyRepository: NodeLatencyRepository,
        private val batteryStatus: dev.typenil.vpnclient.ui.common.BatteryOptimizationStatus,
    ) : ViewModel() {
        /** The last terminal error the state machine published this process —
         *  the cheapest honest source for the sheet's "last error" row. */
        private val lastErrorMessage = MutableStateFlow<VpnError?>(null)
        private val batteryHint = dev.typenil.vpnclient.ui.common.BatteryHintModel(
            viewModelScope, connectionManager.state, settings.batteryHintDismissed, batteryStatus, settings::dismissBatteryHint,
        )
        val batteryHintVisible = batteryHint.visible

        fun refreshBatteryStatus() = batteryHint.refresh()

        fun dismissBatteryHint() {
            batteryHint.dismiss()
        }

        private val healthCheckPolicy = HealthCheckPolicy(::monotonicMs)
        private var healthCheckJob: kotlinx.coroutines.Job? = null

        private fun monotonicMs() = System.nanoTime() / 1_000_000

        /** Home entered STARTED: on-demand probes may run. Idempotent. */
        fun onHomeStarted() {
            if (healthCheckJob?.isActive == true) return
            healthCheckJob = viewModelScope.launch {
                runHealthChecks(connectionManager.state, connectionManager.health, healthCheckPolicy, ::monotonicMs) {
                    connectionManager.requestHealthCheck()
                }
            }
        }

        /** Home left STARTED (other screen, background, screen off): nothing keeps probing. */
        fun onHomeStopped() {
            healthCheckJob?.cancel()
            healthCheckJob = null
            healthCheckPolicy.reset()
        }

        init {
            viewModelScope.launch {
                connectionManager.state.collect { state ->
                    if (state is VpnConnectionState.Error) {
                        lastErrorMessage.value = state.error
                    }
                }
            }
        }

        /** Wall clock, re-read periodically: the engine's groups flow only
         *  emits on change, so without a tick a measurement that ages out
         *  would stay on screen until something else moved. */
        private val clockTicks: Flow<Long> =
            flow {
                while (true) {
                    emit(latencyRepository.now())
                    delay(CLOCK_TICK_MS)
                }
            }

        val uiState: StateFlow<HomeUiState> =
            combine(
                connectionManager.state,
                settings.selectedNodeId,
                nodeDao.observeEnabled(),
                subscriptions.profiles,
                settings.restartGuardTripped,
                connectionManager.groups,
                settings.perAppPolicy,
                settings.routeMode,
                connectionManager.underlyingTransport,
                lastErrorMessage,
                connectionManager.appliedSessionConfig,
                nodePreferenceDao.observeAll(),
                settings.dnsProfile,
                clockTicks,
                connectionManager.selectionError,
                healthHintFlow(
                    connectionManager.state,
                    connectionManager.health,
                    nowMonotonicMs = { System.nanoTime() / 1_000_000 },
                ),
            ) { values ->
                val connection = values[0] as VpnConnectionState
                val selectedId = values[1] as String?

                @Suppress("UNCHECKED_CAST")
                val nodes = values[2] as List<dev.typenil.vpnclient.data.db.NodeEntity>

                @Suppress("UNCHECKED_CAST")
                val profiles = values[3] as List<dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile>
                val guardTripped = values[4] as Boolean

                @Suppress("UNCHECKED_CAST")
                val groups = values[5] as List<dev.typenil.vpnclient.core.engine.OutboundGroupInfo>

                @Suppress("UNCHECKED_CAST")
                val perAppPolicy = values[6] as Pair<PerAppMode, Set<String>>
                val perAppMode = perAppPolicy.first
                val perAppPackages = perAppPolicy.second
                val routeMode = values[7] as RouteMode
                val underlay = values[8] as UnderlyingTransport
                val lastError = values[9] as VpnError?
                val appliedConfig = values[10] as AppliedSessionConfig?

                @Suppress("UNCHECKED_CAST")
                val prefs =
                    values[11] as List<dev.typenil.vpnclient.data.db.NodePreferenceEntity>
                val prefById = prefs.associateBy { it.nodeId }
                val favoriteIds =
                    prefs
                        .asSequence()
                        .filter { it.isFavorite }
                        .map { it.nodeId }
                        .toSet()
                val dnsProfile = values[12] as DnsProfile
                val nowMs = values[13] as Long

                // Picker source: usable + not hidden — the engine can't
                // select a disabled node, and a hidden one has already left
                // the user's lists. The prefs join (not just the node row)
                // decides both. Auto stays first regardless.
                val usableNodes =
                    nodes.filter { node ->
                        val pref = prefById[node.id]
                        (pref?.isEnabled ?: true) && !(pref?.isHidden ?: false)
                    }
                val selected = usableNodes.firstOrNull { it.id == selectedId }
                val auto = selectedId == NodeSelection.AUTO_ID
                val subscriptionNames = profiles.associate { it.id to it.name }
                // What the header describes: the picked node, or — for Auto —
                // the urltest group's measured winner. "First enabled
                // subscription" would name a provider the session isn't using.
                val autoGroup = groups.firstOrNull { it.tag == NodeSelection.AUTO_ID }
                // Only fresh measurements count: a stale last-good delay of a
                // dead node must not be named the best (see UrlTestFreshness).
                val bestItem = autoGroup?.items?.let { pickBestLatency(it, nowMs) }
                val bestNode = bestItem?.let { item -> nodes.firstOrNull { it.id == item.tag } }
                val bestLatencyNodeName = bestNode?.let {
                    prefById[it.id]?.customName?.takeIf { n -> n.isNotBlank() } ?: it.name
                }
                val bestLatencyMs = bestItem?.urlTestDelayMs
                val selectedDelayMs = if (auto) bestLatencyMs else groups.asSequence()
                    .flatMap { it.items.asSequence() }
                    .firstOrNull { it.tag == selectedId }
                    ?.freshDelayMs(nowMs)
                val selectedDelayStaleMinutes = staleLatencyAgeMinutes(
                    if (auto) autoGroup?.items.orEmpty()
                    else groups.asSequence().flatMap { it.items.asSequence() }.filter { it.tag == selectedId }.toList(),
                    nowMs,
                )

                HomeUiState(
                    connection = connection,
                    selectionError = if (connection is VpnConnectionState.Connected || connection is VpnConnectionState.Reconnecting)
                        values[14] as VpnError? else null,
                    // Auto resolves to a member only at the engine; the UI keeps
                    // the mode label stable and reports a measured winner separately.
                    // Custom name applies to the header too — same presentation
                    // rule as the picker.
                    selectedNodeName =
                        selected?.let {
                            prefById[it.id]?.customName?.takeIf { n -> n.isNotBlank() }
                                ?: it.name
                        },
                    selectedNodeProtocol = selected?.protocol,
                    autoSelected = auto,
                    bestLatencyNodeName = if (auto) bestLatencyNodeName else null,
                    bestLatencyNodeProtocol = if (auto) bestNode?.protocol else null,
                    selectedDelayMs = selectedDelayMs,
                    selectedDelayStaleMinutes = selectedDelayStaleMinutes,
                    // Expiry runs on the store's monotonic clock inside healthHintFlow (own 5 s tick).
                    healthHint = values[15] as HomeHealthHint,
                    noNodesAtAll = nodes.isEmpty(),
                    enabledSubscriptionCount = profiles.count {
                        it.enabled && !SubscriptionRepository.isManualSubscription(it.url)
                    },
                    serverOptions =
                        if (nodes.isEmpty()) {
                            emptyList()
                        } else {
                            buildList {
                                add(
                                    ServerOption(
                                        id = NodeSelection.AUTO_ID,
                                        titleRes = R.string.common_auto_fastest,
                                        subtitleRes = R.string.home_auto_subtitle,
                                        searchText = NodeSelection.AUTO_ID,
                                    ),
                                )
                                // Favorites first, right after Auto — the
                                // quick pick is where they pay off. Original
                                // order (subscriptionId, position) is kept
                                // inside each partition.
                                val ordered =
                                    usableNodes.sortedByDescending { it.id in favoriteIds }
                                ordered.forEach { node ->
                                    add(
                                        ServerOption(
                                            id = node.id,
                                            // Local override wins over the
                                            // provider name — same rule as
                                            // ServersCard.
                                            title =
                                                prefById[node.id]
                                                    ?.customName
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?: node.name,
                                            subtitle =
                                                listOfNotNull(
                                                    node.protocol,
                                                    subscriptionNames[node.subscriptionId],
                                                ).joinToString(" · "),
                                            // Match the host too — a user hunting
                                            // for a specific server usually
                                            // knows its address, not its name.
                                            searchText = "${node.name} ${node.server}",
                                            encrypted = isEncrypted(node.toDomain()),
                                            tunnelAllowed = isTunnelAllowed(node.toDomain()),
                                        ),
                                    )
                                }
                            }
                        },
                    selectedOptionId = selectedId,
                    subscriptionName = (if (auto) bestNode else selected)?.let { subscriptionNames[it.subscriptionId] },
                    routingMode = if (connection is VpnConnectionState.Connected ||
                        connection is VpnConnectionState.Reconnecting
                    ) appliedConfig?.routeMode else routeMode,
                    routingPending = (connection is VpnConnectionState.Connected ||
                        connection is VpnConnectionState.Reconnecting) &&
                        appliedConfig != null && appliedConfig.routeMode != routeMode,
                    restartGuardTripped = guardTripped,
                    // The sheet is real-state only: it exists only while Connected —
                    // outside that there is no session to describe.
                    sessionDetails =
                        if (connection is VpnConnectionState.Connected) {
                            SessionDetails(
                                // The engine's selector group reports what actually
                                // egresses — the session node label can lag a live
                                // switch, so the group's selected tag is authoritative.
                                // The tag itself is a raw outbound id (hash) — resolve
                                // it to the node's name; "auto" means the urltest group
                                // is the egress (Auto), a member tag resolves
                                // through the node table.
                                activeOutbound =
                                    groups
                                        .firstOrNull { it.selectable }
                                        ?.selected
                                        ?.let { tag ->
                                            when {
                                                tag == NodeSelection.AUTO_ID -> null
                                                else -> nodes.firstOrNull { it.id == tag }?.name ?: tag
                                            }
                                        },
                                activeOutboundRes =
                                    if (
                                        groups
                                            .firstOrNull { it.selectable }
                                            ?.selected == NodeSelection.AUTO_ID
                                    ) {
                                        R.string.common_auto_fastest
                                    } else {
                                        null
                                    },
                                // Applied plan — a route-mode change is only
                                // real after a reconnect, and per-app after a
                                // TUN rebuild; the settings flow is intent.
                                applied = appliedConfig,
                                pendingReconnect =
                                    buildList {
                                        if (appliedConfig != null) {
                                            if (appliedConfig.routeMode != routeMode) {
                                                add(R.string.common_routing_mode)
                                            }
                                            // The set, not the size: replacing
                                            // one app with another is still
                                            // an unapplied change.
                                            if (appliedConfig.perAppMode != perAppMode ||
                                                appliedConfig.perAppPackages != perAppPackages
                                            ) {
                                                add(R.string.common_per_app_vpn)
                                            }
                                            if (appliedConfig.dnsProfileSummary != dnsProfile.summary) {
                                                add(R.string.routing_dns_upstream)
                                            }
                                        }
                                    },
                                underlay = underlay,
                                lastError = lastError?.message,
                                lastErrorRes = if (lastError == VpnError.UnencryptedTransport)
                                    R.string.home_error_unencrypted else null,
                            )
                        } else {
                            null
                        },
                )
            }.flowOn(Dispatchers.Default).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                // Seed with the live state — Idle would flash "Disconnected" for a
                // frame on a connected session before combine's first emission.
                initialValue = HomeUiState(connection = connectionManager.state.value),
            )

        /** Persist a pick from the sheet — the same `selected_node_id` slot
         *  Servers writes; ConnectionManager reconciles a live engine with it. */
        fun selectServer(id: String) {
            viewModelScope.launch { settings.setSelectedNodeId(id) }
        }

        /** Dismiss the "pick not applied" notice. */
        fun dismissSelectionNotice() = connectionManager.clearSelectionError()

        /**
         * Every manual connect starts in Auto mode so the engine's urltest
         * selector chooses the best eligible server instead of reusing an
         * old manually pinned node.
         */
        fun connect() {
            viewModelScope.launch {
                settings.setSelectedNodeId(NodeSelection.AUTO_ID)
                connectionManager.connect()
            }
        }

        fun disconnect() = connectionManager.disconnect()

        /** Explicit dismiss — connecting again also clears it via the reset. */
        fun dismissRestartGuardWarning() {
            viewModelScope.launch {
                runCatching { settings.setRestartGuardTripped(false) }
            }
        }
    }

/** Resolution of "how old is this measurement" on Home; the window is minutes. */
private const val CLOCK_TICK_MS = 30_000L
