package dev.typenil.vpnclient.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.disclosureSemantics
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.StateTone
import dev.typenil.vpnclient.ui.theme.stateColors
import kotlinx.coroutines.delay
import java.time.Instant

internal enum class VisualState { Offline, Preparing, Permission, Connecting, Connected, Reconnecting, Stopping, Error }

/** Disconnected is neutral, in-flight states are warm, Connected is calm, only Error is red. */
internal fun VisualState.tone() = when (this) {
    VisualState.Offline, VisualState.Stopping -> StateTone.Neutral
    VisualState.Preparing, VisualState.Permission, VisualState.Connecting, VisualState.Reconnecting -> StateTone.Progress
    VisualState.Connected -> StateTone.Positive
    VisualState.Error -> StateTone.Error
}

/** A Connected hero with a failed check turns amber (not red: the tunnel itself is up). */
internal fun heroTone(state: VisualState, hint: HomeHealthHint) =
    if (state == VisualState.Connected && hint != HomeHealthHint.None) StateTone.Progress else state.tone()

internal fun frameTimeNanos(lastTickNanos: Long, measuredNanos: Long): Long = maxOf(lastTickNanos, measuredNanos)

private fun VpnConnectionState.visual() = when (this) {
    VpnConnectionState.Idle -> VisualState.Offline
    is VpnConnectionState.Preparing -> VisualState.Preparing
    VpnConnectionState.PermissionRequired -> VisualState.Permission
    is VpnConnectionState.Connecting -> VisualState.Connecting
    is VpnConnectionState.Connected -> VisualState.Connected
    is VpnConnectionState.Reconnecting -> VisualState.Reconnecting
    VpnConnectionState.Stopping -> VisualState.Stopping
    is VpnConnectionState.Error -> VisualState.Error
}

/**
 * The base-form source uses a flat green matte. Remove that matte from the
 * local bitmap so the hero remains clean over the app's light background.
 */
private fun removeGreenMatte(source: Bitmap): Bitmap {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        if (green > 40 && green > red * 1.13 && green > blue * 1.06 && green - red > 10) {
            pixels[i] = android.graphics.Color.TRANSPARENT
        }
    }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return bitmap
}

/**
 * Uses bundled art only: no remote image requests and no chance for one form
 * to accidentally fall back to the other form.
 */
@Composable
private fun GokuArtwork(isSuperSaiyan: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = isSuperSaiyan) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resourceId = if (isSuperSaiyan) R.drawable.goku_ssj else R.drawable.goku_base
                val decoded = BitmapFactory.decodeResource(context.resources, resourceId)
                    ?: return@runCatching null
                (if (isSuperSaiyan) decoded else removeGreenMatte(decoded)).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = if (isSuperSaiyan) "Goku Super Saiyan" else "Goku",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_sayan_mark),
                contentDescription = if (isSuperSaiyan) "Goku Super Saiyan" else "Goku",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.62f),
            )
        }
    }
}

