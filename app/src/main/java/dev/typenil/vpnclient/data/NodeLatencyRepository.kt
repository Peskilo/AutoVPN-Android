package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.data.db.NodeLatencyDao
import dev.typenil.vpnclient.data.db.NodeLatencyEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What a stored latency actually measured. The two are not comparable:
 *  [Tcp] is "the server port accepted a connection", [Proxy] is a full
 *  request through the node's outbound. */
enum class LatencyMethod(val storage: String) {
    Tcp("tcp"),
    Proxy("proxy"),
    ;

    companion object {
        fun fromStorage(value: String): LatencyMethod? = entries.firstOrNull { it.storage == value }
    }
}

/** How long a stored verdict still counts as current for ordering. Past it
 *  the value is shown as outdated history and never sorts as a fresh one. */
const val LATENCY_TTL_MS = 24L * 60 * 60 * 1000

/** A persisted verdict. [latencyMs] null = the probe ran and failed. */
data class NodeLatency(
    val latencyMs: Int?,
    val checkedAtMs: Long,
    val method: LatencyMethod,
) {
    /** A timestamp in the future (clock moved back) can't be trusted: stale. */
    fun isFresh(nowMs: Long): Boolean = nowMs - checkedAtMs in 0..LATENCY_TTL_MS
}

/**
 * Orders candidates only for sing-box's initial urltest fallback.
 *
 * sing-box selects the first network-compatible outbound when it has no
 * successful URL-test history yet. Put recent end-to-end Proxy successes
 * first, then recent TCP-connect successes, then unknown/stale nodes, and
 * finally nodes whose latest fresh probe failed. Once urltest has history,
 * sing-box's own latency+tolerance selection takes over.
 *
 * TCP is deliberately weaker evidence than Proxy: it proves only that the
 * server port accepted a connection, not that the proxy protocol works.
 */
internal fun <T> orderAutoFallbackCandidates(
    candidates: List<T>,
    idOf: (T) -> String,
    latencies: Map<String, NodeLatency>,
    nowMs: Long,
): List<T> =
    candidates.withIndex()
        .sortedWith(
            compareBy<IndexedValue<T>> { indexed ->
                val verdict = latencies[idOf(indexed.value)]
                when {
                    verdict == null || !verdict.isFresh(nowMs) -> 2
                    verdict.latencyMs != null && verdict.method == LatencyMethod.Proxy -> 0
                    verdict.latencyMs != null && verdict.method == LatencyMethod.Tcp -> 1
                    else -> 3
                }
            }.thenBy { indexed ->
                val verdict = latencies[idOf(indexed.value)]
                if (verdict?.isFresh(nowMs) == true) verdict.latencyMs ?: Int.MAX_VALUE
                else Int.MAX_VALUE
            }.thenBy { it.index },
        )
        .map { it.value }

/**
 * Persisted per-node latency verdicts. Stored values are history — callers
 * must show them with [NodeLatency.checkedAtMs] and the method, never as a
 * live reading.
 */
@Singleton
class NodeLatencyRepository internal constructor(
    private val dao: NodeLatencyDao,
    private val nowMs: () -> Long,
) {
    @Inject constructor(dao: NodeLatencyDao) : this(dao, System::currentTimeMillis)

    /** Wall clock the stored timestamps are written with. */
    fun now(): Long = nowMs()

    /** Node id → last verdict, for nodes that still exist. Rows with an
     *  unknown method (written by a newer build) are skipped. */
    val latencies: Flow<Map<String, NodeLatency>> =
        dao.observeLive().map { rows ->
            buildMap {
                for (row in rows) {
                    val method = LatencyMethod.fromStorage(row.method) ?: continue
                    put(row.nodeId, NodeLatency(row.latencyMs, row.checkedAtEpochMs, method))
                }
            }
        }

    /** One verdict batch, all stamped with the same time. `null` values are
     *  failed probes. */
    suspend fun record(
        results: Map<String, Int?>,
        method: LatencyMethod,
    ) {
        if (results.isEmpty()) return
        val at = nowMs()
        dao.record(results.map { (id, ms) -> NodeLatencyEntity(id, ms, at, method.storage) })
    }
}
