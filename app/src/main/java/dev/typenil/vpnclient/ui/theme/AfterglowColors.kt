package dev.typenil.vpnclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Pingwin-inspired blue/green palette shared by every SayanVPN screen. */
@Immutable
data class AfterglowPalette(
    val paper: Color,
    val paperSecondary: Color,
    val surfaceElevated: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
    val borderStrong: Color,
    val coral: Color,
    val coralLight: Color,
    val jade: Color,
    val amber: Color,
    val error: Color,
    val errorSurface: Color,
    val onInk: Color,
    val actionSurface: Color,
    val onActionSurface: Color,
    val errorAccent: Color,
    val heroNeutral: Color,
    val heroProgress: Color,
    val heroPositive: Color,
    val heroError: Color,
    val onHero: Color,
    val onHeroMuted: Color,
)

val LightAfterglow =
    AfterglowPalette(
        paper = Color(0xFFFAFBFD),
        paperSecondary = Color(0xFFF3F5F9),
        surfaceElevated = Color(0xFFFFFFFF),
        ink = Color(0xFF17191F),
        muted = Color(0xFF455064),
        border = Color(0xFFE9ECF1),
        borderStrong = Color(0xFF657084),
        coral = Color(0xFF2B62D6),
        coralLight = Color(0xFF2B62D6),
        jade = Color(0xFF187A3C),
        amber = Color(0xFF89600B),
        error = Color(0xFFB4233B),
        errorSurface = Color(0xFFFFE4E7),
        onInk = Color(0xFFFFFFFF),
        actionSurface = Color(0xFF050608),
        onActionSurface = Color(0xFFFFFFFF),
        errorAccent = Color(0xFFB4233B),
        heroNeutral = Color(0xFF10285A),
        heroProgress = Color(0xFF73510B),
        heroPositive = Color(0xFF0B4B2D),
        heroError = Color(0xFF7F1D2D),
        onHero = Color(0xFFFFFFFF),
        onHeroMuted = Color(0xFFE9EDF6),
    )

val DarkAfterglow =
    AfterglowPalette(
        paper = Color(0xFF0B1020),
        paperSecondary = Color(0xFF111827),
        surfaceElevated = Color(0xFF172238),
        ink = Color(0xFFF4F6FA),
        muted = Color(0xFFB4C0D2),
        border = Color(0xFF34425A),
        borderStrong = Color(0xFF7C8CA5),
        coral = Color(0xFF8AB0FF),
        coralLight = Color(0xFF8AB0FF),
        jade = Color(0xFF66D995),
        amber = Color(0xFFFFD47A),
        error = Color(0xFFFF9AA8),
        errorSurface = Color(0xFF421D29),
        onInk = Color(0xFF0B1020),
        actionSurface = Color(0xFF1E3355),
        onActionSurface = Color(0xFFFFFFFF),
        errorAccent = Color(0xFFFF9AA8),
        heroNeutral = Color(0xFF10285A),
        heroProgress = Color(0xFF5A4210),
        heroPositive = Color(0xFF0B3C2C),
        heroError = Color(0xFF5B1F2D),
        onHero = Color(0xFFFFFFFF),
        onHeroMuted = Color(0xFFE9EDF6),
    )

val LocalAfterglowPalette = compositionLocalOf { LightAfterglow }

enum class StateTone { Neutral, Progress, Positive, Error }

@Immutable
data class StateColors(val hero: Color, val onHero: Color, val onHeroMuted: Color, val accent: Color, val line: Color)

fun AfterglowPalette.stateColors(tone: StateTone): StateColors =
    when (tone) {
        StateTone.Neutral -> StateColors(heroNeutral, onHero, onHeroMuted, coralLight, coralLight)
        StateTone.Progress -> StateColors(heroProgress, onHero, onHeroMuted, amber, amber)
        StateTone.Positive -> StateColors(heroPositive, onHero, onHeroMuted, jade, jade)
        StateTone.Error -> StateColors(heroError, onHero, onHeroMuted, errorAccent, errorAccent)
    }
