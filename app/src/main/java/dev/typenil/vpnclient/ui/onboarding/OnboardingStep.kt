package dev.typenil.vpnclient.ui.onboarding

/**
 * First-run steps. [VpnDisclosure] is the prominent-disclosure screen: its
 * Continue button is the record that the text was shown. Onboarding never
 * calls `VpnService.prepare` — consent stays behind `ConnectionManager`.
 */
enum class OnboardingStep { Welcome, Subscription, VpnDisclosure, Notifications }

/** Pure step machine; no Android types so it is unit-testable. */
object OnboardingSteps {
    /** POST_NOTIFICATIONS exists as a runtime permission from API 33 only. */
    const val NOTIFICATIONS_MIN_SDK = 33

    fun steps(sdk: Int): List<OnboardingStep> =
        OnboardingStep.entries.filter { it != OnboardingStep.Notifications || sdk >= NOTIFICATIONS_MIN_SDK }

    /** The step after [step], or null when [step] is the last one (= finish). */
    fun next(step: OnboardingStep, sdk: Int): OnboardingStep? =
        steps(sdk).let { it.getOrNull(it.indexOf(step) + 1) }

    /** The step before [step], or null on the first step (= system back). */
    fun previous(step: OnboardingStep, sdk: Int): OnboardingStep? =
        steps(sdk).let { it.getOrNull(it.indexOf(step) - 1) }

    /** 1-based position for "Step N of M". A step absent on this SDK maps to 1. */
    fun position(step: OnboardingStep, sdk: Int): Int =
        (steps(sdk).indexOf(step) + 1).coerceAtLeast(1)

    fun count(sdk: Int): Int = steps(sdk).size
}
