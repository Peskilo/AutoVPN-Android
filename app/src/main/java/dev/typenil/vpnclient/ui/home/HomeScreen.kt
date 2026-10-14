package dev.typenil.vpnclient.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.PerAppMode
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.ui.common.CORE_VERSION
import dev.typenil.vpnclient.ui.common.DetailRow
import dev.typenil.vpnclient.ui.common.appliedDnsSummary
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate
import dev.typenil.vpnclient.ui.common.perAppSummary
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.underlayLabel
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.ui.theme.AfterglowSheet
import dev.typenil.vpnclient.ui.theme.AfterglowSheetHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.LocalSheetMaxHeight
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenConnections: () -> Unit = {},
    onAddServer: () -> Unit = {},
    onOpenServers: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenRouting: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = ui.connection
    val batteryHintVisible by viewModel.batteryHintVisible.collectAsStateWithLifecycle()
    val batteryLifecycle = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(batteryLifecycle, viewModel) {
        viewModel.refreshBatteryStatus()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryStatus()
        }
        batteryLifecycle.lifecycle.addObserver(observer)
        onDispose { batteryLifecycle.lifecycle.removeObserver(observer) }
    }
    // On-demand health probes only while Home is visible (STARTED); the observer
    // replays ON_START if already started and stops everything on ON_STOP/dispose.
    androidx.compose.runtime.DisposableEffect(batteryLifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onHomeStarted()
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHomeStopped()
        }
        batteryLifecycle.lifecycle.addObserver(observer)
        onDispose {
            batteryLifecycle.lifecycle.removeObserver(observer)
            viewModel.onHomeStopped()
        }
    }

    var showPicker by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    AfterglowHomeContent(
        ui = ui,
        modifier = modifier,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onPick = { showPicker = true },
        onAddServer = onAddServer,
        onOpenConnections = onOpenConnections,
        onOpenDetails = { showDetails = true },
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenRouting = onOpenRouting,
        onOpenServers = onOpenServers,
        onOpenSubscriptions = onOpenSubscriptions,
        onOpenSettings = onOpenSettings,
        onDismissGuard = viewModel::dismissRestartGuardWarning,
        batteryHint = {
            if (batteryHintVisible && connection is VpnConnectionState.Connected) {
                dev.typenil.vpnclient.ui.common.BatteryHintCard(onDismiss = viewModel::dismissBatteryHint)
            }
        },
        errorMessage = when (val notice = ui.notice()) {
            is HomeNotice.Failure -> errorText(notice.error)
            is HomeNotice.SelectionRejected -> stringResource(R.string.home_selection_unencrypted)
            null -> null
        },
        onDismissNotice = if (ui.notice() is HomeNotice.SelectionRejected) viewModel::dismissSelectionNotice else null,
    )
    if (showPicker) {
        ServerPickerSheet(
            options = ui.serverOptions,
            selectedId = ui.selectedOptionId,
            onPick = { showPicker = false; viewModel.selectServer(it) },
            onOpenServers = { showPicker = false; onOpenServers() },
            onDismiss = { showPicker = false },
        )
    }
    if (showDetails && connection is VpnConnectionState.Connected) {
        SessionDetailsSheet(connection, ui.sessionDetails, onDismiss = { showDetails = false })
    }
}

@Composable
private fun errorText(error: VpnError): String =
    when (error) {
        VpnError.PermissionDenied -> stringResource(R.string.home_error_permission_denied)
        VpnError.PermissionRevoked -> stringResource(R.string.home_error_permission_revoked)
        VpnError.NoNodeSelected -> stringResource(R.string.home_error_no_node)
        VpnError.UnencryptedTransport -> stringResource(R.string.home_error_unencrypted)
        is VpnError.ConfigInvalid -> stringResource(R.string.home_error_config, error.detail)
        is VpnError.EngineFailed -> stringResource(R.string.home_error_engine, error.detail)
        is VpnError.TunnelFailed -> stringResource(R.string.home_error_tunnel, error.detail)
        is VpnError.Unexpected -> error.detail
    }