@Composable
internal fun AfterglowHomeContent(
    ui: HomeUiState,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPick: () -> Unit,
    onAddServer: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenServers: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDismissGuard: () -> Unit,
    errorMessage: String?,
    onDismissNotice: (() -> Unit)? = null,
    batteryHint: @Composable () -> Unit = {},
) {
    val state = ui.connection
    val connected = state is VpnConnectionState.Connected
    val busy = state is VpnConnectionState.Preparing ||
        state is VpnConnectionState.Connecting ||
        state is VpnConnectionState.Reconnecting ||
        state is VpnConnectionState.Stopping ||
        state == VpnConnectionState.PermissionRequired
    val noServers = ui.serverOptions.isEmpty() &&
        (state is VpnConnectionState.Idle || state is VpnConnectionState.Error)
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    val accent = if (connected) Color(0xFF2BAE57) else Color(0xFF4C8DFF)
    val cardShape = RoundedCornerShape(22.dp)

    Box(modifier.fillMaxSize().background(Color(0xFFFAFBFD))) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .padding(bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_sayan_mark),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "sayanvpn",
                    color = Color(0xFF10285A),
                    fontSize = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.7).sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "+",
                    color = Color(0xFF17325F),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .clickable { if (noServers) onAddServer() else onOpenServers() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE9EDF6), cardShape)
                    .clickable { onPick() }
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🌐", fontSize = 28.sp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ui.selectedNodeName ?: stringResource(R.string.afterglow_selected_server),
                        color = Color(0xFF17191F),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ui.selectedNodeProtocol ?: stringResource(R.string.afterglow_no_usable_servers),
                        color = Color(0xFF697184),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("⌄", fontSize = 22.sp, color = Color(0xFF455064))
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp)
                        .clickable(enabled = !busy && !noServers) {
                            if (connected) onDisconnect() else onConnect()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    GokuArtwork(
                        isSuperSaiyan = connected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when {
                        connected -> stringResource(R.string.afterglow_online)
                        state is VpnConnectionState.Error -> stringResource(R.string.afterglow_offline)
                        busy -> stringResource(R.string.afterglow_status_reconnecting)
                        noServers -> stringResource(R.string.afterglow_no_servers_headline)
                        else -> stringResource(R.string.afterglow_offline)
                    },
                    color = Color(0xFF15171C),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ϟ", fontSize = 21.sp, color = if (connected) Color(0xFF2BAE57) else Color(0xFF8B94A5))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (connected) ui.selectedDelayMs?.let { "$it ms" } ?: "— ms" else "— ms",
                        fontSize = 17.sp,
                        color = if (connected) Color(0xFF17191F) else Color(0xFF8B94A5),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "↻",
                        fontSize = 19.sp,
                        color = if (connected) Color(0xFF657084) else Color(0xFFB0B6C1),
                        modifier = Modifier.clickable(enabled = connected) { onOpenDiagnostics() }.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(cardShape).background(Color(0xFF2F6FED))
                        .clickable(enabled = noServers || !busy) {
                            when {
                                noServers -> onAddServer()
                                connected -> onDisconnect()
                                else -> onConnect()
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            noServers -> stringResource(R.string.common_add_server_cta)
                            connected -> stringResource(R.string.action_disconnect)
                            state is VpnConnectionState.Error -> stringResource(R.string.afterglow_retry)
                            else -> stringResource(R.string.afterglow_start)
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (errorMessage != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFE9EC)).clickable {
                            if (onDismissNotice != null) onDismissNotice() else onOpenDiagnostics()
                        }.padding(14.dp),
                ) {
                    Text(errorMessage, color = Color(0xFF9E2638), modifier = Modifier.weight(1f))
                    Text("›", color = Color(0xFF9E2638), fontSize = 22.sp)
                }
            }
            if (state == VpnConnectionState.PermissionRequired) {
                Text(stringResource(R.string.afterglow_permission_explanation), color = Color(0xFF697184), fontSize = 14.sp)
            }
            if (ui.restartGuardTripped) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFF4D8))
                        .clickable { onDismissGuard() }.padding(14.dp),
                ) {
                    Text(stringResource(R.string.home_restart_guard_text), color = Color(0xFF73510B))
                }
            }
            batteryHint()

            Column(
                Modifier.fillMaxWidth().clip(cardShape).background(Color.White)
                    .border(1.dp, Color(0xFFE9EDF6), cardShape),
            ) {
                PingwinStyleRow("⇄", Color(0xFF2F6FED), Color(0xFFEAF1FF),
                    stringResource(R.string.afterglow_routing_title), stringResource(R.string.afterglow_quick_access), onOpenRouting)
                Box(Modifier.fillMaxWidth().padding(start = 69.dp, end = 16.dp).height(1.dp).background(Color(0xFFE9ECF1)))
                PingwinStyleRow("◎", Color(0xFF2BAE57), Color(0xFFEAF8EF),
                    stringResource(R.string.nav_servers), stringResource(R.string.afterglow_servers_available, ui.serverOptions.size), onOpenServers)
                Box(Modifier.fillMaxWidth().padding(start = 69.dp, end = 16.dp).height(1.dp).background(Color(0xFFE9ECF1)))
                PingwinStyleRow("▤", Color(0xFF2F6FED), Color(0xFFEAF1FF),
                    stringResource(R.string.nav_subscriptions), stringResource(R.string.afterglow_subscriptions_enabled, ui.enabledSubscriptionCount), onOpenSubscriptions)
            }

            if (state is VpnConnectionState.Connected) {
                Column(
                    Modifier.fillMaxWidth().clip(cardShape).background(Color.White)
                        .border(1.dp, Color(0xFFE9EDF6), cardShape).padding(16.dp),
                ) {
                    Text(
                        stringResource(R.string.afterglow_session_elapsed, uptimeText(state.since, Instant.now())),
                        color = Color(0xFF17191F), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(if (statsExpanded) R.string.home_stats_hide else R.string.home_stats_show),
                        color = Color(0xFF2F6FED), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { statsExpanded = !statsExpanded }.padding(vertical = 6.dp),
                    )
                    if (statsExpanded) {
                        val stats = state.stats
                        if (stats == null) {
                            Text(stringResource(R.string.afterglow_stats_unavailable), color = Color(0xFF697184))
                        } else {
                            Text(
                                stringResource(R.string.home_stat_connections),
                                color = Color(0xFF17191F), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().clickable { onOpenConnections() }.padding(vertical = 10.dp),
                            )
                            Text(stringResource(R.string.home_stat_download) + ": " + formatRate(stats.downlinkBytesPerSec), color = Color(0xFF697184))
                            Text(stringResource(R.string.home_stat_upload) + ": " + formatRate(stats.uplinkBytesPerSec), color = Color(0xFF697184))
                            Text(stringResource(R.string.home_stat_total_suffix, formatBytes(stats.uplinkTotalBytes + stats.downlinkTotalBytes)), color = Color(0xFF697184))
                        }
                    }
                    Text(
                        stringResource(R.string.afterglow_open_diagnostics),
                        color = Color(0xFF2F6FED), modifier = Modifier.clickable { onOpenDetails() }.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().widthIn(max = 560.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFE9EDF6), RoundedCornerShape(26.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFE9EEFF)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⌂", fontSize = 22.sp, color = Color(0xFF2450C8))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nav_home), color = Color(0xFF2450C8), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .clickable { onOpenSettings() }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF555B67))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nav_settings), color = Color(0xFF555B67), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PingwinStyleRow(
    symbol: String,
    symbolColor: Color,
    symbolBackground: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(symbolBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = symbolColor, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFF17191F), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color(0xFF697184), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("›", color = Color(0xFF455064), fontSize = 26.sp)
    }
}

/** Wide screens center the editorial column instead of stretching it. */
private val HomeContentMaxWidth = 560.dp

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = AfterglowTheme.colors.coral, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 1.sp, modifier = modifier.semantics { heading() })
}

