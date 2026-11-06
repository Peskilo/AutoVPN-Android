package dev.typenil.vpnclient.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [NodeLatencyDao] for unit tests. The SQL join/sweep against the
 *  nodes table is covered on a real Room database (androidTest); here
 *  [liveNodeIds], when set, plays the role of the `nodes` table. */
class FakeNodeLatencyDao : NodeLatencyDao() {
    private val state = MutableStateFlow<Map<Pair<String, String>, NodeLatencyEntity>>(emptyMap())

    /** Null = every row is visible and nothing is swept. */
    var liveNodeIds: Set<String>? = null

    /** All independent method rows, keyed by (node id, method). */
    val allRows: Map<Pair<String, String>, NodeLatencyEntity> get() = state.value

    /** Latest display projection retained for tests that only need one badge. */
    val rows: Map<String, NodeLatencyEntity>
        get() =
            state.value.values
                .groupBy { baseNodeId(it.nodeId) }
                .mapValues { (id, values) ->
                    values.maxWithOrNull(
                        compareBy<NodeLatencyEntity> { it.checkedAtEpochMs }
                            .thenBy { it.method == "proxy" },
                    )!!.copy(nodeId = id)
                }

    override fun observeLive(): Flow<List<NodeLatencyEntity>> =
        state.map { all ->
            all.values.mapNotNull { row ->
                val id = baseNodeId(row.nodeId)
                if (liveNodeIds?.contains(id) == false) null else row.copy(nodeId = id)
            }
        }

    override suspend fun deleteLegacy(nodeId: String, method: String) {
        state.value =
            state.value.filterNot { (key, row) ->
                key.first == nodeId && key.second == method && row.nodeId == nodeId
            }
    }

    override suspend fun upsertAll(rows: List<NodeLatencyEntity>) {
        state.value =
            state.value + rows.associateBy { baseNodeId(it.nodeId) to it.method }
    }

    override suspend fun deleteOrphans() {
        val live = liveNodeIds ?: return
        state.value = state.value.filterKeys { it.first in live }
    }

    private fun baseNodeId(storageKey: String): String = storageKey.substringBefore('\u001F')
}
