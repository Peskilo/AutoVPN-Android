package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.data.db.NodeLatencyDao
import dev.typenil.vpnclient.data.db.NodeLatencyEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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

/** A positive measurement is only a short-lived hint for initial ordering. */
const val LATENCY_TTL_MS = 15L * 60 * 1000

/** A transient failure should deprioritize a recovered node for only five minutes. */
const val LATENCY_FAILURE_TTL_MS = 5L * 60 * 1000

/** A persisted verdict. [latencyMs] null = the probe ran and failed. */
data class NodeLatency(
    val latencyMs: Int?,
    val checkedAtMs: Long,
    val method: LatencyMethod,
) {
    /** Future timestamps (clock moved back) are never trusted. Failures expire quickly. */
    fun isFresh(nowMs: Long): Boolean {
        val ageMs = nowMs - checkedAtMs
        val ttlMs = if (latencyMs == null) LATENCY_FAILURE_TTL_MS else LATENCY_TTL_MS
        return ageMs in 0..ttlMs
    }
}

/**
 * Maximum time the first Auto connect may spend seeding fallback order with
 * direct TCP evidence. Partial results are useful; uncompleted probes remain
 * unknown rather than being recorded as failures.
 */
internal const val AUTO_FALLBACK_PREFLIGHT_BUDGET_MS = 5_000L

/**
 * Bounded first-connect preflight for Auto mode.
 *
 * sing-box starts URLTest with the first candidate before it has any proxy
 * history. When there is no fresh effective success, probe candidates that
 * have no fresh verdict yet and return only terminal results. TCP is a
 * reachability hint, not proof of a working proxy, so callers may use these
 * results to order candidates but must never remove candidates from the group.
 *
 * A fresh Proxy failure remains stronger than TCP success and is not
 * overwritten by this underlay-only check. A fresh successful Proxy verdict,
 * or a TCP success without a fresh Proxy verdict, avoids the extra delay.
 */
internal suspend fun <T> probeUnknownAutoFallbackCandidates(
    candidates: List<T>,
    idOf: (T) -> String,
    hostOf: (T) -> String,
    portOf: (T) -> Int,
    verdicts: Map<String, Map<LatencyMethod, NodeLatency>>,
    nowMs: Long,
    budgetMs: Long = AUTO_FALLBACK_PREFLIGHT_BUDGET_MS,
    probe: suspend (String, Int) -> Int?,
): Map<String, Int?> {
    require(budgetMs > 0) { "preflight budget must be positive" }

    val freshById = candidates.associate { candidate ->
        val byMethod = verdicts[idOf(candidate)].orEmpty()
        val proxy = byMethod[LatencyMethod.Proxy]?.takeIf { it.isFresh(nowMs) }
        val tcp = byMethod[LatencyMethod.Tcp]?.takeIf { it.isFresh(nowMs) }
        idOf(candidate) to (proxy to tcp)
    }

    val hasFreshSuccess = freshById.values.any { (proxy, tcp) ->
        proxy?.latencyMs != null || (proxy == null && tcp?.latencyMs != null)
    }
    if (hasFreshSuccess) return emptyMap()

    val pending = candidates.filter { candidate ->
        val (proxy, tcp) = freshById.getValue(idOf(candidate))
        proxy == null && tcp == null
    }
    if (pending.isEmpty()) return emptyMap()

    val completed = ConcurrentHashMap.newKeySet<String>()
    val successful = ConcurrentHashMap<String, Int>()

    withTimeoutOrNull(budgetMs) {
        coroutineScope {
            pending.map { candidate ->
                async {
                    val id = idOf(candidate)
                    val result = try {
                        probe(hostOf(candidate), portOf(candidate))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (result != null) successful[id] = result
                    completed.add(id)
                }
            }.awaitAll()
        }
    }

    return completed.associateWith { successful[it] }
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
                    val byMethod = getOrPut(row.nodeId) { emptyMap() }
                    put(row.nodeId, byMethod + (method to NodeLatency(row.latencyMs, row.checkedAtEpochMs, method)))
                }
            }
        }

    /** Compatibility/display projection: newest verdict per node; Proxy wins
     *  an exact timestamp tie because it is stronger end-to-end evidence. */
    val latencies: Flow<Map<String, NodeLatency>> =
        verdicts.map { byNode ->
            byNode.mapValues { (_, byMethod) ->
                byMethod.values.maxWithOrNull(
                    compareBy<NodeLatency> { it.checkedAtMs }
                        .thenBy { it.method == LatencyMethod.Proxy },
                )!!
            }
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