@Composable
private fun ShortcutCard(label: Int, caption: String, server: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val colors = AfterglowTheme.colors
    Column(modifier.heightIn(min = 92.dp).border(1.dp, colors.border)
        .background(colors.paperSecondary)
        .semantics { role = Role.Button }
        .clickable(onClick = onClick)
        .padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (server) ServerListBadge() else SubscriptionBadge()
            CircledChevron()
        }
        Column {
            Text(stringResource(label), color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(caption, color = colors.muted, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ConnectionHero(
    state: VisualState, noServers: Boolean, delayMs: Int? = null,
    healthHint: HomeHealthHint = HomeHealthHint.None, staleMinutes: Int? = null,
) {
    val colors = AfterglowTheme.colors
    val connected = state == VisualState.Connected
    val hint = if (connected) healthHint else HomeHealthHint.None
    val stateColors = colors.stateColors(heroTone(state, hint))
    val target = stateColors.hero
    // Token tween, not the default spring — hero color shifts are state
    // feedback, not physics; the standard duration matches the other state
    // colors (accent, action surface) so the whole surface lands together.
    val base by animateColorAsState(target, animationSpec = tween(AfterglowTokens.motionStandard),
        label = "Hero status")
    val headline = stringResource(when {
        noServers && state == VisualState.Offline -> R.string.afterglow_no_servers_headline
        else -> when (state) {
            VisualState.Offline -> R.string.home_status_disconnected
            VisualState.Connected -> R.string.home_status_connected
            VisualState.Error -> R.string.home_status_failed
            VisualState.Reconnecting -> R.string.afterglow_status_reconnecting
            VisualState.Preparing -> R.string.home_status_preparing
            VisualState.Permission -> R.string.afterglow_permission
            VisualState.Connecting -> R.string.home_status_connecting
            VisualState.Stopping -> R.string.home_status_disconnecting
        }
    })
    val fontScale = LocalDensity.current.fontScale
    val largeFont = fontScale > 1.3f
    // Landscape keeps a working viewport — compact hero, same type sizes.
    val heroMin = if (isShortWindow(LocalConfiguration.current.screenHeightDp)) 176f else 238f
    val heroMinEff = if (largeFont) heroMin + (fontScale - 1.3f) * 85f else heroMin
    val artScale = heroArtScale(fontScale)
    // The art is as tall as the hero, which grows with wrapped text at large fonts — the hint cap
    // follows the measured height (taller hero -> narrower cap -> at most more lines, bounded by the 40% floor, so it settles).
    var heroHeightPx by remember { mutableIntStateOf(0) }
    val heroHeightDp = maxOf(heroMinEff, with(LocalDensity.current) { heroHeightPx.toDp().value })
    Box(Modifier.fillMaxWidth().heightIn(min = heroMinEff.dp).onSizeChanged { heroHeightPx = it.height }
        .clip(AfterglowTokens.cardShape).background(Brush.horizontalGradient(listOf(base, base, colors.actionSurface)))) {
        Spacer(Modifier.fillMaxWidth().height(heroMin.dp))
        // Decorative artwork never participates in the hero's measurement.
        Box(Modifier.matchParentSize()) {
            GokuArtwork(isSuperSaiyan = connected,
                modifier = Modifier.fillMaxHeight(artScale).aspectRatio(1f)
                    .align(Alignment.BottomEnd).offset(x = 18.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(
                            0f to Color.Transparent, .45f to Color.Black, 1f to Color.Black,
                        ), blendMode = BlendMode.DstIn)
                    })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to base, .3f to base.copy(alpha = .92f), .7f to Color.Transparent)))
        // Width is measured so the hint lines can stop short of the mascot face
        // (it hangs off the bottom-right) instead of sliding under it.
        BoxWithConstraints(Modifier.align(Alignment.CenterStart)) {
            val hintCap = heroHintMaxWidthDp(maxWidth.value, heroHeightDp, artScale).dp
            Column(Modifier.fillMaxWidth(if (largeFont) .82f else .72f)
                .padding(start = 18.dp, end = 8.dp, top = 20.dp, bottom = 20.dp)) {
                Text(stringResource(R.string.afterglow_your_connection),
                    color = stateColors.onHeroMuted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
                // Only the state headline announces changes — never the latency/stats.
                // The hint is read as part of the title (one Polite announcement, only when the hint
                // changes); its own node is hidden so it is not spoken twice.
                val hintText = hint.textRes()?.let { stringResource(it) }
                Text(headline, color = stateColors.onHero, fontWeight = FontWeight.Bold,
                    fontSize = 28.sp, lineHeight = 33.sp,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (hintText != null) contentDescription = "$headline. $hintText"
                    })
                if (hintText != null) {
                    Text(hintText, color = stateColors.onHero, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = hintCap).clearAndSetSemantics {})
                }
                HeroLatency(delayMs, stateColors.onHero, Modifier.padding(top = 8.dp))
                if (connected && staleMinutes != null && (delayMs == null || delayMs <= 0)) {
                    Text(stringResource(R.string.home_latency_outdated, staleMinutes),
                        color = stateColors.onHeroMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = hintCap))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)
            .background(stateColors.line))
    }
}

