package dev.typenil.vpnclient.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import dev.typenil.vpnclient.ui.theme.AfterglowDialog as AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTextField as OutlinedTextField
import dev.typenil.vpnclient.ui.theme.AfterglowRadioButton as RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import dev.typenil.vpnclient.ui.theme.AfterglowSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.AppLanguage
import dev.typenil.vpnclient.core.common.ThemeMode
import dev.typenil.vpnclient.core.subscription.HwidConsent
import dev.typenil.vpnclient.ui.common.openBatterySettings
import dev.typenil.vpnclient.ui.home.SectionHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTokens

@Composable
fun SettingsScreen(
    onOpenRouting: () -> Unit,
    onOpenAppFilter: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showHwidExplanation by remember { mutableStateOf(false) }
    var pendingHwidAllow by remember { mutableStateOf(false) }
    val reconnectMessage = stringResource(R.string.settings_snackbar_reconnect_message)
    val reconnectAction = stringResource(R.string.settings_snackbar_reconnect_action)
    // Pending-state prompt: shows once per recommendation, re-shows after a
    // process/config change only if the flag flips back to true.
    LaunchedEffect(ui.reconnectRecommended, reconnectMessage, reconnectAction) {
        if (ui.reconnectRecommended) {
            val result =
                snackbarHostState.showSnackbar(
                    message = reconnectMessage,
                    actionLabel = reconnectAction,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.reconnect()
        }
    }

    if (showHwidExplanation) {
        AlertDialog(
            onDismissRequest = { showHwidExplanation = false; pendingHwidAllow = false },
            title = { Text(stringResource(R.string.hwid_what)) },
            text = { Text(stringResource(R.string.hwid_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    if (pendingHwidAllow) viewModel.setHwidAllowed(true)
                    pendingHwidAllow = false
                    showHwidExplanation = false
                }) {
                    Text(stringResource(if (pendingHwidAllow) R.string.hwid_allow else R.string.common_done))
                }
            },
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
    ) {
        SettingsSection(R.string.settings_section_connection)
        SwitchRow(
            title = stringResource(R.string.settings_reconnect_on_change),
            subtitle = stringResource(R.string.settings_reconnect_on_change_sub),
            checked = ui.reconnectOnNetworkChange,
            onCheckedChange = viewModel::setReconnectOnNetworkChange,
        )
        SwitchRow(
            title = stringResource(R.string.settings_ipv6),
            subtitle = stringResource(R.string.settings_ipv6_sub),
            checked = ui.ipv6Enabled,
            onCheckedChange = viewModel::setIpv6Enabled,
        )
        SwitchRow(
            title = stringResource(R.string.settings_doze),
            subtitle = stringResource(R.string.settings_doze_sub),
            checked = ui.dozePowerSave,
            onCheckedChange = viewModel::setDozePowerSave,
        )
        SwitchRow(
            title = stringResource(R.string.settings_connect_on_launch),
            subtitle = stringResource(R.string.settings_connect_on_launch_sub),
            checked = ui.autoConnectOnLaunch,
            onCheckedChange = viewModel::setAutoConnectOnLaunch,
        )
        SwitchRow(
            title = stringResource(R.string.settings_extended_diagnostics),
            subtitle = stringResource(R.string.settings_extended_diagnostics_sub),
            checked = ui.extendedDiagnostics,
            onCheckedChange = viewModel::setExtendedDiagnostics,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRouting)
                    .heightIn(min = AfterglowTokens.touchTarget)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.routing_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_routing_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NavChevron()
        }
        AlwaysOnRow()
        BatteryRow()
        SettingsSection(R.string.settings_section_subscriptions)
        SwitchRow(
            title = stringResource(R.string.hwid_send),
            subtitle = stringResource(
                if (ui.hwidConsent == HwidConsent.Allowed) R.string.hwid_what
                else R.string.hwid_sending_off,
            ),
            checked = ui.hwidConsent == HwidConsent.Allowed,
            onCheckedChange = { allowed ->
                if (allowed && ui.hwidConsent == HwidConsent.Unset) {
                    pendingHwidAllow = true
                    showHwidExplanation = true
                } else viewModel.setHwidAllowed(allowed)
            },
        )
        TextButton(onClick = { showHwidExplanation = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.hwid_what))
        }
        SwitchRow(
            title = stringResource(R.string.settings_auto_refresh),
            subtitle =
                if (ui.autoRefreshEnabled) {
                    if (ui.autoRefreshMinutes > 0) {
                        // Periodic work can't run faster than the platform floor.
                        stringResource(
                            R.string.settings_auto_refresh_every,
                            maxOf(ui.autoRefreshMinutes, 15),
                        )
                    } else {
                        stringResource(R.string.settings_auto_refresh_provider)
                    }
                } else {
                    stringResource(R.string.settings_auto_refresh_manual)
                },
            checked = ui.autoRefreshEnabled,
            onCheckedChange = viewModel::setAutoRefreshEnabled,
        )
        if (ui.autoRefreshEnabled) {
            AutoRefreshIntervalRow(
                minutes = ui.autoRefreshMinutes,
                onMinutes = viewModel::setAutoRefreshMinutes,
            )
        }
        // This is the default each subscription inherits — per-subscription
        // policies in the detail sheet can override it.
        Text(
            text = stringResource(R.string.settings_auto_refresh_default_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        SettingsSection(R.string.settings_section_app)
        ThemeModeRow(
            mode = ui.themeMode,
            onSelect = viewModel::setThemeMode,
        )
        LanguageRow(
            language = ui.appLanguage,
            onSelect = viewModel::setAppLanguage,
        )
        if (ui.dynamicColorAvailable) {
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_sub),
                checked = ui.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }
        NotificationPermissionRow()

        SettingsSection(R.string.diag_title)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDiagnostics)
                    .heightIn(min = AfterglowTokens.touchTarget)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.diag_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_diagnostics_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NavChevron()
        }

        AboutSection()
    }
}

