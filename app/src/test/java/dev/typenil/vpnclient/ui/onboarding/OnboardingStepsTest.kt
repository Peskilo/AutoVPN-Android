package dev.typenil.vpnclient.ui.onboarding

import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Notifications
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Subscription
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.VpnDisclosure
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep.Welcome
import org.junit.Assert.assertEquals
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
}