/**
 * Quick-pick bottom sheet: Auto pinned first, then every enabled node behind a
 * search box. Bounded and lazy — a subscription with hundreds of nodes must not
 * turn the picker into a full-screen list, and the Servers tab stays the place
 * for filters, sorting and latency tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerPickerSheet(
    options: List<ServerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onOpenServers: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val autos = options.filter { it.id == NodeSelection.AUTO_ID || it.id == NodeSelection.AUTO_RUSSIA_ID }
    val nodes = options.filterNot { it.id == NodeSelection.AUTO_ID || it.id == NodeSelection.AUTO_RUSSIA_ID }
    val trimmed = query.trim()
    val matches =
        if (trimmed.isEmpty()) {
            nodes
        } else {
            // searchText carries name + host for real nodes; the Auto row's
            // localized title is matched too so a translated label is found.
            nodes.filter { option ->
                option.searchText?.contains(trimmed, ignoreCase = true) == true ||
                    option.title?.contains(trimmed, ignoreCase = true) == true
            }
        }
    val matchingAutos = autos.filter { option ->
        val title = option.titleRes?.let { stringResource(it) }.orEmpty()
        trimmed.isEmpty() ||
            title.contains(trimmed, ignoreCase = true) ||
            option.searchText?.contains(trimmed, ignoreCase = true) == true
    }

    val colors = AfterglowTheme.colors
    AfterglowSheet(onDismiss = onDismiss) {
        // Bound to the sheet's real space (readable only inside the sheet
        // content) so the list shrinks with the IME — not a fixed height
        // behind the keyboard.
        val sheetMax = LocalSheetMaxHeight.current
        val listCap = sheetMax?.let { minOf(420.dp, it * 0.6f) } ?: 420.dp
        Column(Modifier.padding(bottom = 12.dp)) {
            AfterglowSheetHeader(
                title = stringResource(R.string.home_picker_title),
                closeLabel = stringResource(R.string.common_dismiss),
                onDismiss = onDismiss,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .heightIn(min = AfterglowTokens.fieldHeight),
                shape = AfterglowTokens.inputShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.coral,
                    unfocusedBorderColor = colors.borderStrong,
                    focusedContainerColor = colors.paperSecondary,
                    unfocusedContainerColor = colors.paperSecondary,
                    cursorColor = colors.coral,
                ),
                placeholder = { Text(stringResource(R.string.home_picker_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            // Outside the list: the escape hatch must stay reachable with the
            // soft keyboard open, which covers the bottom of the sheet.
            TextButton(
                onClick = onOpenServers,
                modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.home_picker_all_servers), color = colors.coral)
            }
            LazyColumn(Modifier.heightIn(max = listCap)) {
                // Auto is the picker's first-class choice — shown on an
                // empty query or when its own label/tag matches the search.
                items(matchingAutos, key = { it.id }) { option ->
                    PickerRow(
                        option = option,
                        selected = option.id == selectedId,
                        onClick = { onPick(option.id) },
                    )
                }
                items(matches, key = { it.id }) { option ->
                    PickerRow(
                        option = option,
                        selected = option.id == selectedId,
                        onClick = { onPick(option.id) },
                    )
                }
                // A query that hits only the Auto row is still a match —
                // don't pair the result with a "no match" caption.
                if (matches.isEmpty() && matchingAutos.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = stringResource(R.string.home_picker_no_match, trimmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    option: ServerOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AfterglowTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .border(AfterglowTokens.border, if (selected) colors.coral else colors.border, AfterglowTokens.chipShape)
                .background(if (selected) colors.errorSurface else colors.paperSecondary, AfterglowTokens.chipShape)
                .semantics { this.selected = selected }
                .clickable(onClick = onClick)
                .heightIn(min = AfterglowTokens.rowHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text =
                    option.title
                        ?: option.titleRes
                            ?.let { stringResource(it) }
                            .orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!option.encrypted) {
                Text(stringResource(R.string.servers_unencrypted),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(stringResource(if (option.tunnelAllowed) R.string.servers_local_sidecar else R.string.servers_connection_blocked),
                    style = MaterialTheme.typography.labelSmall)
            }
            val optionSubtitle = option.subtitle ?: option.subtitleRes?.let { stringResource(it) }
            optionSubtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.common_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Real session details: engine-reported outbound, persisted routing/per-app
 * settings, the service's underlay transport, and stats from the live
 * Connected payload. Nothing is invented — absent values render as "—".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailsSheet(
    state: VpnConnectionState.Connected,
    details: SessionDetails?,
    onDismiss: () -> Unit,
) {
    // Uptime reads live: a 1 Hz ticker keeps the sheet's "now" current while
    // it's open, gated by lifecycle like the home stats ticker.
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1_000) }
        }
    }
    AfterglowSheet(onDismiss = onDismiss) {
        AfterglowSheetHeader(
            title = stringResource(R.string.home_details_title),
            closeLabel = stringResource(R.string.common_dismiss),
            onDismiss = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val none = stringResource(R.string.common_none)
            DetailRow(stringResource(R.string.home_details_node), state.node.name)
            // The selector group's pick is what actually egresses — can
            // diverge from the session node after a live outbound switch.
            DetailRow(
                stringResource(R.string.home_details_outbound),
                details?.activeOutbound
                    ?: details?.activeOutboundRes?.let { stringResource(it) }
                    ?: none,
            )
            // Applied plan only — the settings value may not be live yet
            // (a route-mode change needs a reconnect, per-app a rebuild).
            DetailRow(
                stringResource(R.string.common_routing_mode),
                details?.applied?.let { routeModeSummary(it.routeMode) } ?: none,
            )
            DetailRow(
                stringResource(R.string.common_per_app_vpn),
                details?.applied?.let { perAppSummary(it.perAppMode, it.perAppPackages.size) } ?: none,
            )
            DetailRow(
                stringResource(R.string.routing_dns_upstream),
                appliedDnsSummary(details?.applied?.dnsProfileSummary),
            )
            // Named, not silently ignored: these are changed-but-unapplied.
            details?.takeIf { it.pendingReconnect.isNotEmpty() }?.let {
                val pendingLabels = it.pendingReconnect.map { res -> stringResource(res) }
                DetailRow(
                    stringResource(R.string.home_details_pending),
                    pendingLabels.joinToString(", "),
                )
            }
            DetailRow(
                stringResource(R.string.home_details_underlay),
                details?.underlay?.let { underlayLabel(it) } ?: none,
            )
            DetailRow(stringResource(R.string.home_details_uptime), uptimeText(state.since, now))
            state.stats?.let { stats ->
                DetailRow(
                    stringResource(R.string.home_details_data_used),
                    stringResource(
                        R.string.home_details_data_used_value,
                        formatBytes(stats.downlinkTotalBytes),
                        formatBytes(stats.uplinkTotalBytes),
                    ),
                )
            }
            DetailRow(stringResource(R.string.common_vpn_core), CORE_VERSION)
            // Most recent Error seen this process — historical context, not
            // live state, so it renders even when the session is healthy.
            val lastError = details?.lastErrorRes?.let { stringResource(it) } ?: details?.lastError
            lastError?.let { DetailRow(stringResource(R.string.common_last_error), it) }
        }
    }
}
