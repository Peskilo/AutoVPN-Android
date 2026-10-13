package dev.typenil.vpnclient.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGrandfatherTest {
    private val none = InstallEvidence(0, hwidDecided = false, nodeSelected = false, vpnDesired = false)

    @Test
    fun `fresh install is not grandfathered`() {
        assertFalse(shouldGrandfather(onboardingDone = false, evidence = none))
    }

    @Test
    fun `a subscription alone grandfathers`() {
        assertTrue(shouldGrandfather(false, none.copy(subscriptionCount = 1)))
    }

    @Test
    fun `a decided HWID consent alone grandfathers`() {
        assertTrue(shouldGrandfather(false, none.copy(hwidDecided = true)))
    }

    @Test
    fun `a selected node alone grandfathers`() {
        assertTrue(shouldGrandfather(false, none.copy(nodeSelected = true)))
    }

    @Test
    fun `desired VPN running alone grandfathers`() {
        assertTrue(shouldGrandfather(false, none.copy(vpnDesired = true)))
    }

    @Test
    fun `an existing flag means nothing to grandfather`() {
        assertFalse(shouldGrandfather(true, none.copy(subscriptionCount = 3, vpnDesired = true)))
    }

    @Test
    fun `onboarding flag defaults to not done and persists true`() {
        assertFalse(SettingsRepository.onboardingDoneOf(emptyPreferences()))
        val prefs = mutablePreferencesOf()
        SettingsRepository.persistOnboardingDone(prefs)
        assertTrue(SettingsRepository.onboardingDoneOf(prefs))
    }

    @Test
    fun `evidence reads raw keys, treating desired=false as no trace`() {
        assertEquals(none, SettingsRepository.installEvidenceOf(emptyPreferences(), 0))
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("hwid_allowed") to false, // a denied choice is still a decision
            stringPreferencesKey("selected_node_id") to "n",
            booleanPreferencesKey("desired_vpn_running") to false,
        )
        assertEquals(
            InstallEvidence(2, hwidDecided = true, nodeSelected = true, vpnDesired = false),
            SettingsRepository.installEvidenceOf(prefs, 2),
        )
    }
}
