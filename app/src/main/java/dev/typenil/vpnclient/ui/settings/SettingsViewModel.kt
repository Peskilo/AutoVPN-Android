package dev.typenil.vpnclient.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.common.AppLanguage
import dev.typenil.vpnclient.core.common.LocaleSupport
import dev.typenil.vpnclient.core.common.ThemeMode
import dev.typenil.vpnclient.core.subscription.HwidConsent
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val reconnectOnNetworkChange: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val dozePowerSave: Boolean = false,
    val extendedDiagnostics: Boolean = false,
    /** <0 = manual only, 0 = provider-driven, >0 = fixed minutes. */
    val autoRefreshMinutes: Int = 0,
    /** A compiled-in setting changed while a session is alive — the tunnel
     *  keeps its old config until reconnect. Pending state, not an event:
     *  survives recomposition and is cleared on accept/session end. */
    val reconnectRecommended: Boolean = false,
    /** Start the VPN automatically when the app opens. */
    val autoConnectOnLaunch: Boolean = false,
    /** Persisted UI theme — applies on recomposition, no VPN impact. */
    val themeMode: ThemeMode = ThemeMode.System,
    /** Monet wallpaper palette — Android 12+ only; inert below. */
    val dynamicColor: Boolean = false,
    /** Whether the dynamic-color toggle is meaningful on this device. */
    val dynamicColorAvailable: Boolean = false,
    /** Persisted UI language choice — applied by MainActivity. */
    val appLanguage: AppLanguage = AppLanguage.System,
    val hwidConsent: HwidConsent = HwidConsent.Unset,
) {
    val autoRefreshEnabled: Boolean get() = autoRefreshMinutes >= 0
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val connectionManager: ConnectionManager,
        @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    ) : ViewModel() {
        /** Pending "reconnect to apply" recommendation — set when a compiled-in
         *  setting actually changes while a session is alive; cleared when the
         *  user accepts the reconnect or the session leaves its active states. */
        private val reconnectRecommended = MutableStateFlow(false)

        val uiState: StateFlow<SettingsUiState> =
            combine(
                combine(
                    settings.reconnectOnNetworkChange,
                    settings.ipv6Enabled,
                    settings.dozePowerSave,
                    settings.autoRefreshMinutes,
                    settings.extendedDiagnostics,
                ) { reconnect, ipv6, doze, refreshMinutes, extendedDiagnostics ->
                    SettingsUiState(
                        reconnectOnNetworkChange = reconnect,
                        ipv6Enabled = ipv6,
                        dozePowerSave = doze,
                        extendedDiagnostics = extendedDiagnostics,
                        autoRefreshMinutes = refreshMinutes,
                    )
                },
                reconnectRecommended,
                settings.autoConnectOnLaunch,
                settings.themeMode,
                settings.dynamicColor,
                effectiveAppLanguage(),
                settings.hwidConsent,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val state = values[0] as SettingsUiState
                val recommended = values[1] as Boolean
                val autoConnect = values[2] as Boolean
                val themeMode = values[3] as ThemeMode
                val dynamicColor = values[4] as Boolean
                val appLanguage = values[5] as AppLanguage
                state.copy(
                    reconnectRecommended = recommended,
                    autoConnectOnLaunch = autoConnect,
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    appLanguage = appLanguage,
                    hwidConsent = values[6] as HwidConsent,
                    dynamicColorAvailable =
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SettingsUiState(),
            )

        init {
            // A dead session picks the new value up on the next connect — the
            // recommendation is only meaningful while a tunnel is alive.
            viewModelScope.launch {
                connectionManager.state.collect { state ->
                    if (!state.hasLiveConfig()) reconnectRecommended.value = false
                }
            }
        }

        /** The language the UI shows as selected — on 33+ the system
         *  per-app locale store is authoritative (a system Settings pick
         *  beats a stale DataStore value), below 33 our own tag is. */
        private fun effectiveAppLanguage(): kotlinx.coroutines.flow.Flow<AppLanguage> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                kotlinx.coroutines.flow.flow {
                    // LocaleManager has no listener — poll cheaply so a
                    // system-level change while the screen is open reflects.
                    while (true) {
                        emit(
                            dev.typenil.vpnclient.core.common.AppLanguage.fromTag(
                                dev.typenil.vpnclient.core.common.LocaleSupport
                                    .currentSystemTag(appContext),
                            ),
                        )
                        kotlinx.coroutines.delay(1_000)
                    }
                }
            } else {
                settings.appLanguage
            }

        fun setHwidAllowed(allowed: Boolean) {
            viewModelScope.launch {
                settings.setHwidConsent(if (allowed) HwidConsent.Allowed else HwidConsent.Denied)
            }
        }

        fun setReconnectOnNetworkChange(enabled: Boolean) {
            viewModelScope.launch { settings.setReconnectOnNetworkChange(enabled) }
        }

        /** IPv6 is baked into the TUN addresses + DNS strategy at compile time —
         *  a live tunnel keeps its old setup until reconnect. */
        fun setIpv6Enabled(enabled: Boolean) {
            viewModelScope.launch {
                val changed = settings.ipv6Enabled.first() != enabled
                settings.setIpv6Enabled(enabled)
                if (changed) recommendReconnectIfSessionActive()
            }
        }

        fun setDozePowerSave(enabled: Boolean) {
            viewModelScope.launch { settings.setDozePowerSave(enabled) }
        }

        fun setExtendedDiagnostics(enabled: Boolean) {
            viewModelScope.launch { settings.setExtendedDiagnostics(enabled) }
        }

        fun setAutoConnectOnLaunch(enabled: Boolean) {
            viewModelScope.launch { settings.setAutoConnectOnLaunch(enabled) }
        }

        /** UI-only preference — the running tunnel never needs a rebuild. */
        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settings.setThemeMode(mode) }
        }

        fun setDynamicColor(enabled: Boolean) {
            viewModelScope.launch { settings.setDynamicColor(enabled) }
        }

        /** On 33+ write straight to the system per-app locale store — that's
         *  the authoritative surface (Android Settings → App info → Language
         *  reads/writes the same slot), so an in-app pick and an OS pick land
         *  on the same value. Below 33 the SharedPreferences tag is the only
         *  store, so DataStore stays the source of truth there. Either way the
         *  write is fire-and-forget; MainActivity reads the effective locale. */
        fun setAppLanguage(language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LocaleSupport.applyToSystem(appContext, language.tag)
            } else {
                viewModelScope.launch { settings.setAppLanguage(language) }
            }
        }

        fun setAutoRefreshEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setAutoRefreshEnabled(enabled) }
        }

        /** Persist a user override interval; 0/blank falls back to the provider hint. */
        fun setAutoRefreshMinutes(minutes: Int) {
            viewModelScope.launch { settings.setAutoRefreshMinutes(minutes.coerceAtLeast(0)) }
        }

        /** Restart the tunnel so compiled-in settings take effect. */
        fun reconnect() {
            reconnectRecommended.value = false
            viewModelScope.launch { connectionManager.reconnect() }
        }

        /** Only recommend while a session is alive — an idle tunnel picks the new
         *  value up on the next connect, and a dying one is already gone. */
        private fun recommendReconnectIfSessionActive() {
            if (connectionManager.state.value.hasLiveConfig()) {
                reconnectRecommended.value = true
            }
        }

        /** States where the running session holds a compiled config a settings
         *  change can't reach. */
        private fun VpnConnectionState.hasLiveConfig(): Boolean =
            when (this) {
                is VpnConnectionState.Connected,
                is VpnConnectionState.Connecting,
                is VpnConnectionState.Reconnecting,
                is VpnConnectionState.Preparing,
                is VpnConnectionState.PermissionRequired,
                -> true

                else -> false
            }
    }
