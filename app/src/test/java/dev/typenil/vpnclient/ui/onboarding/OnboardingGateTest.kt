package dev.typenil.vpnclient.ui.onboarding

import dev.typenil.vpnclient.core.vpn.tileMayConnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingGateTest {
    private class Harness(existing: suspend () -> Boolean, initial: Boolean? = null) {
        // A shared flow can re-emit an equal value, like DataStore does after any edit.
        private val flag = MutableSharedFlow<Boolean?>(replay = 1)
        fun set(value: Boolean?) { flag.tryEmit(value) }
        var markCalls = 0
        var existingCalls = 0
        val states = mutableListOf<OnboardingGate>()
        val flow = onboardingGate(
            done = flag,
            isExistingInstall = { existingCalls++; existing() },
            // Mirrors DataStore: a successful write is observed through the flag flow.
            markDone = { markCalls++; set(true) },
        )
        init { set(initial) }
    }

    private fun kotlinx.coroutines.test.TestScope.collect(h: Harness) {
        backgroundScope.launch { h.flow.collect { h.states += it } }
    }

    @Test
    fun `unknown flag keeps the neutral frame and never decides`() = runTest {
        val h = Harness({ true })
        collect(h)
        runCurrent()
        assertEquals(listOf(OnboardingGate.Loading), h.states)
        assertEquals(0, h.existingCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun `null then false on a fresh install ends in Needed without writing the flag`() = runTest {
        val h = Harness({ false })
        collect(h)
        runCurrent()
        h.set(false)
        runCurrent()
        assertEquals(OnboardingGate.Needed, h.states.last())
        assertTrue(OnboardingGate.Done !in h.states)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun `null then false on a used install is grandfathered and ends Done`() = runTest {
        val h = Harness({ true })
        collect(h)
        runCurrent()
        h.set(false)
        runCurrent()
        assertEquals(OnboardingGate.Done, h.states.last())
        assertTrue(OnboardingGate.Needed !in h.states)
        assertEquals(1, h.markCalls)
    }

    @Test
    fun `a stored true is Done at once and skips the grandfather read`() = runTest {
        val h = Harness({ error("must not be asked") }, initial = true)
        collect(h)
        runCurrent()
        assertEquals(listOf(OnboardingGate.Done), h.states)
        assertEquals(0, h.existingCalls)
    }

    @Test
    fun `finishing onboarding moves Needed to Done`() = runTest {
        val h = Harness({ false }, initial = false)
        collect(h)
        runCurrent()
        assertEquals(OnboardingGate.Needed, h.states.last())
        h.set(true) // what finish() persists
        runCurrent()
        assertEquals(OnboardingGate.Done, h.states.last())
    }

    @Test
    fun `a repeated false does not redo the decision or flash Loading`() = runTest {
        val h = Harness({ false }, initial = false)
        collect(h)
        runCurrent()
        val before = h.states.toList()
        h.set(false) // another DataStore edit re-emits the same flag
        runCurrent()
        assertEquals(before, h.states)
        assertEquals(1, h.existingCalls)
    }

    @Test
    fun `a failed grandfather read falls back to Needed`() = runTest {
        val h = Harness({ throw java.io.IOException("db") }, initial = false)
        collect(h)
        runCurrent()
        assertEquals(OnboardingGate.Needed, h.states.last())
        assertEquals(0, h.markCalls)
    }

    @Test
    fun `cancellation is not swallowed by the fallback`() = runTest {
        val h = Harness({ throw CancellationException("stop") }, initial = false)
        collect(h)
        runCurrent()
        assertTrue(OnboardingGate.Needed !in h.states)
    }

    @Test
    fun `tile connects only when onboarding is known to be done`() {
        assertTrue(tileMayConnect(true))
        assertFalse(tileMayConnect(false))
        assertFalse(tileMayConnect(null))
    }
}