@Composable
private fun ConnectionAction(state: VisualState, noServers: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit,
    modifier: Modifier = Modifier, hasNodes: Boolean = false) {
    val colors = AfterglowTheme.colors
    val active = state == VisualState.Connected || state == VisualState.Reconnecting
    // Preparing is the only pre-service phase that can be cancelled: the
    // manager aborts the compile and never starts the tunnel.
    val cancellable = state == VisualState.Preparing
    val enabled = noServers || active || cancellable || state == VisualState.Offline || state == VisualState.Error
    val label = when {
        noServers -> stringResource(if (hasNodes) R.string.nav_servers else R.string.common_add_server_cta)
        active -> stringResource(R.string.action_disconnect)
        state == VisualState.Offline -> stringResource(R.string.afterglow_start)
        state == VisualState.Error -> stringResource(R.string.afterglow_retry)
        state == VisualState.Stopping -> stringResource(R.string.home_status_disconnecting)
        state == VisualState.Permission -> stringResource(R.string.home_status_permission)
        cancellable -> stringResource(R.string.common_cancel)
        else -> stringResource(R.string.home_status_connecting)
    }
    val accent = colors.stateColors(state.tone()).accent
    // Subtitle only for stable states; transitional states already name the
    // action in the title, so a static "VPN OFF/ON" subtitle would misreport.
    val subtitle = when {
        noServers -> null // hero + card already say it; the title is the action
        state == VisualState.Connected -> stringResource(R.string.afterglow_online)
        state == VisualState.Offline -> stringResource(R.string.afterglow_offline)
        else -> null
    }
    val tint = colors.stateColors(state.tone()).hero
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .98f else 1f,
        animationSpec = tween(AfterglowTokens.motionFast), label = "Connection press")
    val animatedAccent by animateColorAsState(accent, animationSpec = tween(AfterglowTokens.motionStandard),
        label = "Connection accent")
    val gradientEnd by animateColorAsState(lerp(colors.actionSurface, tint, .4f),
        animationSpec = tween(AfterglowTokens.motionEmphasized), label = "Connection surface")
    // Decorative mascot earns its pad only on tall-enough screens; on
    // landscape/short heights it leaves so the CTA keeps usable viewport.
    val shortWindow = isShortWindow(LocalConfiguration.current.screenHeightDp)
    val showMascot = !shortWindow
    Box(modifier.fillMaxWidth().padding(top = if (showMascot) 64.dp else 0.dp)
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(colors.actionSurface, gradientEnd)))
            .border(1.dp, colors.onActionSurface.copy(alpha = .18f), RoundedCornerShape(12.dp))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                onClick = if ((active || cancellable) && !noServers) onDisconnect else onConnect)
            .heightIn(min = if (shortWindow) 64.dp else 84.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (shortWindow) 44.dp else 52.dp).clip(RoundedCornerShape(8.dp)).background(animatedAccent),
                contentAlignment = Alignment.Center) {
                if (noServers) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = colors.actionSurface, modifier = Modifier.size(26.dp))
                else Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.5.dp.toPx()
                    drawArc(colors.actionSurface, -45f, 270f, false,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    drawLine(colors.actionSurface, Offset(size.width / 2, size.height * .06f),
                        Offset(size.width / 2, size.height * .53f), stroke, cap = StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                // Localized labels grow with font scale — three lines keeps a
                // long CTA honest without hiding it behind the mascot strip.
                Text(label, color = colors.onActionSurface, fontWeight = FontWeight.Black, fontSize = 19.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(subtitle ?: "\u00A0", color = colors.onActionSurface.copy(alpha = .72f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        // Transparent sprite padding lets the paws rest just inside the edge;
        // the label column still owns the row — no in-row mascot spacer.
        if (showMascot) {
            GokuArtwork(isSuperSaiyan = state == VisualState.Connected,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 7.dp).offset(y = (-52).dp)
                    .size(width = 72.dp, height = 82.dp))
        }
    }
}

@Composable
private fun HeroLatency(delayMs: Int?, onHero: Color, modifier: Modifier = Modifier) {
    if (delayMs == null || delayMs <= 0) return
    val colors = AfterglowTheme.colors
    // Match LatencyBadge semantics: <800 = healthy, >=800 = degraded. Use amber
    // for degraded so the dot stays visible on the red transitional/error heroes.
    val tone = if (delayMs < 800) colors.jade else colors.amber
    Row(modifier.clip(RoundedCornerShape(20.dp))
        .background(onHero.copy(alpha = .14f))
        .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        Text(stringResource(R.string.afterglow_measured_latency, delayMs),
            color = onHero, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SubscriptionBadge() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val outline = Path().apply {
                moveTo(size.width * .2f, size.height * .1f)
                lineTo(size.width * .68f, size.height * .1f)
                lineTo(size.width * .82f, size.height * .26f)
                lineTo(size.width * .82f, size.height * .9f)
                lineTo(size.width * .2f, size.height * .9f)
                close()
            }
            drawPath(outline, colors.coral, style = stroke)
            drawLine(colors.coral, Offset(size.width * .32f, size.height * .52f),
                Offset(size.width * .68f, size.height * .52f), strokeWidth = stroke.width)
            drawLine(colors.coral, Offset(size.width * .32f, size.height * .7f),
                Offset(size.width * .6f, size.height * .7f), strokeWidth = stroke.width)
        }
    }
}

@Composable
private fun ServerListBadge() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            // Stacked server rows — the list affordance for the Servers screen.
            val stroke = 1.9.dp.toPx()
            listOf(.28f, .5f, .72f).forEach { y ->
                drawLine(colors.coral, Offset(size.width * .2f, size.height * y),
                    Offset(size.width * .8f, size.height * y), strokeWidth = stroke,
                    cap = StrokeCap.Round)
                drawCircle(colors.coral, stroke * .8f, Offset(size.width * .2f, size.height * y))
            }
        }
    }
}