/** Afterglow section header (same style as Home), with uniform spacing. */
@Composable
private fun SettingsSection(@StringRes title: Int) {
    SectionHeader(
        stringResource(title),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Same row shape as its neighbours; opens system battery settings on click only. */
@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { unavailable = !openBatterySettings(context) }
                .heightIn(min = AfterglowTokens.touchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.battery_hint_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.battery_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unavailable) {
                Text(
                    stringResource(R.string.battery_hint_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NavChevron()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = AfterglowTokens.rowHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Theme picker — applies live on selection (no reconnect, no restart). */
@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .heightIn(min = AfterglowTokens.touchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            Text(
                themeModeLabel(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavChevron()
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == mode,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Text(
                                themeModeLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.System -> stringResource(R.string.theme_system)
        ThemeMode.Light -> stringResource(R.string.theme_light)
        ThemeMode.Dark -> stringResource(R.string.theme_dark)
    }

/** Language picker — persisted via DataStore; applied by the platform on
 *  API 33+ (system per-app language) and by an activity-recreate below.
 *  The VPN service is never restarted for this. */
@Composable
private fun LanguageRow(
    language: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .heightIn(min = AfterglowTokens.touchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            Text(
                languageLabel(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavChevron()
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == language,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == language, onClick = null)
                            Text(
                                languageLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String =
    when (language) {
        AppLanguage.System -> stringResource(R.string.language_system)
        AppLanguage.English -> stringResource(R.string.language_english)
        AppLanguage.Russian -> stringResource(R.string.language_russian)
    }

/** Optional user interval override; empty field = follow the provider hint.
 *  Committed on IME Done — per-keystroke writes would re-enqueue all work. */
@Composable
private fun AutoRefreshIntervalRow(
    minutes: Int,
    onMinutes: (Int) -> Unit,
) {
    var text by remember {
        mutableStateOf(TextFieldValue(if (minutes > 0) minutes.toString() else ""))
    }
    var focused by remember { mutableStateOf(false) }
    // Adopt the persisted value once it loads — but never while the user is
    // typing, so an in-progress edit isn't clobbered by a DataStore round-trip.
    LaunchedEffect(minutes, focused) {
        if (!focused) {
            val persisted = if (minutes > 0) minutes.toString() else ""
            if (text.text != persisted) text = TextFieldValue(persisted)
        }
    }
    val focusManager = LocalFocusManager.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val digits = input.text.filter { it.isDigit() }
                text = input.copy(text = digits)
            },
            label = { Text(stringResource(R.string.settings_interval_label)) },
            placeholder = { Text(stringResource(R.string.settings_interval_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        onMinutes(text.text.toIntOrNull() ?: 0)
                        focusManager.clearFocus()
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
        )
    }
}

/**
 * Real system always-on/connection-blocking status (VpnService.isAlwaysOn /
 * isLockdownEnabled on API 29+), refreshed whenever the screen resumes —
 * the user configures it in system VPN settings, so returning from that
 * activity must show the new value. Click still opens the system screen;
 * apps can't toggle these flags themselves.
 */
@Composable
private fun AlwaysOnRow() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readVpnSystemStatus(context)) }

    // Re-read when returning from the system VPN settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    status = readVpnSystemStatus(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val subtitle =
        when {
            !status.available -> {
                stringResource(R.string.settings_always_on_unavailable)
            }

            else -> {
                stringResource(
                    R.string.settings_always_on_status,
                    stringResource(
                        if (status.alwaysOn == true) R.string.common_enabled else R.string.common_disabled,
                    ),
                    // Lockdown is only meaningful when always-on is set — the system
                    // reports its raw flag, so surface what it actually says.
                    stringResource(
                        if (status.lockdownEnabled == true) {
                            R.string.common_enabled
                        } else {
                            R.string.common_disabled
                        },
                    ),
                )
            }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    // Apps can't enable lockdown themselves — the system VPN
                    // settings screen is the only supported path.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }.heightIn(min = AfterglowTokens.touchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_always_on), style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_always_on_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavChevron()
    }
}

/** Trailing affordance for rows that open a screen or a picker. */
@Composable
internal fun NavChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun InfoRow(
    title: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** POST_NOTIFICATIONS is a runtime permission on API 33+. */
@Composable
private fun NotificationPermissionRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current

    fun isGranted() =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }

    // Re-check when returning from the system permission dialog/settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { result -> granted = result }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (granted) {
                        R.string.settings_notifications_granted
                    } else {
                        R.string.settings_notifications_denied
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) {
            TextButton(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) {
                Text(stringResource(R.string.settings_notifications_allow))
            }
        }
    }
}
