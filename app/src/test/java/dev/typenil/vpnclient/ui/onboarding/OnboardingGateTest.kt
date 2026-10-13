package dev.typenil.vpnclient.ui.onboarding

import dev.typenil.vpnclient.core.vpn.tileMayConnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private suspend inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw e
    }
    throw AssertionError("expected ${T::class.simpleName}")
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingGateTest {
    private class Harness(
        existing: suspend () -> Boolean,
        initial: Boolean? = null,
        done: kotlinx.coroutines.flow.Flow<Boolean>? = null,
    ) {
        // A shared flow can re-emit an equal value, like DataStore does after any edit.
        private val flag = MutableSharedFlow<Boolean>(replay = 1)
        fun set(value: Boolean) { flag.tryEmit(value) }
        var markCalls = 0
        var existingCalls = 0
        val states = mutableListOf<OnboardingGate>()
        val flow = onboardingGate(
            done = done ?: flag,
            isExistingInstall = { existingCalls++; existing() },
            // Mirrors DataStore: a successful write is observed through the flag flow.
            markDone = { markCalls++; set(true) },
        )
        init { initial?.let(::set) }
    }

    private fun kotlinx.coroutines.test.TestScope.collect(h: Harness) {
        backgroundScope.launch { h.flow.collect { h.states += it } }
    }

    @Test
    fun `no flag value yet keeps the neutral frame and never decides`() = runTest {
        val h = Harness({ true })
        collect(h)
        runCurrent()
        assertTrue(h.states.isEmpty()) // the ViewModel shows Loading until the first emission
        assertEquals(0, h.existingCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun `false on a fresh install ends in Needed without writing the flag`() = runTest {
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
    fun `false on a used install is grandfathered and ends Done`() = runTest {
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

    private fun failing(failures: Int, then: Boolean, reads: () -> Int, bump: () -> Unit) = flow {
        bump()
        if (reads() <= failures) throw IOException("datastore")
        emit(then)
    }

    @Test
    fun `a first successful read decides at once without waiting`() = runTest {
        var reads = 0
        val h = Harness({ false }, done = failing(0, true, { reads }) { reads++ })
        collect(h)
        runCurrent()
        assertEquals(listOf(OnboardingGate.Done), h.states)
        assertEquals(1, reads)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a read that fails twice then succeeds is retried with a pause`() = runTest {
        var reads = 0
        val h = Harness({ false }, done = failing(2, false, { reads }) { reads++ })
        collect(h)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(h.states.isEmpty()) // still the neutral frame
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, reads)
        assertEquals(OnboardingGate.Needed, h.states.last())
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `exhausted retries fall back to Needed instead of an endless Loading`() = runTest {
        var reads = 0
        val h = Harness({ error("must not be asked") }, done = failing(Int.MAX_VALUE, true, { reads }) { reads++ })
        collect(h)
        runCurrent()
        advanceTimeBy(1_400)
        runCurrent()
        assertTrue("still retrying", h.states.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(OnboardingGate.Needed), h.states)
        assertEquals(4, reads) // first read + 3 retries
        assertEquals(0, h.existingCalls)
        assertEquals(0, h.markCalls)
    }

    @Test
    fun `a non-IO failure is not swallowed by the retry fallback`() = runTest {
        assertFailsWith<IllegalStateException> {
            onboardingGate(
                done = flow { throw IllegalStateException("bug") },
                isExistingInstall = { false },
                markDone = {},
            ).toList()
        }
    }

    @Test
    fun `finishing opens the app even when the flag was never readable or writable`() = runTest {
        val finished = MutableStateFlow(false)
        val states = mutableListOf<OnboardingGate>()
        backgroundScope.launch {
            onboardingGate(
                done = flow { throw IOException("datastore") },
                isExistingInstall = { false },
                markDone = {},
                finished = finished,
            ).collect { states += it }
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(OnboardingGate.Needed, states.last())
        finishOnboarding(finished) { throw IOException("disk full") }
        runCurrent()
        assertEquals(OnboardingGate.Done, states.last())
    }

    @Test
    fun `finishOnboarding enters first and survives a failed write`() = runTest {
        val finished = MutableStateFlow(false)
        var wasFinishedWhenWriting = false
        finishOnboarding(finished) {
            wasFinishedWhenWriting = finished.value
            throw IOException("disk full")
        }
        assertTrue(finished.value)
        assertTrue("flag must be set before the write starts", wasFinishedWhenWriting)
    }

    @Test
    fun `finishOnboarding does not swallow cancellation`() = runTest {
        val finished = MutableStateFlow(false)
        assertFailsWith<CancellationException> { finishOnboarding(finished) { throw CancellationException("stop") } }
        assertTrue(finished.value)
    }

    @Test
    fun `tile connects only when onboarding is known to be done`() {
        assertTrue(tileMayConnect(true))
        assertFalse(tileMayConnect(false))
        assertFalse(tileMayConnect(null))
    }
}
