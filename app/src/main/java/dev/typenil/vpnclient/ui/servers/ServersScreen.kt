package dev.typenil.vpnclient.ui.servers

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import dev.typenil.vpnclient.ui.common.headingSemantics
import dev.typenil.vpnclient.ui.theme.AfterglowDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import dev.typenil.vpnclient.ui.theme.AfterglowCheckbox
import dev.typenil.vpnclient.ui.theme.AfterglowChip as FilterChip
import dev.typenil.vpnclient.ui.theme.AfterglowRadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.singbox.NodeTlsSummary
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.isRussianServer
import dev.typenil.vpnclient.data.toDomain
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.data.LatencyMethod
import dev.typenil.vpnclient.data.NodeLatency
import dev.typenil.vpnclient.ui.common.relativeTimeText
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.LocalSheetMaxHeight

@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    onAddServer: () -> Unit = {},
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    // A direct-probe run has nobody to show its progress to once the screen is
    // gone — but a rotation recreates the composition and must not kill it.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onScreenLeft() }
    }

    // One column on phones, two on tablets — 160 dp min cards crushed the
    // name/touch targets into slivers.
    val sheetMax = LocalSheetMaxHeight.current
        ?: LocalConfiguration.current.screenHeightDp.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        modifier = modifier.fillMaxWidth().heightIn(max = sheetMax * .78f),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
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
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortSelector(
                        sortMode = ui.sortMode,
                        onSelect = viewModel::setSortMode,
                    )
                    // Star-only toggle — the word "Избранное" was what pushed
                    // the Filters chip off the viewport; the glyph reads the
                    // same in both locales. FilterChip carries the selected
                    // semantics + visible state for free.
                    val favLabel = stringResource(R.string.servers_favorites)
                    FilterChip(
                        selected = ui.favoritesOnly,
                        onClick = { viewModel.setFavoritesOnly(!ui.favoritesOnly) },
                        label = {
                            Icon(
                                if (ui.favoritesOnly) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = favLabel,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    // Subscription / protocol / hidden filters sit behind one
                    // dialog entry — as chips they grew into a long strip that
                    // pushed the actual list out of view.
                    FiltersEntry(ui, viewModel)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (testing) {
                        progress?.let {
                            Text(
                                text = stringResource(R.string.servers_latency_progress, it.done, it.total),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = viewModel::cancelLatencyTest) {
                            Text(stringResource(R.string.servers_latency_stop))
                        }
                    } else {
                        TextButton(onClick = viewModel::testLatency) {
                            Text(stringResource(R.string.servers_test_latency))
                        }
                    }
                    Text(
                        // Says what the button beside it will do — never what
                        // the shown badges are (each badge carries its own
                        // method and age): connected → urltest through the
                        // proxy chain; disconnected → direct TCP.
                        text =
                            stringResource(
                                if (ui.connected) {
                                    R.string.servers_latency_next_proxy
                                } else {
                                    R.string.servers_latency_next_tcp
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (ui.groups.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ui.noSubscriptions) {
                        // Nothing imported at all — offer the way forward,
                        // not just a dead label. Opens the Subscriptions
                        // tab's add dialog (URL or share link).
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.common_no_servers_yet),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.common_add_servers_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onAddServer) {
                                Text(stringResource(R.string.common_add_server_cta))
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.servers_no_match),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // "Auto / Fastest" — persisted as the AUTO_ID sentinel; the engine
        // compiles/selects the urltest group for it, so it never maps to a
        // concrete node card. Hidden with no nodes at all: there would be
        // nothing to measure, and picking it would persist a selection that
        // cannot connect.
        if (!ui.noSubscriptions) {
            item(key = "auto", span = { GridItemSpan(maxLineSpan) }) {
                AutoCard(
                    selected = ui.selectedNodeId == NodeSelection.AUTO_ID,
                    delayMs = ui.delays[NodeSelection.AUTO_ID],
                    onClick = viewModel::selectAuto,
                )
            }
            val hasRussianServers = ui.groups.any { group ->
                group.nodes.any { it.entity.toDomain().isRussianServer() }
            }
            if (hasRussianServers) {
                item(key = "auto-russia", span = { GridItemSpan(maxLineSpan) }) {
                    AutoCard(
                        selected = ui.selectedNodeId == NodeSelection.AUTO_RUSSIA_ID,
                        delayMs = ui.delays[NodeSelection.AUTO_RUSSIA_ID],
                        titleRes = R.string.common_auto_russia,
                        onClick = viewModel::selectAutoRussia,
                    )
                }
            }
        }

        ui.groups.forEach { group ->
            if (ui.showHeaders) {
                item(key = "header-${group.subscriptionId}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text =
                            group.subscriptionName
                                ?: stringResource(
                                    R.string.common_subscription_numbered,
                                    group.subscriptionId,
                                ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(
                items = group.nodes,
                key = { node -> node.id },
            ) { node ->
                ServerCard(
                    node = node,
                    selected = node.id == ui.selectedNodeId,
                    delayMs = ui.delays[node.id],
                    tested = node.id in ui.testedNodeIds,
                    stored = ui.storedLatency[node.id],
                    // A disabled node stays rendered (the user manages it) but
                    // tapping it must not persist a selection the engine can't
                    // honor — keep the card, drop the click.
                    onClick = { if (node.enabled) viewModel.selectNode(node.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(node.id) },
                    onRename = { newName -> viewModel.setNodeCustomName(node.id, newName) },
                    onSetEnabled = { enabled -> viewModel.setNodeEnabled(node.id, enabled) },
                    onSetHidden = { hidden -> viewModel.setNodeHidden(node.id, hidden) },
                )
            }
        }
    }
}

@Composable
private fun SortSelector(
    sortMode: ServerSortMode,
    onSelect: (ServerSortMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Bare mode label, no "Sort:" prefix — the long Russian label
        // ("Сортировка: по задержке") pushed the Filters chip off-screen.
        // The dropdown arrow already signals a menu; the items name it.
        FilterChip(
            selected = sortMode != ServerSortMode.Default,
            onClick = { open = true },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sortModeLabel(sortMode))
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ServerSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortModeLabel(mode)) },
                    onClick = {
                        open = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == sortMode) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FiltersEntry(
    ui: ServersUiState,
    viewModel: ServersViewModel,
) {
    var open by remember { mutableStateOf(false) }
    val active =
        listOfNotNull(ui.subscriptionFilter, ui.protocolFilter).size +
            (if (ui.showHidden) 1 else 0)
    FilterChip(
        selected = active > 0,
        onClick = { open = true },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.servers_filters) +
                        if (active > 0) " · $active" else "",
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.servers_filters)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (ui.subscriptionOptions.size > 1) {
                        FilterHeader(stringResource(R.string.servers_filter_subscription))
                        FilterOptionRow(
                            stringResource(R.string.servers_filter_all),
                            ui.subscriptionFilter == null,
                        ) { viewModel.setSubscriptionFilter(null) }
                        ui.subscriptionOptions.forEach { option ->
                            FilterOptionRow(
                                option.name.ifBlank {
                                    stringResource(R.string.common_subscription_numbered, option.id)
                                },
                                ui.subscriptionFilter == option.id,
                            ) { viewModel.setSubscriptionFilter(option.id) }
                        }
                    }
                    if (ui.protocolOptions.size > 1) {
                        FilterHeader(stringResource(R.string.servers_filter_protocol))
                        FilterOptionRow(
                            stringResource(R.string.servers_filter_all),
                            ui.protocolFilter == null,
                        ) { viewModel.setProtocolFilter(null) }
                        ui.protocolOptions.forEach { protocol ->
                            FilterOptionRow(
                                protocolLabel(protocol),
                                ui.protocolFilter == protocol,
                            ) { viewModel.setProtocolFilter(protocol) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(AfterglowTokens.chipShape)
                            .clickable { viewModel.setShowHidden(!ui.showHidden) }
                            .heightIn(min = AfterglowTokens.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AfterglowCheckbox(checked = ui.showHidden, onCheckedChange = null)
                        Text(
                            stringResource(R.string.servers_show_hidden),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.common_done))
                }
            },
            dismissButton = {
                if (active > 0) {
                    TextButton(onClick = {
                        viewModel.setSubscriptionFilter(null)
                        viewModel.setProtocolFilter(null)
                        viewModel.setShowHidden(false)
                    }) {
                        Text(stringResource(R.string.servers_filter_reset))
                    }
                }
            },
        )
    }
}

@Composable
private fun FilterHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.headingSemantics().padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FilterOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(AfterglowTokens.chipShape).clickable(onClick = onClick)
            .heightIn(min = AfterglowTokens.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AfterglowRadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun sortModeLabel(mode: ServerSortMode): String =
    when (mode) {
        ServerSortMode.Default -> stringResource(R.string.sort_default)
        ServerSortMode.Latency -> stringResource(R.string.sort_latency)
        ServerSortMode.Name -> stringResource(R.string.sort_name)
    }

@Composable
private fun AutoCard(
    selected: Boolean,
    delayMs: Int?,
    titleRes: Int = R.string.common_auto_fastest,
    onClick: () -> Unit,
) {
    val colors = AfterglowTheme.colors
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = AfterglowTokens.cardShape,
        border = BorderStroke(AfterglowTokens.border, if (selected) colors.coral else colors.border),
        colors = CardDefaults.cardColors(containerColor = colors.paperSecondary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            LatencyBadge(delayMs = delayMs, tested = delayMs != null)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.common_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    node: ServerNode,
    selected: Boolean,
    delayMs: Int?,
    tested: Boolean,
    stored: NodeLatency?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: (String?) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetHidden: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTlsDetails by remember { mutableStateOf(false) }
    val colors = AfterglowTheme.colors
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = AfterglowTokens.cardShape,
        border = BorderStroke(AfterglowTokens.border, if (selected) colors.coral else colors.border),
        colors = CardDefaults.cardColors(containerColor = colors.paperSecondary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    // minLines keeps every card the same height — a one-line
                    // name must not shrink the card below its two-line peers.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(AfterglowTokens.touchTarget),
                ) {
                    Icon(
                        imageVector =
                            if (node.favorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription =
                            stringResource(
                                if (node.favorite) {
                                    R.string.servers_favorite_remove
                                } else {
                                    R.string.servers_favorite_add
                                },
                            ),
                        tint =
                            if (node.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(18.dp),
                    )
                }
                // Per-node management — enable/hide/rename live in prefs and
                // survive refresh; they never rewrite the subscription row.
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(AfterglowTokens.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.servers_node_menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.servers_tls_details)) },
                            onClick = {
                                menuOpen = false
                                showTlsDetails = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.servers_rename)) },
                            onClick = {
                                menuOpen = false
                                showRename = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (node.enabled) {
                                            R.string.servers_disable
                                        } else {
                                            R.string.servers_enable
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSetEnabled(!node.enabled)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (node.hidden) {
                                            R.string.servers_unhide
                                        } else {
                                            R.string.servers_hide
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSetHidden(!node.hidden)
                            },
                        )
                    }
                }
                if (selected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // Disabled / hidden are honest state — the card stays rendered
            // (the user manages it), but neither is engine-pickable.
            // An insecure TLS config is a risk the user must see even before
            // connecting — the details dialog carries the full picture.
            if (node.tls.insecure) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.servers_tls_insecure_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!node.encrypted) {
                Text(stringResource(R.string.servers_unencrypted),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(stringResource(if (node.tunnelAllowed) R.string.servers_local_sidecar else R.string.servers_connection_blocked),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (!node.enabled) {
                Text(
                    stringResource(R.string.servers_disabled_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (node.hidden) {
                Text(
                    stringResource(R.string.servers_hidden_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolBadge(node.protocol)
                Spacer(Modifier.weight(1f))
                if (delayMs != null || tested || stored == null) {
                    LatencyBadge(delayMs = delayMs, tested = tested)
                }
            }
            if (delayMs == null && !tested && stored != null) {
                StoredLatency(stored, Modifier.fillMaxWidth())
            }
        }
    }
    if (showRename) {
        var text by remember { mutableStateOf(node.customName ?: node.providerName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.servers_rename_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(node.providerName) },
                    supportingText = {
                        Text(stringResource(R.string.servers_rename_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Blank input = "clear the override" → provider name.
                        onRename(text.trim().ifBlank { null })
                        showRename = false
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showTlsDetails) {
        NodeTlsDialog(node = node, onDismiss = { showTlsDetails = false })
    }
}

/**
 * Read-only TLS posture of the stored node config — provenance (share link
 * vs imported config; the original format isn't stored, so it is never
 * guessed), authentication mode, and sanitized SNI/ALPN. Reachable from the
 * node menu while connected or disconnected; values describe the imported
 * config, never an observed handshake.
 */
@Composable
private fun NodeTlsDialog(
    node: ServerNode,
    onDismiss: () -> Unit,
) {
    val tls = node.tls
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_tls_details)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                TlsDetailRow(
                    label = stringResource(R.string.servers_tls_origin),
                    value =
                        stringResource(
                            if (node.entity.rawUri != null) {
                                R.string.servers_tls_origin_sharelink
                            } else {
                                R.string.servers_tls_origin_subscription
                            },
                        ),
                )
                TlsDetailRow(
                    label = stringResource(R.string.servers_tls_mode),
                    value =
                        stringResource(
                            when (tls.mode) {
                                NodeTlsSummary.Mode.NONE -> R.string.servers_tls_mode_none
                                NodeTlsSummary.Mode.CERTIFICATE ->
                                    R.string.servers_tls_mode_certificate
                                NodeTlsSummary.Mode.REALITY -> R.string.servers_tls_mode_reality
                                NodeTlsSummary.Mode.UNKNOWN -> R.string.servers_tls_mode_unknown
                            },
                        ),
                )
                if (!node.encrypted) {
                    Text(stringResource(R.string.servers_unencrypted), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(if (node.tunnelAllowed) R.string.servers_local_sidecar
                        else R.string.home_error_unencrypted))
                }
                if (tls.insecure) {
                    TlsDetailRow(
                        label = stringResource(R.string.servers_tls_insecure_row),
                        value = stringResource(R.string.servers_tls_insecure_disabled),
                        warning = true,
                    )
                }
                tls.serverName?.let {
                    TlsDetailRow(label = stringResource(R.string.servers_tls_sni), value = it)
                }
                if (tls.alpn.isNotEmpty()) {
                    TlsDetailRow(
                        label = stringResource(R.string.servers_tls_alpn),
                        value = tls.alpn.joinToString(", "),
                    )
                }
                Text(
                    text = stringResource(R.string.servers_tls_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
    )
}

@Composable
private fun TlsDetailRow(
    label: String,
    value: String,
    warning: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (warning) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
        )
    }
}

/** A persisted verdict is history: muted value, then which measurement it was
 *  and how old it is — never styled like a live reading. */
@Composable
private fun StoredLatency(
    stored: NodeLatency,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val method =
        stringResource(
            when (stored.method) {
                LatencyMethod.Tcp -> R.string.servers_latency_method_tcp
                LatencyMethod.Proxy -> R.string.servers_latency_method_proxy
            },
        )
    val freshness =
        stringResource(
            if (stored.isFresh(now)) R.string.servers_latency_earlier else R.string.servers_latency_stale,
        )
    val age = relativeTimeText(maxOf(0L, now - stored.checkedAtMs))
    Text(
        text =
            if (stored.latencyMs != null) {
                stringResource(R.string.servers_latency_ms, stored.latencyMs)
            } else {
                stringResource(R.string.servers_latency_timeout)
            } + " · $method · $freshness, $age",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

@Composable
private fun LatencyBadge(
    delayMs: Int?,
    tested: Boolean,
) {
    when {
        delayMs != null -> {
            Text(
                text = stringResource(R.string.servers_latency_ms, delayMs),
                style = MaterialTheme.typography.labelSmall,
                // Light jade (tertiary) on paper fails text contrast — ink
                // keeps the value legible in both themes.
                color =
                    if (delayMs < 800) {
                        AfterglowTheme.colors.ink
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }

        tested -> {
            Text(
                text = stringResource(R.string.servers_latency_timeout),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.common_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    val colors = AfterglowTheme.colors
    Surface(color = colors.ink, shape = AfterglowTokens.chipShape) {
        Text(
            text = protocolLabel(protocol),
            style = MaterialTheme.typography.labelSmall,
            color = colors.paper,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
