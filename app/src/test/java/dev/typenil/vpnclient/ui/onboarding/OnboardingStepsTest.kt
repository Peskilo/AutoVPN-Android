package dev.typenil.vpnclient.ui.onboarding

import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Notifications
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Subscription
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.VpnDisclosure
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingStepsTest {
    private val legacy = 32
    private val modern = 33

    @Test
    fun `notifications step exists only from API 33`() {
        assertEquals(listOf(Welcome, Subscription, VpnDisclosure), OnboardingSteps.steps(legacy))
        assertEquals(listOf(Welcome, Subscription, VpnDisclosure, Notifications), OnboardingSteps.steps(modern))
    }

    @Test
    fun `disclosure is the last step below API 33 so Continue finishes there`() {
        assertNull(OnboardingSteps.next(VpnDisclosure, legacy))
        assertEquals(Notifications, OnboardingSteps.next(VpnDisclosure, modern))
        assertNull(OnboardingSteps.next(Notifications, modern))
    }

    @Test
    fun `next walks forward in order`() {
        assertEquals(Subscription, OnboardingSteps.next(Welcome, legacy))
        assertEquals(VpnDisclosure, OnboardingSteps.next(Subscription, legacy))
    }

    @Test
    fun `previous walks back and is null on the first step`() {
        assertNull(OnboardingSteps.previous(Welcome, modern))
        assertEquals(Welcome, OnboardingSteps.previous(Subscription, modern))
        assertEquals(VpnDisclosure, OnboardingSteps.previous(Notifications, modern))
    }

    @Test
    fun `position and count follow the SDK`() {
        assertEquals(3, OnboardingSteps.count(legacy))
        assertEquals(4, OnboardingSteps.count(modern))
        assertEquals(1, OnboardingSteps.position(Welcome, legacy))
        assertEquals(3, OnboardingSteps.position(VpnDisclosure, legacy))
        assertEquals(4, OnboardingSteps.position(Notifications, modern))
    }

    @Test
    fun `a step missing on this SDK degrades to the first position`() {
        assertEquals(1, OnboardingSteps.position(Notifications, legacy))
    }

    @Test
    fun `notifications step is shown only on API 33+ and only while not granted`() {
        for (sdk in listOf(32, 33, 34)) {
            for (granted in listOf(false, true)) {
                assertEquals(
                    "sdk=$sdk granted=$granted",
                    sdk >= 33 && !granted,
                    OnboardingSteps.showNotificationsStep(sdk, granted),
                )
                assertEquals(
                    OnboardingSteps.showNotificationsStep(sdk, granted),
                    Notifications in OnboardingSteps.steps(sdk, granted),
                )
            }
        }
    }

    @Test
    fun `an already granted permission makes disclosure the last step`() {
        assertEquals(3, OnboardingSteps.count(34, granted = true))
        assertNull(OnboardingSteps.next(VpnDisclosure, 34, granted = true))
    }

    @Test
    fun `advanceFrom moves on and the last step finishes - also after Not now or a denial`() {
        val moved = mutableListOf<OnboardingStep>()
        var finished = 0
        advanceFrom(Welcome, 34, false, { moved += it }, { finished++ })
        assertEquals(listOf(Subscription), moved)
        assertEquals(0, finished)
        // Not now and every permission result land here for the last step.
        advanceFrom(Notifications, 34, false, { moved += it }, { finished++ })
        assertEquals(1, moved.size)
        assertEquals(1, finished)
        // Without the step (granted) the disclosure Continue finishes.
        advanceFrom(VpnDisclosure, 34, true, { moved += it }, { finished++ })
        assertEquals(2, finished)
        assertFalse(moved.contains(Notifications))
    }
}
