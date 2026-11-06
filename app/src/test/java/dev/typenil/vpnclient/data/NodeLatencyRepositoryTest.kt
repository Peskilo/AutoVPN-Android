package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.data.db.FakeNodeLatencyDao
import dev.typenil.vpnclient.data.db.NodeLatencyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeLatencyRepositoryTest {
    private val dao = FakeNodeLatencyDao()
    private var clock = 1_000L
    private val repo = NodeLatencyRepository(dao) { clock }

    @Test
    fun `auto fallback prefers fresh proxy successes over tcp successes`() {
        val nodes = listOf("timeout", "tcp-fast", "proxy-slower", "unknown")
        val verdicts = mapOf(
            "timeout" to mapOf(LatencyMethod.Tcp to NodeLatency(null, 1_000L, LatencyMethod.Tcp)),
            "tcp-fast" to mapOf(LatencyMethod.Tcp to NodeLatency(80, 1_000L, LatencyMethod.Tcp)),
            "proxy-slower" to mapOf(LatencyMethod.Proxy to NodeLatency(190, 1_000L, LatencyMethod.Proxy)),
        )

        assertEquals(
            listOf("proxy-slower", "tcp-fast", "unknown", "timeout"),
            orderAutoFallbackCandidates(nodes, { it }, verdicts, nowMs = 1_000L),
        )
    }

    @Test
    fun `fresh proxy failure is not hidden by a successful tcp connect`() {
        val nodes = listOf("proxy-failed", "tcp-only", "unknown")
        val verdicts = mapOf(
            "proxy-failed" to mapOf(
                LatencyMethod.Proxy to NodeLatency(null, 1_000L, LatencyMethod.Proxy),
                LatencyMethod.Tcp to NodeLatency(40, 1_000L, LatencyMethod.Tcp),
            ),
            "tcp-only" to mapOf(
                LatencyMethod.Tcp to NodeLatency(80, 1_000L, LatencyMethod.Tcp),
            ),
        )

        assertEquals(
            listOf("tcp-only", "unknown", "proxy-failed"),
            orderAutoFallbackCandidates(nodes, { it }, verdicts, nowMs = 1_000L),
        )
    }

    @Test
    fun `auto fallback ignores stale and future measurements`() {
        val nowMs = LATENCY_TTL_MS + 10_000L
        val nodes = listOf("stale", "fresh", "future")
        val verdicts = mapOf(
            "stale" to mapOf(LatencyMethod.Proxy to NodeLatency(5, 1_000L, LatencyMethod.Proxy)),
            "fresh" to mapOf(LatencyMethod.Tcp to NodeLatency(120, nowMs - 1_000L, LatencyMethod.Tcp)),
            "future" to mapOf(LatencyMethod.Proxy to NodeLatency(1, nowMs + 1_000L, LatencyMethod.Proxy)),
        )

        assertEquals(
            listOf("fresh", "stale", "future"),
            orderAutoFallbackCandidates(nodes, { it }, verdicts, nowMs = nowMs),
        )
    }

    @Test
    fun `auto fallback keeps original order when evidence is equally weak`() {
        val nodes = listOf("first", "second")
        val verdicts = mapOf(
            "first" to mapOf(LatencyMethod.Proxy to NodeLatency(null, 1_000L, LatencyMethod.Proxy)),
            "second" to mapOf(LatencyMethod.Tcp to NodeLatency(null, 1_000L, LatencyMethod.Tcp)),
        )

        assertEquals(
            listOf("first", "second"),
            orderAutoFallbackCandidates(nodes, { it }, verdicts, nowMs = 1_000L),
        )
    }

    @Test
    fun `verdicts carry method and the batch timestamp, failures stay null`() =
        runTest {
            repo.record(mapOf("a" to 42, "b" to null), LatencyMethod.Tcp)

            val stored = repo.latencies.first()
            assertEquals(NodeLatency(42, 1_000L, LatencyMethod.Tcp), stored["a"])
            assertEquals(NodeLatency(null, 1_000L, LatencyMethod.Tcp), stored["b"])
            assertEquals(
                NodeLatency(42, 1_000L, LatencyMethod.Tcp),
                repo.verdicts.first().getValue("a").getValue(LatencyMethod.Tcp),
            )
        }

    @Test
    fun `tcp and proxy verdicts coexist independently for one node`() =
        runTest {
            repo.record(mapOf("a" to 42), LatencyMethod.Tcp)
            clock = 5_000L
            repo.record(mapOf("a" to 310), LatencyMethod.Proxy)

            val byMethod = repo.verdicts.first().getValue("a")
            assertEquals(NodeLatency(42, 1_000L, LatencyMethod.Tcp), byMethod[LatencyMethod.Tcp])
            assertEquals(NodeLatency(310, 5_000L, LatencyMethod.Proxy), byMethod[LatencyMethod.Proxy])
            assertEquals(2, dao.allRows.size)
        }

    @Test
    fun `rows of nodes that no longer exist are swept on the next write`() =
        runTest {
            dao.liveNodeIds = setOf("a", "b")
            repo.record(mapOf("a" to 1, "b" to 2), LatencyMethod.Tcp)

            dao.liveNodeIds = setOf("b", "c") // a vanished in a refresh
            repo.record(mapOf("c" to 3), LatencyMethod.Tcp)

            assertEquals(setOf("b", "c"), dao.rows.keys)
        }

    @Test
    fun `an unknown method from a newer build is skipped, not mislabelled`() =
        runTest {
            dao.upsertAll(listOf(NodeLatencyEntity("a", 9, 1L, "quic")))

            assertNull(repo.latencies.first()["a"])
        }

    @Test
    fun `a verdict is fresh up to the TTL, outdated after it and when from the future`() {
        val v = NodeLatency(42, 1_000L, LatencyMethod.Tcp)
        assertEquals(true, v.isFresh(1_000L + LATENCY_TTL_MS))
        assertEquals(false, v.isFresh(1_000L + LATENCY_TTL_MS + 1))
        assertEquals(false, v.isFresh(999L)) // clock moved back: cannot be trusted
    }
}
