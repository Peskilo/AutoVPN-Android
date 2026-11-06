package dev.typenil.vpnclient.ui.servers

import androidx.lifecycle.viewModelScope
import dev.typenil.vpnclient.core.common.LatencyProbe
import dev.typenil.vpnclient.core.common.VpnSocketProtector
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineEvent
import dev.typenil.vpnclient.core.engine.OutboundGroupInfo
import dev.typenil.vpnclient.core.engine.OutboundItemInfo
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.subscription.ClashYamlParser
import dev.typenil.vpnclient.core.subscription.SingBoxJsonParser
import dev.typenil.vpnclient.core.subscription.SubscriptionCandidateValidator
import dev.typenil.vpnclient.core.subscription.SubscriptionClassifier
import dev.typenil.vpnclient.core.subscription.SubscriptionExpiryNotifier
import dev.typenil.vpnclient.core.subscription.SubscriptionFetcher
import dev.typenil.vpnclient.core.subscription.SubscriptionParserDispatcher
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.subscription.UriListParser
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.core.vpn.IpCheckResult
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.core.vpn.PostStartHealthProbe
import dev.typenil.vpnclient.core.vpn.ServiceControl
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.LATENCY_TTL_MS
import dev.typenil.vpnclient.data.db.NodeLatencyEntity
import dev.typenil.vpnclient.data.NodeLatencyRepository
import dev.typenil.vpnclient.data.db.FakeNodePreferenceDao
import dev.typenil.vpnclient.data.db.FakeNodeLatencyDao
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import java.net.InetAddress
import java.net.ServerSocket
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connected-mode urlTest run tests for [ServersViewModel]: a real
 * [ConnectionManager] over a fake engine, virtual time for the 15 s bound.
 * Assertions read the public `uiState`/`testing` surface — the badge inputs
 * (delay map + covered-tag marks) are the user-visible contract.
 *
 * Coverage = members of `type == "urltest"` groups only; freshness =
 * per-tag `urlTestTime` strictly newer than the pre-dispatch baseline;
 * cancellation = any non-Connected state, status-channel suppression, or
 * ViewModel teardown — cancelled runs revert unmeasured tags to untested
 * ("—"), never "timeout".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val node =
        NodeSummary(
            id = "n1",
            name = "Node n1",
            protocol = ProtocolType.VLESS,
            server = "example.invalid",
        )

    private class FakeServiceControl : ServiceControl {
        override fun prepareVpn(): android.content.Intent? = null

        override fun startConnectService() = Unit

        override fun startDisconnectService() = Unit

        override fun stopVpnService(): Boolean = false
    }

    private class FakeNodeConfigProvider(
        config: EngineConfig,
        private val selected: MutableStateFlow<String?>,
    ) : NodeConfigProvider {
        private val config = config

        override suspend fun compileSelected(): EngineConfig = config

        override val selectedNodeId: Flow<String?> get() = selected

        override suspend fun isSelectionAllowed(id: String): Boolean = true

        override suspend fun nodeSummary(id: String): NodeSummary? = null

        override val enabledNodeSetFingerprint: Flow<String> = MutableStateFlow("fp")
        override val compiledNodeSetFingerprint: StateFlow<String?> = MutableStateFlow(null)
        override var underlayHasIpv6: Boolean = true

        private var underlayEpoch = 0L
        private var underlayReported = false

        override fun acquireUnderlayEpoch(): Long {
            underlayEpoch++
            underlayReported = false
            return underlayEpoch
        }

        override fun releaseUnderlayEpoch(epoch: Long) {
            if (epoch == underlayEpoch) underlayReported = false
        }

        override fun reportUnderlay(hasIpv6: Boolean) {
            underlayHasIpv6 = hasIpv6
            underlayReported = true
        }
    }

    private class FakeSettings : SubscriptionSettings {
        override val hwidConsent = MutableStateFlow(dev.typenil.vpnclient.core.subscription.HwidConsent.Allowed)
        override suspend fun setHwidConsent(consent: dev.typenil.vpnclient.core.subscription.HwidConsent) {
            hwidConsent.value = consent
        }
        val selected = MutableStateFlow<String?>(null)

        override suspend fun getOrCreateHwid() = "test-hwid"

        override val selectedNodeId: Flow<String?> get() = selected

        override suspend fun setSelectedNodeId(id: String?) {
            selected.value = id
        }

        override suspend fun clearSelectedNodeIdIf(expected: String) {
            if (selected.value == expected) selected.value = null
        }

        override val autoRefreshMinutes: Flow<Int> = MutableStateFlow(-1)
        override val expiryAlerted: Flow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun markExpiryAlerted(key: String) = Unit
    }

    private class FakeEngine : VpnEngine {
        val groupsFlow = MutableStateFlow<List<OutboundGroupInfo>>(emptyList())
        val statusUpdatesFlow = MutableStateFlow(true)
        val urlTestCalls = mutableListOf<String>()

        /** Hook run inside [urlTest] — push a synchronous groups update or
         *  `awaitCancellation()` to emulate a hanging dispatch. */
        var onUrlTest: suspend (String) -> Unit = {}

        override val stats: Flow<TrafficStats> = MutableSharedFlow()
        override val events: Flow<EngineEvent> = MutableSharedFlow()
        override val groups: StateFlow<List<OutboundGroupInfo>> get() = groupsFlow
        override val connections: StateFlow<List<ConnectionInfo>> =
            MutableStateFlow(emptyList())
        override val statusUpdatesEnabled: StateFlow<Boolean> get() = statusUpdatesFlow

        override suspend fun validate(config: EngineConfig) = Unit

        override suspend fun start(config: EngineConfig) = Unit

        override suspend fun stop() = Unit

        override suspend fun onUnderlyingNetworkChanged() = Unit

        override suspend fun selectOutbound(
            groupTag: String,
            outboundTag: String,
        ) = true

        override suspend fun urlTest(groupTag: String) {
            urlTestCalls += groupTag
            onUrlTest(groupTag)
        }

        override suspend fun closeConnection(id: String) = true
    }

    private class FakeNodeDao : NodeDao() {
        val nodes = mutableMapOf<String, NodeEntity>()

        /** Mirrors Room's re-emission on writes — a revision tick makes
         *  post-mutation state observable. */
        private val revision = MutableStateFlow(0)

        private fun bump() {
            revision.value++
        }

        override suspend fun forSubscription(subscriptionId: Long) =
            nodes.values.filter { it.subscriptionId == subscriptionId }

        override fun observeEnabled(): Flow<List<NodeEntity>> =
            revision.map { nodes.values.sortedBy { it.position } }

        override suspend fun getEnabled() = nodes.values.toList()

        override fun observeUsable(): Flow<List<NodeEntity>> = observeEnabled()

        override suspend fun getUsable() = getEnabled()

        override suspend fun get(id: String) = nodes[id]

        override fun observeForSubscriptionUrl(url: String) =
            revision.map { nodes.values.toList() }

        override suspend fun delete(id: String) {
            nodes.remove(id)
            bump()
        }

        override suspend fun upsertAll(nodes: List<NodeEntity>) {
            nodes.forEach { this.nodes[it.id] = it }
            bump()
        }

        override suspend fun upsert(node: NodeEntity) {
            nodes[node.id] = node
            bump()
        }

        override suspend fun deleteForSubscription(subscriptionId: Long) {
            nodes.values.removeAll { it.subscriptionId == subscriptionId }
            bump()
        }

        override suspend fun countForSubscription(subscriptionId: Long) =
            nodes.values.count { it.subscriptionId == subscriptionId }
    }

    private class FakeSubscriptionDao : SubscriptionDao {
        val subs = mutableMapOf<Long, SubscriptionEntity>()
        private val revision = MutableStateFlow(0)

        override fun observeAll(): Flow<List<SubscriptionEntity>> =
            revision.map { subs.values.toList() }

        override suspend fun get(id: Long) = subs[id]

        override suspend fun findIdByUrl(url: String) =
            subs.values.firstOrNull { it.url == url }?.id

        override suspend fun getAll() = subs.values.toList()

        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            revision.value++
            return entity.id
        }

        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
            revision.value++
        }

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) = Unit

        override suspend fun updateName(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun updateRefreshPolicy(
            id: Long,
            policy: String,
            fixedMinutes: Int?,
        ) = Unit

        override suspend fun delete(id: Long) {
            subs.remove(id)
            revision.value++
        }

        override suspend fun markAttempt(
            id: Long,
            attemptAt: Long,
            error: String?,
        ) = Unit

        override suspend fun markSuccess(
            id: Long,
            updatedAt: Long,
            attemptAt: Long,
            userInfoJson: String?,
            supportUrl: String?,
            updateIntervalMinutes: Int?,
            announce: String?,
            updateAlways: Boolean,
            fallbackUrl: String?,
        ) = Unit

        override suspend fun updateUrlAndMarkSuccess(
            id: Long,
            url: String,
            updatedAt: Long,
            attemptAt: Long,
            userInfoJson: String?,
            supportUrl: String?,
            updateIntervalMinutes: Int?,
            announce: String?,
            updateAlways: Boolean,
            fallbackUrl: String?,
        ) = Unit
    }

    private val settings = FakeSettings()
    private val nodeDao = FakeNodeDao()
    private val subscriptionDao = FakeSubscriptionDao()
    private val nodePreferenceDao = FakeNodePreferenceDao()
    private val socketProtector = VpnSocketProtector()
    private val latencyProbe = LatencyProbe(socketProtector, dispatcher, dispatcher) { loopback() }
    private val latencyDao = FakeNodeLatencyDao()
    private var clockMs = 1_000_000L
    private val latencyRepository = NodeLatencyRepository(latencyDao) { clockMs }

    private lateinit var engine: FakeEngine
    private lateinit var manager: ConnectionManager
    private lateinit var viewModel: ServersViewModel

    private fun nodeEntity(
        id: String,
        position: Int,
    ) = NodeEntity(
        id = id,
        subscriptionId = 1L,
        name = "Node $id",
        protocol = "VLESS",
        server = "127.0.0.1",
        port = 1,
        outboundJson = "{}",
        rawUri = null,
        position = position,
    )

    private fun item(
        tag: String,
        delay: Int? = null,
        time: Long = 0L,
    ) = OutboundItemInfo(tag = tag, type = "vless", urlTestDelayMs = delay, urlTestTime = time)

    /** The compiled shape: urltest group `auto` over [covered], plus the
     *  `proxy` selector carrying [selectorItems] — selector membership is
     *  NOT urltest coverage. */
    private fun groups(
        covered: List<OutboundItemInfo>,
        selectorItems: List<OutboundItemInfo> = covered,
    ) = listOf(
        OutboundGroupInfo("auto", "urltest", selectable = true, selected = "n1", items = covered),
        OutboundGroupInfo("proxy", "selector", selectable = true, selected = "n1", items = selectorItems),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        manager =
            ConnectionManager(
                FakeServiceControl(),
                FakeNodeConfigProvider(
                    EngineConfig(configJson = "{}", node = node),
                    settings.selected,
                ),
                PostStartHealthProbe { IpCheckResult(null, null, "unexpected response") },
            )
        subscriptionDao.subs[1L] =
            SubscriptionEntity(
                id = 1L,
                name = "TestSub",
                url = "https://sub.invalid/feed",
                createdAtEpochMs = 1,
                lastUpdatedAtEpochMs = 1,
                lastAttemptAtEpochMs = null,
                lastError = null,
                enabled = true,
                userInfoJson = null,
                supportUrl = null,
                updateIntervalMinutes = null,
                announce = null,
                fallbackUrl = null,
            )
        viewModel = buildViewModel(latencyProbe)
    }

    private fun buildViewModel(probe: LatencyProbe): ServersViewModel =
            ServersViewModel(
                settings,
                manager,
                nodeDao,
                nodePreferenceDao,
                probe,
                SubscriptionRepository(
                    subscriptionDao = subscriptionDao,
                    nodeDao = nodeDao,
                    nodePreferenceDao = nodePreferenceDao,
                    fetcher = SubscriptionFetcher(OkHttpClient()),
                    classifier = SubscriptionClassifier(),
                    dispatcher =
                        SubscriptionParserDispatcher(
                            UriListParser(),
                            SingBoxJsonParser(),
                            ClashYamlParser(),
                        ),
                    validator =
                        object : SubscriptionCandidateValidator {
                            override suspend fun validate(nodes: List<ProxyNode>) = Unit
                        },
                    transactions =
                        object : DbTransactionRunner {
                            override suspend fun <T> run(block: suspend () -> T): T = block()
                        },
                    scheduler =
                        object : SubscriptionRefreshScheduler {
                            override suspend fun schedule(
                                subscriptionId: Long,
                                providerMinutes: Int?,
                                policy: RefreshPolicy,
                                userOverrideMinutes: Int,
                                enabled: Boolean,
                            ) = Unit

                            override fun cancel(subscriptionId: Long) = Unit
                        },
                    settings = settings,
                    expiryNotifier =
                        object : SubscriptionExpiryNotifier {
                            override fun notifyExpiring(
                                subscriptionId: Long,
                                subscriptionName: String,
                                expireEpochSeconds: Long,
                            ): Boolean = true
                        },
                    uriListParser = UriListParser(),
                ),
                latencyRepository,
            )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState is WhileSubscribed — collect it for the test's duration. */
    private fun TestScope.collectUi() = backgroundScope.launch { viewModel.uiState.collect {} }

    private fun connectToRunning(): Long = attachAndStart(engine)

    /** Attach [attached] as a fresh session and publish Connected. */
    private fun attachAndStart(attached: FakeEngine): Long {
        manager.connect()
        testScope.advanceUntilIdle()
        val generation = manager.pendingSession!!.generation
        manager.attachEngine(attached, generation)
        testScope.advanceUntilIdle()
        manager.onServiceStarted(generation)
        testScope.advanceUntilIdle()
        return generation
    }

    /** Fresh engine + fresh session — the reconnect path a retained
     *  ViewModel sees. */
    private fun reconnectToRunning(): FakeEngine {
        val fresh = FakeEngine()
        attachAndStart(fresh)
        return fresh
    }

    /** Send the disconnect intent only — publishes Stopping, the live
     *  observation a mid-run cancel keys on. Teardown convergence is left
     *  to [settleStopped] so callers can assert promptness first. */
    private fun sendDisconnect() {
        manager.disconnect()
        testScope.runCurrent()
    }

    /** Service reports teardown done — Idle + detached engine. */
    private fun settleStopped(generation: Long) {
        testScope.advanceUntilIdle()
        manager.onServiceStopped(generation)
        testScope.advanceUntilIdle()
    }

    /** Disconnect and let teardown converge — Idle + detached engine. */
    private fun disconnectToIdle(generation: Long) {
        manager.disconnect()
        testScope.advanceUntilIdle()
        manager.onServiceStopped(generation)
        testScope.advanceUntilIdle()
    }

    /** Run-end wait for runs that touch real dispatchers (the disconnected
     *  TCP probe path rides Dispatchers.IO): alternate virtual pumping with
     *  real millisecond waits, bounded. */
    private fun TestScope.awaitRunEnd(timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (viewModel.testing.value && System.nanoTime() < deadline) {
            advanceUntilIdle()
            Thread.sleep(2)
        }
        assertFalse("latency run did not finish", viewModel.testing.value)
    }

    // ---- the six mandatory scenarios ----

    @Test
    fun `early all-terminal run ends without the bound and shows fresh delays`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            connectToRunning()
            // n1 carries a stale result (time=500) — it must NOT display as
            // this run's outcome; only a strictly newer time counts.
            engine.groupsFlow.value =
                groups(listOf(item("n1", delay = 90, time = 500), item("n2")))
            advanceUntilIdle()
            assertEquals(90, viewModel.uiState.value.delays["n1"])

            engine.onUrlTest = {
                // A synchronous result push inside the dispatch call — the
                // readiness barrier must already be observing.
                engine.groupsFlow.value =
                    groups(listOf(item("n1", delay = 95, time = 800), item("n2", delay = 140, time = 800)))
            }
            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()

            // All-terminal completion must not have consumed virtual time —
            // a run that only finishes at the bound would pass a blind
            // advanceUntilIdle().
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            assertEquals(listOf("auto"), engine.urlTestCalls)
            val ui = viewModel.uiState.value
            assertTrue(ui.connected)
            assertEquals(95, ui.delays["n1"])
            assertEquals(140, ui.delays["n2"])
            assertTrue("n1" in ui.testedNodeIds && "n2" in ui.testedNodeIds)
        }

    @Test
    fun `stale engine delay is hidden while a run is in flight`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 90, time = 500)))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()

            // The floor suppresses the pre-run value during the run — it may
            // not render as this run's measurement.
            assertTrue(viewModel.testing.value)
            assertNull(viewModel.uiState.value.delays["n1"])

            // A push that still carries the old time stays suppressed.
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 90, time = 500)))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.delays["n1"])
        }

    @Test
    fun `baseline-zero node without a result waits the full bound then timeout`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)
            assertEquals(listOf("auto"), engine.urlTestCalls)

            // Baseline 0 / no result is ambiguous — it rides the bound,
            // not an early verdict.
            advanceTimeBy(ServersViewModel.URLTEST_RUN_TIMEOUT_MS - 1)
            runCurrent()
            assertTrue(viewModel.testing.value)

            // The timeout lands at exactly the bound — not earlier, and the
            // run does not outlive it.
            advanceTimeBy(1)
            runCurrent()
            assertFalse(viewModel.testing.value)
            assertEquals(t0 + ServersViewModel.URLTEST_RUN_TIMEOUT_MS, testScheduler.currentTime)
            val ui = viewModel.uiState.value
            assertTrue("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])
        }

    @Test
    fun `cleared history on a measured baseline is an early terminal failure`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            connectToRunning()
            // n1 measured before (time=500); n2 never.
            engine.groupsFlow.value =
                groups(listOf(item("n1", delay = 90, time = 500), item("n2")))
            advanceUntilIdle()

            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()
            // The core cleared n1's history (time back to 0) and measured n2.
            engine.groupsFlow.value =
                groups(listOf(item("n1", delay = null, time = 0), item("n2", delay = 140, time = 800)))
            runCurrent()

            // Both verdicts were terminal before the bound — no virtual
            // time may have been consumed waiting for it.
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            val ui = viewModel.uiState.value
            assertTrue("n1" in ui.testedNodeIds) // failure verdict → timeout badge
            assertNull(ui.delays["n1"])
            assertEquals(140, ui.delays["n2"])
            assertTrue("n2" in ui.testedNodeIds)
        }

    @Test
    fun `disconnect mid-run cancels — arrived results stay, unfinished revert`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            val generation = connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1"), item("n2")))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            // n1 lands a fresh result; n2 is still pending at disconnect.
            engine.groupsFlow.value =
                groups(listOf(item("n1", delay = 150, time = 900), item("n2")))
            runCurrent()

            // Stopping is itself a cancel trigger — the run ends promptly,
            // without consuming the bound.
            val t0 = testScheduler.currentTime
            sendDisconnect()
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            settleStopped(generation)

            val ui = viewModel.uiState.value
            assertFalse(ui.connected)
            assertFalse(viewModel.testing.value)
            // Retained fresh result survives the emptied live surface.
            assertEquals(150, ui.delays["n1"])
            assertTrue("n1" in ui.testedNodeIds)
            // n2 never received a result → untested "—", never "timeout".
            assertFalse("n2" in ui.testedNodeIds)
            assertNull(ui.delays["n2"])
        }

    @Test
    fun `screen-off suppression cancels the run — unfinished revert`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)

            val t0 = testScheduler.currentTime
            engine.statusUpdatesFlow.value = false // screen off → channel suppressed
            runCurrent()

            // Prompt cancel — not a run that happened to reach the bound.
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])

            // Screen back on does not resurrect the cancelled run's marks.
            engine.statusUpdatesFlow.value = true
            advanceUntilIdle()
            assertFalse("n1" in viewModel.uiState.value.testedNodeIds)
        }

    @Test
    fun `nodes outside urltest groups stay untested`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("sel", 1)))
            collectUi()
            connectToRunning()
            // "sel" exists only inside the selector group — not covered,
            // and a positive delay on it must NOT display: the connected
            // surface renders urltest-group members only.
            engine.groupsFlow.value =
                groups(
                    covered = listOf(item("n1")),
                    selectorItems = listOf(item("n1"), item("sel", delay = 60, time = 700)),
                )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.delays["sel"])

            engine.onUrlTest = {
                engine.groupsFlow.value =
                    groups(
                        covered = listOf(item("n1", delay = 120, time = 800)),
                        selectorItems = listOf(item("n1", delay = 120, time = 800), item("sel", delay = 60, time = 700)),
                    )
            }
            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()

            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            val ui = viewModel.uiState.value
            assertEquals(120, ui.delays["n1"])
            assertTrue("n1" in ui.testedNodeIds)
            assertFalse("sel" in ui.testedNodeIds)
            assertNull(ui.delays["sel"])
        }

    @Test
    fun `no urltest groups means nothing is dispatched or covered`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value =
                listOf(
                    OutboundGroupInfo("proxy", "selector", selectable = true, selected = "n1", items = listOf(item("n1", delay = 50, time = 400))),
                )
            advanceUntilIdle()

            viewModel.testLatency()
            advanceUntilIdle()

            assertFalse(viewModel.testing.value)
            assertTrue(engine.urlTestCalls.isEmpty())
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            // A selector-group delay is not urltest coverage — no badge.
            assertNull(ui.delays["n1"])
        }

    // ---- supervisor-required regression cases ----

    @Test
    fun `repeated presses do not start a parallel run`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            // Burst before the launched coroutine ever runs — the CAS claim
            // must already hold.
            viewModel.testLatency()
            viewModel.testLatency()
            viewModel.testLatency()
            runCurrent()
            assertEquals(listOf("auto"), engine.urlTestCalls)

            // More presses while the run is waiting — still one dispatch.
            viewModel.testLatency()
            viewModel.testLatency()
            advanceUntilIdle() // fires the bound

            assertFalse(viewModel.testing.value)
            assertEquals(listOf("auto"), engine.urlTestCalls)
            assertTrue("n1" in viewModel.uiState.value.testedNodeIds)
        }

    @Test
    fun `synchronous result inside dispatch ends the run early`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            // The push lands before urlTest returns — before any suspension
            // point in the dispatch loop.
            engine.onUrlTest = {
                engine.groupsFlow.value = groups(listOf(item("n1", delay = 77, time = 800)))
            }
            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()

            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            assertEquals(77, viewModel.uiState.value.delays["n1"])
            assertTrue("n1" in viewModel.uiState.value.testedNodeIds)
        }

    @Test
    fun `disconnect interrupts a hanging dispatch without touching the next group`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            val generation = connectToRunning()
            // Two urltest groups — dispatch must not continue past a cancel.
            engine.groupsFlow.value =
                listOf(
                    OutboundGroupInfo("auto", "urltest", true, "n1", listOf(item("n1"))),
                    OutboundGroupInfo("auto2", "urltest", true, "n2", listOf(item("n2"))),
                )
            engine.onUrlTest = { awaitCancellation() }
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)
            assertEquals(listOf("auto"), engine.urlTestCalls) // hanging in the first call

            val t0 = testScheduler.currentTime
            sendDisconnect()

            // The wedged dispatch is cancelled promptly — the run does not
            // ride the bound waiting for it.
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            settleStopped(generation)

            assertEquals(listOf("auto"), engine.urlTestCalls)
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            assertFalse("n2" in ui.testedNodeIds)
        }

    @Test
    fun `screen-off interrupts a hanging dispatch the same way`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value =
                listOf(
                    OutboundGroupInfo("auto", "urltest", true, "n1", listOf(item("n1"))),
                    OutboundGroupInfo("auto2", "urltest", true, "n2", listOf(item("n2"))),
                )
            engine.onUrlTest = { awaitCancellation() }
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)

            val t0 = testScheduler.currentTime
            engine.statusUpdatesFlow.value = false
            runCurrent()

            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            assertEquals(listOf("auto"), engine.urlTestCalls)
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            assertFalse("n2" in ui.testedNodeIds)
        }

    @Test
    fun `disconnect then quick reconnect keeps the cancelled run reverted`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            val generation = connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()

            manager.disconnect()
            testScope.advanceUntilIdle() // watcher observes Stopping → cancel
            manager.onServiceStopped(generation)
            testScope.advanceUntilIdle()

            // Fast reconnect — a new session on a fresh engine.
            val engine2 = FakeEngine()
            manager.connect()
            testScope.advanceUntilIdle()
            val generation2 = manager.pendingSession!!.generation
            manager.attachEngine(engine2, generation2)
            testScope.advanceUntilIdle()
            manager.onServiceStarted(generation2)
            testScope.advanceUntilIdle()

            // The reverted marks must not flip to "timeout" post-hoc.
            assertTrue(viewModel.uiState.value.connected)
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])
        }

    @Test
    fun `a zero delay with a newer time is not a success — rides the bound`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()

            val t0 = testScheduler.currentTime
            viewModel.testLatency()
            runCurrent()
            // delay=0 with a fresh timestamp is NOT terminal-success — the
            // strict >0 check keeps it pending until the bound.
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 0, time = 800)))
            runCurrent()
            assertTrue(viewModel.testing.value)

            advanceTimeBy(ServersViewModel.URLTEST_RUN_TIMEOUT_MS - 1)
            runCurrent()
            assertTrue(viewModel.testing.value)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(viewModel.testing.value)
            assertEquals(t0 + ServersViewModel.URLTEST_RUN_TIMEOUT_MS, testScheduler.currentTime)
            val ui = viewModel.uiState.value
            assertTrue("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])
        }

    @Test
    fun `viewmodel clear cancels the run promptly — arrived kept, unfinished reverted`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1), nodeEntity("n3", 2)))
            collectUi()
            connectToRunning()
            // n1 has a recorded baseline (time=500); n2/n3 never measured.
            engine.groupsFlow.value =
                groups(listOf(item("n1", delay = 90, time = 500), item("n2"), item("n3")))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            // Arrived before teardown: n2 success (fresh time), n1 failure
            // (baseline>0, history cleared). n3 stays pending.
            engine.groupsFlow.value =
                groups(
                    listOf(
                        item("n1", delay = null, time = 0),
                        item("n2", delay = 150, time = 900),
                        item("n3"),
                    ),
                )
            runCurrent()
            assertTrue(viewModel.testing.value) // n3 keeps the run alive

            val t0 = testScheduler.currentTime
            viewModel.viewModelScope.cancel()
            runCurrent()

            // Prompt teardown — the commit is non-suspending and must not
            // wait for the bound.
            assertFalse(viewModel.testing.value)
            assertEquals(t0, testScheduler.currentTime)
            // Observable commit outcome: arrived verdicts are preserved,
            // the never-measured tag reverted to untested — not a spinner
            // check alone.
            val probe = viewModel.probeSurface.value
            assertEquals(150, probe.urlTestDelays["n2"])
            assertTrue("n1" in probe.urlTested)
            assertTrue("n2" in probe.urlTested)
            assertFalse("n3" in probe.urlTested)
        }

    @Test
    fun `mixed retained urltest and TCP verdicts report both caption sources`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("tcp", 1)))
            collectUi()

            // Disconnected TCP run first: the protector refuses, so each
            // probe is a fast failed verdict — tested mark, no delay.
            socketProtector.install { false }
            viewModel.testLatency()
            awaitRunEnd()
            val disconnectedUi = viewModel.uiState.value
            assertTrue("n1" in disconnectedUi.testedNodeIds)
            assertTrue("tcp" in disconnectedUi.testedNodeIds)

            // Connect and run urltest — only n1 is covered; its fresh result
            // is retained while the TCP mark on the uncovered node survives.
            val generation = connectToRunning()
            engine.groupsFlow.value =
                groups(
                    covered = listOf(item("n1")),
                    selectorItems = listOf(item("n1"), item("tcp")),
                )
            advanceUntilIdle()
            engine.onUrlTest = {
                engine.groupsFlow.value =
                    groups(
                        covered = listOf(item("n1", delay = 150, time = 900)),
                        selectorItems = listOf(item("n1", delay = 150, time = 900), item("tcp")),
                    )
            }
            viewModel.testLatency()
            advanceUntilIdle()
            disconnectToIdle(generation)

            val ui = viewModel.uiState.value
            assertFalse(ui.connected)
            // n1: retained urltest verdict + value. tcp: TCP verdict only.
            assertEquals(150, ui.delays["n1"])
            assertTrue("n1" in ui.testedNodeIds)
            assertTrue("tcp" in ui.testedNodeIds)
            assertNull(ui.delays["tcp"])
        }

    // ---- engine-session scoping: a fresh engine must not inherit the
    // previous session's verdicts or freshness floors ----

    @Test
    fun `reconnect after a successful run inherits neither timeout nor floors`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            val generation = connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 90, time = 500)))
            advanceUntilIdle()

            engine.onUrlTest = {
                engine.groupsFlow.value = groups(listOf(item("n1", delay = 150, time = 900)))
            }
            viewModel.testLatency()
            runCurrent()
            assertFalse(viewModel.testing.value)
            assertEquals(150, viewModel.uiState.value.delays["n1"])
            assertTrue("n1" in viewModel.uiState.value.testedNodeIds)

            disconnectToIdle(generation)
            // Retention while disconnected is intended — the verdict stays.
            assertEquals(150, viewModel.uiState.value.delays["n1"])
            assertTrue("n1" in viewModel.uiState.value.testedNodeIds)

            val engine2 = reconnectToRunning()
            // Fresh engine history: n1 was never measured this session — the
            // old session's tested mark must not render as a timeout.
            engine2.groupsFlow.value = groups(listOf(item("n1", delay = null, time = 0)))
            advanceUntilIdle()
            var ui = viewModel.uiState.value
            assertTrue(ui.connected)
            assertFalse("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])

            // The dead session's floor (baseline 500) must not gate the new
            // engine: an EQUAL timestamp result displays…
            engine2.groupsFlow.value = groups(listOf(item("n1", delay = 60, time = 500)))
            advanceUntilIdle()
            ui = viewModel.uiState.value
            assertEquals(60, ui.delays["n1"])
            // …and so does a strictly LOWER one.
            engine2.groupsFlow.value = groups(listOf(item("n1", delay = 55, time = 300)))
            advanceUntilIdle()
            assertEquals(55, viewModel.uiState.value.delays["n1"])
        }

    @Test
    fun `reconnect after a failed run does not inherit the failure mark`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            val generation = connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 90, time = 500)))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            // Terminal failure: baseline 500, the core cleared the history.
            engine.groupsFlow.value = groups(listOf(item("n1", delay = null, time = 0)))
            runCurrent()
            assertFalse(viewModel.testing.value)
            assertTrue("n1" in viewModel.uiState.value.testedNodeIds)
            assertNull(viewModel.uiState.value.delays["n1"])

            disconnectToIdle(generation)
            val engine2 = reconnectToRunning()
            // Unmeasured in the new session — the stale failure must not
            // follow it as a fake timeout.
            engine2.groupsFlow.value = groups(listOf(item("n1", delay = null, time = 0)))
            advanceUntilIdle()

            val ui = viewModel.uiState.value
            assertTrue(ui.connected)
            assertFalse("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])
        }

    @Test
    fun `reconnect after a cancelled run keeps the new session floor-free`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0)))
            collectUi()
            val generation = connectToRunning()
            // Nonzero baseline: the cancelled run leaves a floor behind.
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 90, time = 500)))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)
            // Disconnect mid-run — n1 was never measured; its mark reverts.
            disconnectToIdle(generation)
            assertFalse("n1" in viewModel.uiState.value.testedNodeIds)

            val engine2 = reconnectToRunning()
            // A delay with time EQUAL to the dead run's floor is a fresh
            // session result — it must display, not be suppressed.
            engine2.groupsFlow.value = groups(listOf(item("n1", delay = 80, time = 500)))
            advanceUntilIdle()
            assertEquals(80, viewModel.uiState.value.delays["n1"])

            engine2.groupsFlow.value = groups(listOf(item("n1", delay = null, time = 0)))
            advanceUntilIdle()
            val ui = viewModel.uiState.value
            assertFalse("n1" in ui.testedNodeIds)
            assertNull(ui.delays["n1"])
        }

    // ---- persisted verdicts, Test-all progress and cancel ----

    private fun loopback(): InetAddress = InetAddress.getByName("127.0.0.1")

    /** A probe whose DNS step hangs for [hang] hosts — a run that is
     *  provably still in flight — and resolves everything else to loopback.
     *  All on the test dispatcher: virtual time, no real waits. */
    private fun gatedProbe(hang: Set<String>) =
        LatencyProbe(socketProtector, dispatcher, dispatcher) { host ->
            if (host in hang) awaitCancellation() else loopback()
        }

    @Test
    fun `connected run stores proxy verdicts with the run clock and a failure as null`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1"), item("n2")))
            advanceUntilIdle()
            clockMs = 5_000_000L
            engine.onUrlTest = {
                engine.groupsFlow.value =
                    groups(listOf(item("n1", delay = 95, time = 800), item("n2")))
            }

            viewModel.testLatency()
            advanceUntilIdle() // n2 never answers: rides the bound, a real timeout

            assertEquals(NodeLatencyEntity("n1", 95, 5_000_000L, "proxy"), latencyDao.rows["n1"])
            assertEquals(NodeLatencyEntity("n2", null, 5_000_000L, "proxy"), latencyDao.rows["n2"])
            assertTrue(("n1" to "tcp") in latencyDao.allRows)
            assertTrue(("n1" to "proxy") in latencyDao.allRows)
        }

    @Test
    fun `connected run cut short by disconnect stores only what arrived`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1)))
            val before = NodeLatencyEntity("n2", 77, 1L, "tcp")
            latencyDao.upsertAll(listOf(before))
            collectUi()
            val generation = connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1"), item("n2")))
            advanceUntilIdle()

            viewModel.testLatency()
            runCurrent()
            engine.groupsFlow.value = groups(listOf(item("n1", delay = 150, time = 900), item("n2")))
            runCurrent()
            sendDisconnect()
            settleStopped(generation)

            assertEquals(150, latencyDao.rows["n1"]?.latencyMs)
            // n2 was never reached: its earlier verdict is untouched, not "timeout".
            assertEquals(before, latencyDao.rows["n2"])
        }

    @Test
    fun `direct run stores TCP verdicts, a closed port is a stored failure`() =
        testScope.runTest {
            val open = ServerSocket(0, 50, loopback())
            val closedPort = ServerSocket(0, 1, loopback()).use { it.localPort }
            try {
                nodeDao.upsertAll(
                    listOf(
                        nodeEntity("up", 0).copy(port = open.localPort),
                        nodeEntity("down", 1).copy(port = closedPort),
                    ),
                )
                collectUi()
                clockMs = 7_000_000L

                viewModel.testLatency()
                awaitRunEnd()

                val up = latencyDao.rows.getValue("up")
                assertTrue(up.latencyMs != null)
                assertEquals(7_000_000L, up.checkedAtEpochMs)
                assertEquals("tcp", up.method)
                assertEquals(NodeLatencyEntity("down", null, 7_000_000L, "tcp"), latencyDao.rows["down"])
                assertNull(viewModel.progress.value)
            } finally {
                open.close()
            }
        }

    @Test
    fun `cancel stops a direct run - arrived kept, unreached node keeps its stored verdict`() =
        testScope.runTest {
            val open = ServerSocket(0, 50, loopback())
            try {
                nodeDao.upsertAll(
                    listOf(
                        nodeEntity("a", 0).copy(server = "a.test", port = open.localPort),
                        nodeEntity("b", 1).copy(server = "b.test"),
                    ),
                )
                val earlierB = NodeLatencyEntity("b", 222, 1L, "tcp")
                latencyDao.upsertAll(listOf(NodeLatencyEntity("a", 11, 1L, "tcp"), earlierB))
                viewModel = buildViewModel(gatedProbe(hang = setOf("b.test")))
                collectUi()

                viewModel.testLatency()
                runCurrent()
                assertTrue(viewModel.testing.value)
                assertEquals(LatencyProgress(done = 1, total = 2), viewModel.progress.value)

                clockMs = 9_000_000L
                viewModel.cancelLatencyTest()
                runCurrent()

                assertFalse(viewModel.testing.value)
                assertNull(viewModel.progress.value)
                assertEquals(9_000_000L, latencyDao.rows.getValue("a").checkedAtEpochMs)
                assertEquals(earlierB, latencyDao.rows["b"])
                val ui = viewModel.uiState.value
                assertNull(ui.delays["b"])
                assertFalse("b" in ui.testedNodeIds)
                assertEquals(222, ui.storedLatency["b"]?.latencyMs)
            } finally {
                open.close()
            }
        }

    @Test
    fun `leaving the screen cancels a direct run but not the engine's urltest`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0).copy(server = "a.test")))
            viewModel = buildViewModel(gatedProbe(hang = setOf("a.test")))
            collectUi()
            viewModel.testLatency()
            runCurrent()
            assertTrue(viewModel.testing.value)
            viewModel.onScreenLeft()
            runCurrent()
            assertFalse(viewModel.testing.value)

            viewModel = buildViewModel(latencyProbe)
            collectUi()
            connectToRunning()
            engine.groupsFlow.value = groups(listOf(item("n1")))
            advanceUntilIdle()
            viewModel.testLatency()
            runCurrent()
            viewModel.onScreenLeft()
            runCurrent()
            assertTrue(viewModel.testing.value)
        }

    @Test
    fun `latency sort ranks fresh stored verdicts and sinks outdated ones`() =
        testScope.runTest {
            nodeDao.upsertAll(listOf(nodeEntity("n1", 0), nodeEntity("n2", 1), nodeEntity("n3", 2)))
            latencyDao.upsertAll(
                listOf(
                    NodeLatencyEntity("n1", 300, clockMs - 1_000, "tcp"),
                    // Fastest on paper, but older than the TTL: history, not a rank.
                    NodeLatencyEntity("n2", 10, clockMs - LATENCY_TTL_MS - 1, "tcp"),
                    NodeLatencyEntity("n3", 100, clockMs - 5_000, "proxy"),
                ),
            )
            collectUi()
            viewModel.setSortMode(ServerSortMode.Latency)
            advanceUntilIdle()

            val ui = viewModel.uiState.value
            assertEquals(listOf("n3", "n1", "n2"), ui.groups.flatMap { it.nodes }.map { it.id })
            assertEquals(10, ui.storedLatency["n2"]?.latencyMs)
        }
}
