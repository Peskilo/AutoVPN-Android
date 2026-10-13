package dev.typenil.vpnclient.data.settings

/**
 * Traces an install leaves once the user has really used the app. Collected
 * from Room (subscriptions, including the manual-import sentinel row) and
 * DataStore; no values, only presence.
 */
data class InstallEvidence(
    val subscriptionCount: Int,
    val hwidDecided: Boolean,
    val nodeSelected: Boolean,
    val vpnDesired: Boolean,
)

/**
 * Grandfather rule for updates: an install that has no onboarding flag yet but
 * already shows use (it passed the old consent / HWID flows) is marked done
 * silently instead of showing first-run screens over a working VPN.
 */
fun shouldGrandfather(onboardingDone: Boolean, evidence: InstallEvidence): Boolean =
    !onboardingDone &&
        (evidence.subscriptionCount > 0 || evidence.hwidDecided || evidence.nodeSelected || evidence.vpnDesired)