@Composable
private fun ServerBadge(modifier: Modifier = Modifier) {
    val colors = AfterglowTheme.colors
    Box(modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            // Globe: outer circle + two meridians + equator. Distinct from the
            // horizontal-stack "server list" glyph used on the shortcut card.
            val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - stroke.width
            drawCircle(colors.coral, r, c, style = stroke)
            drawOval(colors.coral, topLeft = Offset(c.x - r * .45f, c.y - r),
                size = androidx.compose.ui.geometry.Size(r * .9f, r * 2f), style = stroke)
            drawLine(colors.coral, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke.width)
        }
    }
}

@Composable
private fun CircledChevron() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(32.dp).border(1.dp, colors.border, CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.coral, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ServerAndRouting(ui: HomeUiState, onPick: () -> Unit, onAdd: () -> Unit, onRouting: () -> Unit) {
    val colors = AfterglowTheme.colors
    val noServers = ui.serverOptions.isEmpty() && (ui.connection is VpnConnectionState.Idle || ui.connection is VpnConnectionState.Error)
    val live = when (val state = ui.connection) {
        is VpnConnectionState.Connected -> state.node
        is VpnConnectionState.Connecting -> state.node
        is VpnConnectionState.Preparing -> state.node
        is VpnConnectionState.Reconnecting -> state.node
        else -> null
    }
    // A measured candidate is not necessarily the member used by the session.
    val autoResolved = if (ui.autoSelected) {
        (ui.connection as? VpnConnectionState.Connected)?.node?.name
            ?.substringAfter("→", "")?.trim()?.takeIf { it.isNotBlank() }
    } else null
    val title = when {
        // The hero already says "No servers"; the card carries the one action hint.
        noServers -> stringResource(
            if (ui.noNodesAtAll) R.string.common_add_servers_hint else R.string.afterglow_no_usable_servers,
        )
        autoResolved != null -> autoResolved
        ui.autoSelected -> stringResource(R.string.home_auto_subtitle)
        else -> ui.selectedNodeName ?: live?.name ?: stringResource(R.string.home_no_server_selected)
    }
    val protocolLabel = if (ui.autoSelected) null else (ui.selectedNodeProtocol ?: live?.protocol?.label)
        ?.takeIf { it.isNotBlank() && !it.equals("Other", ignoreCase = true) }
    val card = Modifier.fillMaxWidth().clip(AfterglowTokens.cardShape)
        .border(1.dp, colors.border, AfterglowTokens.cardShape).background(colors.paperSecondary)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(card.semantics { role = Role.Button }
            .clickable(onClick = if (noServers) onAdd else onPick)
            .heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerBadge()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(stringResource(R.string.afterglow_selected_server), color = colors.muted,
                        fontSize = 11.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                    val modeLabel = if (ui.autoSelected) stringResource(R.string.afterglow_auto) else protocolLabel
                    if (!noServers && modeLabel != null) Text(modeLabel,
                        color = colors.coral, fontSize = 11.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium)
                }
                Text(title, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            CircledChevron()
        }
        Row(card.semantics { role = Role.Button }
            .clickable(onClick = onRouting).heightIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Symmetrical rounded square badge matching the two-line text block height.
            Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val w = size.width
                    val h = size.height
                    val topPath = Path().apply {
                        // Stem from mid-left going up then turning right
                        moveTo(w * 0.18f, h * 0.55f)
                        lineTo(w * 0.18f, h * 0.32f)
                        quadraticTo(w * 0.18f, h * 0.18f, w * 0.36f, h * 0.18f)
                        lineTo(w * 0.82f, h * 0.18f)
                        // Arrowhead pointing right
                        moveTo(w * 0.64f, h * 0.05f)
                        lineTo(w * 0.84f, h * 0.18f)
                        lineTo(w * 0.64f, h * 0.31f)
                    }
                    drawPath(topPath, colors.coral, style = stroke)

                    val bottomPath = Path().apply {
                        // Stem from mid-right going down then turning left
                        moveTo(w * 0.82f, h * 0.45f)
                        lineTo(w * 0.82f, h * 0.68f)
                        quadraticTo(w * 0.82f, h * 0.82f, w * 0.64f, h * 0.82f)
                        lineTo(w * 0.18f, h * 0.82f)
                        // Arrowhead pointing left
                        moveTo(w * 0.36f, h * 0.69f)
                        lineTo(w * 0.16f, h * 0.82f)
                        lineTo(w * 0.36f, h * 0.95f)
                    }
                    drawPath(bottomPath, colors.coral, style = stroke)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.afterglow_routing_title).uppercase(),
                    color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Text(ui.routingMode?.let { routeModeSummary(it) } ?: stringResource(R.string.common_none),
                    color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 2.dp))
                if (ui.routingPending) Text(stringResource(R.string.afterglow_pending), color = colors.coral, fontSize = 12.sp)
            }
            CircledChevron()
        }
    }
}

