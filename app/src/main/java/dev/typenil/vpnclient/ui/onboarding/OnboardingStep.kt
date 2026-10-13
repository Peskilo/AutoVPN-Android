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

    /** Ask only where the permission exists and is not already held. */
    fun showNotificationsStep(sdk: Int, granted: Boolean): Boolean = sdk >= NOTIFICATIONS_MIN_SDK && !granted

    /**
     * [granted] must be a snapshot taken once when onboarding starts: pressing
     * Allow flips the live permission and would otherwise remove the step
     * (and renumber "Step N of M") under the user's feet.
     */
    fun steps(sdk: Int, granted: Boolean = false): List<OnboardingStep> =
        OnboardingStep.entries.filter { it != OnboardingStep.Notifications || showNotificationsStep(sdk, granted) }

    /** The step after [step], or null when [step] is the last one (= finish). */
    fun next(step: OnboardingStep, sdk: Int, granted: Boolean = false): OnboardingStep? =
        steps(sdk, granted).let { it.getOrNull(it.indexOf(step) + 1) }

    /** The step before [step], or null on the first step (= system back). */
    fun previous(step: OnboardingStep, sdk: Int, granted: Boolean = false): OnboardingStep? =
        steps(sdk, granted).let { it.getOrNull(it.indexOf(step) - 1) }

    /** 1-based position for "Step N of M". A step absent on this SDK maps to 1. */
    fun position(step: OnboardingStep, sdk: Int, granted: Boolean = false): Int =
        (steps(sdk, granted).indexOf(step) + 1).coerceAtLeast(1)

    fun count(sdk: Int, granted: Boolean = false): Int = steps(sdk, granted).size
}

/**
 * Moves past [step]; the last step finishes. Continue, "Not now" and every
 * permission result (including a denial) go through here, so a refusal can
 * never stall or loop onboarding.
 */
internal fun advanceFrom(
    step: OnboardingStep,
    sdk: Int,
    granted: Boolean,
    onStep: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
) {
    OnboardingSteps.next(step, sdk, granted)?.let(onStep) ?: onFinish()
}
