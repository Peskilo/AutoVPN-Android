package dev.typenil.vpnclient.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-SQLite behavior the JVM fakes cannot show: the join that hides rows
 *  of vanished nodes, and survival across `replaceForSubscription`. */
@RunWith(AndroidJUnit4::class)
class NodeLatencyDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java,
            ).build()
    }

    @After
    fun close() = db.close()

    private fun node(id: String) =
        NodeEntity(id, 1L, id, "VLESS", "127.0.0.1", 443, "{}", null, 0)

    private fun visible() = runBlocking { db.nodeLatencyDao().observeLive().first().map { it.nodeId }.distinct().sorted() }

    private fun row(id: String) = NodeLatencyEntity(id, 10, 1L, "tcp")

    @Test
    fun tcpAndProxyRowsCoexistForOneNode() =
        runBlocking {
            db.nodeDao().upsertAll(listOf(node("a")))
            db.nodeLatencyDao().record(
                listOf(
                    NodeLatencyEntity("a\u001Ftcp", 40, 1L, "tcp"),
                    NodeLatencyEntity("a\u001Fproxy", 90, 2L, "proxy"),
                ),
            )

            val rows = db.nodeLatencyDao().observeLive().first()
            assertEquals(setOf("tcp", "proxy"), rows.map { it.method }.toSet())
            assertEquals(setOf("a"), rows.map { it.nodeId }.toSet())
        }

    @Test
    fun resultsSurviveRefreshOfUnchangedNodesAndDieWithRemovedOnes() =
        runBlocking {
            val nodes = db.nodeDao()
            nodes.upsertAll(listOf(node("a"), node("b")))
            db.nodeLatencyDao().record(listOf(row("a"), row("b")))
            assertEquals(listOf("a", "b"), visible())

            // A refresh delete+reinserts the subscription's nodes; "a" keeps
            // its id, "b" is gone. An FK cascade would have dropped both.
            nodes.replaceForSubscription(1L, listOf(node("a"), node("c")))

            assertEquals(listOf("a"), visible())
            db.nodeLatencyDao().record(listOf(row("c")))
            assertEquals(listOf("a", "c"), visible())
        }

    @Test
    fun recordForUnknownNodeLeavesNothingBehind() =
        runBlocking {
            db.nodeLatencyDao().record(listOf(row("ghost")))
            // The node appearing later must not resurrect a result that was
            // recorded while it did not exist.
            db.nodeDao().upsertAll(listOf(node("ghost")))
            assertEquals(emptyList<String>(), visible())
        }
}
