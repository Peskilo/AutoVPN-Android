package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.data.db.FakeNodeLatencyDao
import dev.typenil.vpnclient.data.db.NodeLatencyEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class AutoFallbackProbeNode(val id: String, val host: String, val port: Int)

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
    fun `empty history triggers a bounded TCP preflight for all candidates`() = runTest {
        val candidates = listOf(
            AutoFallbackProbeNode("a", "a.example", 443),
            AutoFallbackProbeNode("b", "b.example", 8443),
        )
        val calls = mutableListOf<String>()

        val results = probeUnknownAutoFallbackCandidates(
            candidates = candidates,
            idOf = { it.id },
            hostOf = { it.host },
            portOf = { it.port },
            verdicts = emptyMap(),
            nowMs = 1_000L,
            budgetMs = 1_000L,
            probe = { host, port ->
                calls += "$host:$port"
                if (host == "a.example") 72 else null
            },
        )

        assertEquals(setOf("a.example:443", "b.example:8443"), calls.toSet())
        assertEquals(mapOf("a" to 72, "b" to null), results)
    }

    @Test
    fun `a fresh successful verdict prevents unnecessary first-connect preflight`() = runTest {
        val candidates = listOf(
            AutoFallbackProbeNode("known", "known.example", 443),
            AutoFallbackProbeNode("unknown", "unknown.example", 443),
        )
        val calls = mutableListOf<String>()

        val results = probeUnknownAutoFallbackCandidates(
            candidates = candidates,
            idOf = { it.id },
            hostOf = { it.host },
            portOf = { it.port },
            verdicts = mapOf(
                "known" to mapOf(
                    LatencyMethod.Proxy to NodeLatency(180, 1_000L, LatencyMethod.Proxy),
                ),
            ),
            nowMs = 1_000L,
            probe = { host, _ -> calls += host; 20 },
        )

        assertTrue(results.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `fresh failures stay authoritative and only unknown candidates are preflighted`() = runTest {
        val candidates = listOf(
            AutoFallbackProbeNode("proxy-failed", "proxy.example", 443),
            AutoFallbackProbeNode("tcp-failed", "tcp.example", 443),
            AutoFallbackProbeNode("unknown", "unknown.example", 443),
        )
        val calls = mutableListOf<String>()

        val results = probeUnknownAutoFallbackCandidates(
            candidates = candidates,
            idOf = { it.id },
            hostOf = { it.host },
            portOf = { it.port },
            verdicts = mapOf(
                "proxy-failed" to mapOf(
                    LatencyMethod.Proxy to NodeLatency(null, 1_000L, LatencyMethod.Proxy),
                    LatencyMethod.Tcp to NodeLatency(40, 1_000L, LatencyMethod.Tcp),
                ),
                "tcp-failed" to mapOf(
                    LatencyMethod.Tcp to NodeLatency(null, 1_000L, LatencyMethod.Tcp),
                ),
            ),
            nowMs = 1_000L,
            probe = { host, _ -> calls += host; null },
        )

        assertEquals(listOf("unknown.example"), calls)
        assertEquals(mapOf("unknown" to null), results)
    }

    @Test
    fun `stale and future evidence do not suppress first-connect preflight`() = runTest {
        val nowMs = LATENCY_TTL_MS + 10_000L
        val candidates = listOf(
            AutoFallbackProbeNode("stale", "stale.example", 443),
            AutoFallbackProbeNode("future", "future.example", 443),
        )
        val calls = mutableListOf<String>()

        val results = probeUnknownAutoFallbackCandidates(
            candidates = candidates,
            idOf = { it.id },
            hostOf = { it.host },
            portOf = { it.port },
            verdicts = mapOf(
                "stale" to mapOf(
                    LatencyMethod.Proxy to NodeLatency(1, 1_000L, LatencyMethod.Proxy),
                ),
                "future" to mapOf(
                    LatencyMethod.Tcp to NodeLatency(1, nowMs + 1L, LatencyMethod.Tcp),
                ),
            ),
            nowMs = nowMs,
            probe = { host, _ -> calls += host; 90 },
        )

        assertEquals(setOf("stale.example", "future.example"), calls.toSet())
        assertEquals(mapOf("stale" to 90, "future" to 90), results)
    }

    @Test
    fun `preflight budget keeps completed verdicts without marking pending probes failed`() = runTest {
        val candidates = listOf(
            AutoFallbackProbeNode("fast", "fast.example", 443),
            AutoFallbackProbeNode("slow", "slow.example", 443),
        )

        val results = probeUnknownAutoFallbackCandidates(
            candidates = candidates,
            idOf = { it.id },
            hostOf = { it.host },
            portOf = { it.port },
            verdicts = emptyMap(),
            nowMs = 1_000L,
            budgetMs = 100L,
            probe = { host, _ ->
                if (host == "slow.example") awaitCancellation() else 11
            },
        )

        assertEquals(mapOf("fast" to 11), results)
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
    fun `legacy v8 node-only rows remain readable beside new method rows`() =
        runTest {
            dao.upsertAll(listOf(NodeLatencyEntity("legacy", 55, 1_000L, "tcp")))
            clock = 2_000L
            repo.record(mapOf("legacy" to 95), LatencyMethod.Proxy)

            val byMethod = repo.verdicts.first().getValue("legacy")
            assertEquals(NodeLatency(55, 1_000L, LatencyMethod.Tcp), byMethod[LatencyMethod.Tcp])
            assertEquals(NodeLatency(95, 2_000L, LatencyMethod.Proxy), byMethod[LatencyMethod.Proxy])
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
