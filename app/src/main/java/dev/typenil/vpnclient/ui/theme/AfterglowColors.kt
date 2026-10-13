package dev.typenil.vpnclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand surfaces remain stable even when Material dynamic color is enabled. */
@Immutable
data class AfterglowPalette(
    val paper: Color,
    val paperSecondary: Color,
    val surfaceElevated: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
    /** Boundaries of controls (fields, switches, chips): WCAG non-text 3:1. [border] stays decorative. */
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
    /** State surfaces, see [stateColors]; the status text always names the state too. */
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
        paperSecondary = Color(0xFFF0F4FA),
        surfaceElevated = Color(0xFFFFFFFF),
        ink = Color(0xFF10285A),
        muted = Color(0xFF697184),
        border = Color(0xFFE0E6F0),
        borderStrong = Color(0xFF8795AC),
        coral = Color(0xFF4C8DFF),
        coralLight = Color(0xFF4C8DFF),
        jade = Color(0xFF2BAE57),
        amber = Color(0xFFFFC247),
        error = Color(0xFFB4233B),
        errorSurface = Color(0xFFFFE4E7),
        onInk = Color(0xFFFFFFFF),
        actionSurface = Color(0xFF10285A),
        onActionSurface = Color(0xFFF1F6FF),
        errorAccent = Color(0xFFFF657A),
        heroNeutral = Color(0xFF17345A),
        heroProgress = Color(0xFF254B83),
        heroPositive = Color(0xFF17613A),
        heroError = Color(0xFF9E2638),
        onHero = Color(0xFFFFFFFF),
        onHeroMuted = Color(0xFFD7E8FA),
    )

val DarkAfterglow =
    AfterglowPalette(
        paper = Color(0xFF07101F),
        paperSecondary = Color(0xFF111E31),
        surfaceElevated = Color(0xFF182943),
        ink = Color(0xFFF1F6FF),
        muted = Color(0xFFB4C4D9),
        border = Color(0xFF30435C),
        borderStrong = Color(0xFF7C91AE),
        coral = Color(0xFF8BB5FF),
        coralLight = Color(0xFF8BB5FF),
        jade = Color(0xFF5DC9A7),
        amber = Color(0xFFF4C27B),
        error = Color(0xFFFF9CAB),
        errorSurface = Color(0xFF3B1D2B),
        onInk = Color(0xFF07101F),
        actionSurface = Color(0xFF182943),
        onActionSurface = Color(0xFFFFFAF6),
        errorAccent = Color(0xFFFF9CAB),
        heroNeutral = Color(0xFF102846),
        heroProgress = Color(0xFF254B83),
        heroPositive = Color(0xFF17613A),
        heroError = Color(0xFF661C30),
        onHero = Color(0xFFFFFAF6),
        onHeroMuted = Color(0xFFE6DAD3),
    )

val LocalAfterglowPalette = compositionLocalOf { LightAfterglow }

/** Semantic meaning of a connection state; red is reserved for [Error]. */
enum class StateTone { Neutral, Progress, Positive, Error }

/** Colors of the hero and the action bar for one [StateTone]. */
@Immutable
data class StateColors(val hero: Color, val onHero: Color, val onHeroMuted: Color, val accent: Color, val line: Color)

/** Pure tone -> tokens mapping; screens never pick state colors themselves. */
fun AfterglowPalette.stateColors(tone: StateTone): StateColors =
    when (tone) {
        StateTone.Neutral -> StateColors(heroNeutral, onHero, onHeroMuted, coralLight, coralLight)
        StateTone.Progress -> StateColors(heroProgress, onHero, onHeroMuted, amber, amber)
        StateTone.Positive -> StateColors(heroPositive, onHero, onHeroMuted, jade, jade)
        StateTone.Error -> StateColors(heroError, onHero, onHeroMuted, errorAccent, errorAccent)
    }