@Composable
private fun SessionStats(
    state: VpnConnectionState.Connected,
    onOpenConnections: () -> Unit,
    onOpenDetails: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = AfterglowTheme.colors
    val history = remember(state.since) { TrafficHistory() }
    var samples by remember(state.since) { mutableStateOf<List<TrafficHistory.Point>>(emptyList()) }
    var now by remember(state.since) { mutableStateOf(Instant.now()) }
    var nowNanos by remember(state.since) { mutableLongStateOf(System.nanoTime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, state.since) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); nowNanos = System.nanoTime(); delay(1000) }
        }
    }
    val stats = state.stats
    LaunchedEffect(state.since, state.statsReceivedAtNanos) {
        if (stats != null) {
            history.observe(state.statsReceivedAtNanos, stats.downlinkBytesPerSec, stats.uplinkBytesPerSec)
            samples = history.recent(System.nanoTime())
        }
    }
    // The 1 Hz ticker may predate a just-arrived snapshot. Read the monotonic
    // clock for this frame so a negative age cannot flash "unavailable".
    val displayNanos = frameTimeNanos(nowNanos, System.nanoTime())
    val fresh = stats != null && history.isFresh(displayNanos, state.statsReceivedAtNanos)
    val visibleSamples = samples.filter { it.atNanos in (displayNanos - TrafficHistory.WINDOW_NANOS)..displayNanos }
    val compact = LocalDensity.current.fontScale > 1.3f ||
        LocalConfiguration.current.screenWidthDp < 340
    // Wraps instead of squeezing: at large fonts a day-scale uptime moves
    // under the heading rather than starving it.
    FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        SectionHeader(stringResource(R.string.afterglow_statistics))
        // Shared uptime formatter — explicit localized units, days included,
        // never a raw 52:30:50 clock.
        Text(stringResource(R.string.afterglow_session_elapsed, uptimeText(state.since, now)),
            color = colors.muted, fontSize = 12.sp)
    }
    val s = stats.takeIf { fresh }
    val connectionsDesc = if (s == null) "" else
        stringResource(R.string.home_stat_connections_desc, s.connectionsIn, s.connectionsOut)
    val chartSamples = if (fresh) visibleSamples else emptyList()
    Column(Modifier.fillMaxWidth().border(1.dp, colors.border).background(colors.paperSecondary)) {
        if (s == null) {
            // One compact state instead of two empty metric columns plus a
            // trailing explanation — same information, less noise.
            Text(stringResource(R.string.afterglow_stats_unavailable), color = colors.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        } else if (!expanded) {
            // Collapsed: the two live rates only (session time sits in the header);
            // charts, totals and technical rows stay behind "Details".
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RateSummary(stringResource(R.string.home_stat_download), s.downlinkBytesPerSec, true)
                RateSummary(stringResource(R.string.home_stat_upload), s.uplinkBytesPerSec, false)
            }
        } else if (compact) {
            // Side-by-side metrics can't survive large fonts/narrow
            // widths — stack them at full width instead.
            SpeedMetric(stringResource(R.string.home_stat_download), s.downlinkBytesPerSec,
                s.downlinkTotalBytes, chartSamples, displayNanos, true, colors.jade, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            SpeedMetric(stringResource(R.string.home_stat_upload), s.uplinkBytesPerSec,
                s.uplinkTotalBytes, chartSamples, displayNanos, false, colors.coral, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SpeedMetric(stringResource(R.string.home_stat_download), s.downlinkBytesPerSec,
                    s.downlinkTotalBytes, chartSamples, displayNanos, true, colors.jade, Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                SpeedMetric(stringResource(R.string.home_stat_upload), s.uplinkBytesPerSec,
                    s.uplinkTotalBytes, chartSamples, displayNanos, false, colors.coral, Modifier.weight(1f))
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
                .clickable(onClick = onOpenConnections).heightIn(min = 48.dp)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_stat_connections), color = colors.ink,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Light jade fails text contrast on paper — the direction
                // glyphs take ink/coral; jade stays on the chart accents.
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 10.dp).clearAndSetSemantics {
                        contentDescription = connectionsDesc
                    }) {
                    Text("↓", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(" ${s.connectionsIn}", color = colors.muted, fontSize = 13.sp)
                    Text("  ↑", color = colors.coral, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(" ${s.connectionsOut}", color = colors.muted, fontSize = 13.sp)
                }
                CircledChevron()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
                .clickable(onClick = onOpenDetails).heightIn(min = 48.dp)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_details_title), color = colors.ink,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CircledChevron()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        // Explicit toggle: the label names the action, the chevron shows direction.
        Row(Modifier.fillMaxWidth().disclosureSemantics(expanded)
            .clickable(onClick = onToggle).heightIn(min = 48.dp)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Chevron hugs the label (not the far edge): the bar mascot perches there.
            Text(stringResource(if (expanded) R.string.home_stats_hide else R.string.home_stats_show),
                color = colors.coral, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = colors.coral, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = if (expanded) -90f else 90f })
        }
    }
}

