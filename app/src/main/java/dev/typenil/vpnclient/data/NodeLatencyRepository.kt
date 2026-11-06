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

/** Internal separator for the composite logical key in the v8 latency table. */
internal const val LATENCY_STORAGE_SEPARATOR = "\u001F"

internal fun latencyStorageKey(nodeId: String, method: LatencyMethod): String =
    "$nodeId$LATENCY_STORAGE_SEPARATOR${method.storage}"

internal fun latencyNodeIdFromStorageKey(storageKey: String): String =
    storageKey.substringBefore(LATENCY_STORAGE_SEPARATOR)

/** How long a stored verdict still counts as current for ordering. */
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
 * Fresh successful Proxy evidence wins, then fresh TCP success. A fresh Proxy
 * failure is a strong negative signal and is not hidden by a successful TCP
 * connect: an open port does not prove that the proxy handshake works.
 * Unknown/stale evidence stays ahead of fresh failures.
 */
internal fun <T> orderAutoFallbackCandidates(
    candidates: List<T>,
    idOf: (T) -> String,
    verdicts: Map<String, Map<LatencyMethod, NodeLatency>>,
    nowMs: Long,
): List<T> =
    candidates.withIndex()
        .sortedWith(
            compareBy<IndexedValue<T>> { indexed ->
                val byMethod = verdicts[idOf(indexed.value)].orEmpty()
                val proxy = byMethod[LatencyMethod.Proxy]?.takeIf { it.isFresh(nowMs) }
                val tcp = byMethod[LatencyMethod.Tcp]?.takeIf { it.isFresh(nowMs) }
                when {
                    proxy?.latencyMs != null -> 0
                    proxy != null -> 3
                    tcp?.latencyMs != null -> 1
                    tcp != null -> 3
                    else -> 2
                }
            }.thenBy { indexed ->
                val byMethod = verdicts[idOf(indexed.value)].orEmpty()
                val proxy = byMethod[LatencyMethod.Proxy]?.takeIf { it.isFresh(nowMs) }
                val tcp = byMethod[LatencyMethod.Tcp]?.takeIf { it.isFresh(nowMs) }
                proxy?.latencyMs ?: tcp?.latencyMs ?: Int.MAX_VALUE
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

    /** Node id → independent verdicts, one per method. Unknown methods are skipped. */
    val verdicts: Flow<Map<String, Map<LatencyMethod, NodeLatency>>> =
        dao.observeLive().map { rows ->
            buildMap {
                for (row in rows) {
                    val method = LatencyMethod.fromStorage(row.method) ?: continue
                    val byMethod = getOrPut(row.nodeId) { mutableMapOf() }
                    byMethod[method] = NodeLatency(row.latencyMs, row.checkedAtEpochMs, method)
                }
            }
        }

    /** Compatibility/display projection: newest verdict per node; Proxy wins
     *  an exact timestamp tie because it is stronger end-to-end evidence. */
    val latencies: Flow<Map<String, NodeLatency>> =
        verdicts.mapValues { (_, byMethod) ->
            byMethod.values.maxWithOrNull(
                compareBy<NodeLatency> { it.checkedAtMs }
                    .thenBy { it.method == LatencyMethod.Proxy },
            )!!
        }

    /** One verdict batch, all stamped with the same time. `null` values are
     *  failed probes. A composite storage key preserves both methods without
     *  dropping the existing v8 table or its historical row. */
    suspend fun record(
        results: Map<String, Int?>,
        method: LatencyMethod,
    ) {
        if (results.isEmpty()) return
        val at = nowMs()
        dao.record(
            results.map { (id, ms) ->
                NodeLatencyEntity(latencyStorageKey(id, method), ms, at, method.storage)
            },
        )
    }
}