@Composable
private fun RateSummary(label: String, value: Long, download: Boolean) {
    val colors = AfterglowTheme.colors
    Row(verticalAlignment = Alignment.Bottom) {
        // Ink/coral glyphs — light jade text fails contrast on paper. The glyph is
        // decorative: the label beside it names the direction.
        Text(if (download) "↓" else "↑", color = if (download) colors.ink else colors.coral,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {}.padding(end = 6.dp, bottom = 2.dp))
        Column {
            Text(label, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatRate(value), color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpeedMetric(label: String, value: Long, totalBytes: Long, samples: List<TrafficHistory.Point>,
    nowNanos: Long, download: Boolean, lineColor: Color, modifier: Modifier) {
    val colors = AfterglowTheme.colors
    Column(modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ink/coral glyphs — light jade text fails contrast on paper.
            Text(if (download) "↓" else "↑", color = if (download) colors.ink else colors.coral,
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clearAndSetSemantics {}.padding(end = 4.dp))
            Text(label, color = colors.muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text(formatRate(value),
            color = colors.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        // Total moves under the rate — inline it squeezed the label column.
        Text(stringResource(R.string.home_stat_total_suffix, formatBytes(totalBytes)),
            color = colors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TrafficChart(samples, nowNanos, download, lineColor)
    }
}

@Composable
private fun TrafficChart(samples: List<TrafficHistory.Point>, nowNanos: Long, download: Boolean, lineColor: Color) {
    val colors = AfterglowTheme.colors
    var chartNanos by remember { mutableLongStateOf(nowNanos) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { chartNanos = System.nanoTime(); delay(100) }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp)) {
            val baseline = size.height - 1.dp.toPx()
            drawLine(colors.border, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            val maxRate = samples.maxOfOrNull { if (download) it.down else it.up } ?: 0L
            val scale = maxOf(32.0, maxRate.toDouble() * 1.2)
            val start = maxOf(nowNanos, chartNanos) - TrafficHistory.WINDOW_NANOS
            fun point(sample: TrafficHistory.Point): Offset {
                val rate = if (download) sample.down else sample.up
                val x = size.width * ((sample.atNanos - start).toDouble() / TrafficHistory.WINDOW_NANOS).toFloat()
                return Offset(x.coerceIn(0f, size.width),
                    baseline * (1f - (rate / scale).toFloat().coerceIn(0f, 1f)))
            }
            val segment = mutableListOf<TrafficHistory.Point>()
            fun drawSegment() {
                if (segment.isEmpty()) return
                val first = point(segment.first())
                if (segment.size == 1) {
                    drawCircle(lineColor, 1.5.dp.toPx(), first)
                } else {
                    val line = Path().apply {
                        moveTo(first.x, first.y)
                        segment.drop(1).forEach { val p = point(it); lineTo(p.x, p.y) }
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(point(segment.last()).x, baseline)
                        lineTo(first.x, baseline)
                        close()
                    }
                    drawPath(fill, lineColor.copy(alpha = .12f))
                    drawPath(line, lineColor, style = Stroke(1.75.dp.toPx()))
                }
                segment.clear()
            }
            samples.forEach { sample ->
                if (segment.isNotEmpty() && sample.atNanos - segment.last().atNanos > TrafficHistory.GAP_NANOS) drawSegment()
                segment.add(sample)
            }
            drawSegment()
    }
}

@Composable
private fun StatusNotice(message: String, onClick: () -> Unit, error: Boolean, title: String?, actionLabel: String) {
    val colors = AfterglowTheme.colors
    Column(Modifier.fillMaxWidth().background(if (error) colors.errorSurface else colors.paperSecondary)
        .semantics { role = Role.Button }
        .clickable(onClick = onClick).padding(16.dp)) {
        // A notice never repeats the hero headline ("Connection failed"): the hero
        // names the state, the notice carries the reason and its action.
        if (title != null) Text(title, color = colors.ink, fontWeight = FontWeight.Bold)
        Text(message, color = colors.ink, fontSize = 14.sp, modifier = Modifier.padding(top = if (title == null) 0.dp else 4.dp))
        Text(actionLabel,
            color = colors.coral, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}
