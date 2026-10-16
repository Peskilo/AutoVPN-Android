package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshOutcome
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.FakeNodePreferenceDao
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Refresh-ordering tests: a candidate is committed only after fetch → parse →
 * engine validation succeed; failures preserve last-known-good rows.
 * Real fetcher/classifier/dispatcher over MockWebServer; fake DAOs.
 */
class SubscriptionRepositoryTest {

    private val server = MockWebServer()

    private class FakeSubscriptionDao : SubscriptionDao {
        val subs = mutableMapOf<Long, SubscriptionEntity>()
        var successCalls = 0
        var attemptCalls = 0
        var lastAttemptError: String? = null

        override fun observeAll() = flow { emit(subs.values.toList()) }
        override suspend fun get(id: Long) = subs[id]
        override suspend fun findIdByUrl(url: String): Long? =
            subs.values.firstOrNull { it.url == url }?.id
        override suspend fun getAll() = subs.values.toList()
        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            return entity.id
        }
        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
        }
        override suspend fun delete(id: Long) { subs.remove(id) }
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            subs[id]?.let { subs[id] = it.copy(enabled = enabled) }
        }
        override suspend fun updateName(id: Long, name: String) {
            subs[id]?.let { subs[id] = it.copy(name = name) }
        }
        override suspend fun updateRefreshPolicy(
            id: Long,
            policy: String,
            fixedMinutes: Int?,
        ) {
            subs[id]?.let {
                subs[id] = it.copy(refreshPolicy = policy, refreshFixedMinutes = fixedMinutes)
            }
        }
        var urlSuccessCalls = 0
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
        ) {
            urlSuccessCalls++
            subs[id]?.let { subs[id] = it.copy(
                url = url,
                lastUpdatedAtEpochMs = updatedAt,
                lastAttemptAtEpochMs = attemptAt,
                lastError = null,
                userInfoJson = userInfoJson,
                supportUrl = supportUrl,
                updateIntervalMinutes = updateIntervalMinutes,
                announce = announce,
                updateAlways = updateAlways,
                fallbackUrl = fallbackUrl,
            ) }
        }
        override suspend fun markAttempt(id: Long, attemptAt: Long, error: String?) {
            attemptCalls++
            lastAttemptError = error
        }
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
        ) {
            successCalls++
            subs[id]?.let { subs[id] = it.copy(
                lastUpdatedAtEpochMs = updatedAt,
                lastAttemptAtEpochMs = attemptAt,
                lastError = null,
                userInfoJson = userInfoJson,
                supportUrl = supportUrl,
                updateIntervalMinutes = updateIntervalMinutes,
                announce = announce,
                updateAlways = updateAlways,
                fallbackUrl = fallbackUrl,
            ) }
        }
    }

    private class FakeNodeDao : NodeDao() {
        val nodes = mutableMapOf<String, NodeEntity>()
        var replaceCalls = 0

        override suspend fun forSubscription(subscriptionId: Long) =
            nodes.values.filter { it.subscriptionId == subscriptionId }
        override fun observeEnabled(): Flow<List<NodeEntity>> = flowOf(nodes.values.toList())
        override suspend fun getEnabled() = nodes.values.toList()
        /** Mirrors the SQL: enabled-sub nodes minus pref-disabled ones.
         *  [usableIds] is the test's view of node_preferences.isEnabled —
         *  null means "no pref rows", i.e. everything usable. */
        override fun observeUsable(): Flow<List<NodeEntity>> =
            flowOf(nodes.values.filter { usableIds?.contains(it.id) ?: true }.toList())
        override suspend fun getUsable() =
            nodes.values.filter { usableIds?.contains(it.id) ?: true }.toList()
        var usableIds: Set<String>? = null
        override suspend fun get(id: String) = nodes[id]
        override fun observeForSubscriptionUrl(url: String): Flow<List<NodeEntity>> =
            flowOf(nodes.values.toList())
        override suspend fun delete(id: String) {
            nodes.remove(id)
        }
        override suspend fun upsertAll(new: List<NodeEntity>) {
            new.forEach { nodes[it.id] = it }
        }
        override suspend fun upsert(node: NodeEntity) {
            nodes[node.id] = node
        }
        override suspend fun deleteForSubscription(subscriptionId: Long) {
            nodes.values.removeAll { it.subscriptionId == subscriptionId }
        }
        override suspend fun countForSubscription(subscriptionId: Long): Int =
            nodes.values.count { it.subscriptionId == subscriptionId }
        override suspend fun replaceForSubscription(
            subscriptionId: Long,
            nodes: List<NodeEntity>,
        ) {
            replaceCalls++
            super.replaceForSubscription(subscriptionId, nodes)
        }
    }

    private class FakeValidator(
        private val nodeDao: FakeNodeDao,
    ) : SubscriptionCandidateValidator {
        var failure: EngineError? = null
        var calls = 0
        var replaceCallsAtValidate = -1

        /** Suspends validation until completed — lets a test cancel the
         *  import mid-flight, between the row insert and the node commit. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun validate(nodes: List<ProxyNode>) {
            calls++
            // Ordering contract: validation must happen before any DB write.
            replaceCallsAtValidate = nodeDao.replaceCalls
            gate?.await()
            failure?.let { throw it }
        }
    }

    private inner class FakeTransactions : DbTransactionRunner {
        /** scheduler.cancel calls observed while a transaction block ran. */
        var cancelledInsideBlock = -1
        var failWith: Throwable? = null
        override suspend fun <T> run(block: suspend () -> T): T {
            failWith?.let { throw it }
            cancelledInsideBlock = scheduler.cancelled.size
            return block()
        }
    }

    private class FakeSettings : SubscriptionSettings {
        override val hwidConsent = MutableStateFlow(dev.typenil.vpnclient.core.subscription.HwidConsent.Allowed)
        override suspend fun setHwidConsent(consent: dev.typenil.vpnclient.core.subscription.HwidConsent) {
            hwidConsent.value = consent
        }
        val selected = MutableStateFlow<String?>(null)
        val autoRefresh = MutableStateFlow(0)
        var hwidCalls = 0
        var afterHwid: (() -> Unit)? = null
        override suspend fun getOrCreateHwid(): String {
            hwidCalls++
            afterHwid?.invoke()
            return "00000000-0000-0000-0000-000000000000"
        }
        override val selectedNodeId: Flow<String?> get() = selected
        override suspend fun setSelectedNodeId(id: String?) { selected.value = id }
        override suspend fun clearSelectedNodeIdIf(expected: String) {
            if (selected.value == expected) selected.value = null
        }
        /** Reads after this many successes throw [autoRefreshReadError] —
         *  a periodic refresh reads it at admission AND at the commit
         *  re-gate, so `1` fails exactly the second check. -1 = never. */
        var autoRefreshReadsUntilFailure = -1
        var autoRefreshReadError: () -> Throwable = {
            IllegalStateException("datastore read failed")
        }
        private var autoRefreshReads = 0
        override val autoRefreshMinutes: Flow<Int>
            get() =
                flow {
                    if (autoRefreshReadsUntilFailure >= 0 &&
                        ++autoRefreshReads > autoRefreshReadsUntilFailure
                    ) {
                        throw autoRefreshReadError()
                    }
                    emit(autoRefresh.value)
                }
        val alerted = MutableStateFlow<Set<String>>(emptySet())
        override val expiryAlerted: Flow<Set<String>> get() = alerted
        override suspend fun markExpiryAlerted(key: String) {
            alerted.value = alerted.value + key
        }
    }

    private class FakeScheduler : SubscriptionRefreshScheduler {
        data class Call(
            val id: Long,
            val providerMinutes: Int?,
            val policy: RefreshPolicy,
            val userOverride: Int,
            val enabled: Boolean,
        )
        val scheduled = mutableListOf<Call>()
        val cancelled = mutableListOf<Long>()
        /** When set, schedule() throws — simulates a WorkManager failure. */
        var failure: Exception? = null
        /** schedule() throws only for these ids — a reconcile that fails on
         *  one row while the rest succeed. */
        var failForIds: Set<Long> = emptySet()
        /** Simulated live WorkManager jobs the orphan prune inspects — each id
         *  is checked through the callback and pruned when no CURRENT row
         *  exists. */
        val pendingWorkIds = mutableSetOf<Long>()
        /** Ids the prune actually asked the callback about. */
        val existenceChecks = mutableListOf<Long>()
        /** Runs inside reconcile() before the per-id checks — lets a test
         *  mutate rows between the caller's list read and the prune. */
        var onReconcile: (() -> Unit)? = null
        /** When set, reconcile() throws — simulates a failed queue query. */
        var reconcileFailure: Exception? = null
        override suspend fun schedule(
            subscriptionId: Long,
            providerMinutes: Int?,
            policy: RefreshPolicy,
            userOverrideMinutes: Int,
            enabled: Boolean,
        ) {
            if (subscriptionId in failForIds) throw IllegalStateException("wm down")
            failure?.let { throw it }
            scheduled.add(Call(subscriptionId, providerMinutes, policy, userOverrideMinutes, enabled))
        }
        override fun cancel(subscriptionId: Long) { cancelled.add(subscriptionId) }
        override suspend fun reconcile(exists: suspend (Long) -> Boolean) {
            reconcileFailure?.let { throw it }
            onReconcile?.invoke()
            pendingWorkIds.forEach { id ->
                existenceChecks += id
                if (!exists(id)) cancelled += id
            }
        }
    }

    private class FakeExpiryNotifier : SubscriptionExpiryNotifier {
        data class Call(val id: Long, val name: String, val expire: Long)
        val calls = mutableListOf<Call>()
        override fun notifyExpiring(
            subscriptionId: Long,
            subscriptionName: String,
            expireEpochSeconds: Long,
        ): Boolean {
            calls.add(Call(subscriptionId, subscriptionName, expireEpochSeconds))
            return posted
        }

        /** False simulates a denied POST_NOTIFICATIONS — nothing was shown. */
        var posted: Boolean = true
    }

    private lateinit var subscriptionDao: FakeSubscriptionDao
    private lateinit var nodeDao: FakeNodeDao
    private lateinit var nodePreferenceDao: FakeNodePreferenceDao
    private lateinit var validator: FakeValidator
    private lateinit var scheduler: FakeScheduler
    private lateinit var settings: FakeSettings
    private lateinit var expiryNotifier: FakeExpiryNotifier
    private lateinit var transactions: FakeTransactions
    private lateinit var repository: SubscriptionRepository

    private val uriParser = UriListParser()

    private fun uri(host: String, name: String) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=abc123pubkey&sid=01ab" +
            "&type=tcp&flow=xtls-rprx-vision#$name"

    private fun nodeEntity(uri: String, subId: Long): NodeEntity {
        val n = uriParser.parse(uri, subId).nodes.single()
        return NodeEntity(
            id = n.id,
            subscriptionId = subId,
            name = n.name,
            protocol = n.protocol.name,
            server = n.server,
            port = n.port,
            outboundJson = n.outboundJson,
            rawUri = n.rawUri,
            position = 0,
        )
    }


    private fun seedSubscription(id: Long, url: String, fallbackUrl: String? = null) {
        subscriptionDao.subs[id] = SubscriptionEntity(
            id = id,
            name = "sub",
            url = url,
            createdAtEpochMs = 1,
            lastUpdatedAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastError = null,
            enabled = true,
            userInfoJson = null,
            supportUrl = null,
            updateIntervalMinutes = null,
            announce = null,
            fallbackUrl = fallbackUrl,
            allowInsecureHttp = true,
        )
    }

    private fun seedSubscription(id: Long = 1L) {
        seedSubscription(id, server.url("/sub").toString())
    }

    @Before
    fun setUp() {
        subscriptionDao = FakeSubscriptionDao()
        nodeDao = FakeNodeDao()
        nodePreferenceDao = FakeNodePreferenceDao()
        validator = FakeValidator(nodeDao)
        settings = FakeSettings()
        scheduler = FakeScheduler()
        transactions = FakeTransactions()
        expiryNotifier = FakeExpiryNotifier()
        repository = SubscriptionRepository(
            subscriptionDao = subscriptionDao,
            nodeDao = nodeDao,
            nodePreferenceDao = nodePreferenceDao,
            fetcher = SubscriptionFetcher(OkHttpClient()),
            classifier = SubscriptionClassifier(),
            dispatcher = SubscriptionParserDispatcher(
                uriParser, SingBoxJsonParser(), ClashYamlParser(),
            ),
            validator = validator,
            transactions = transactions,
            scheduler = scheduler,
            settings = settings,
            expiryNotifier = expiryNotifier,
            uriListParser = uriParser,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assertDeviceHeaders(request: okhttp3.mockwebserver.RecordedRequest, allowed: Boolean) {
        listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model", "x-app-version").forEach {
            assertEquals(it, allowed, request.getHeader(it) != null)
        }
        assertTrue(request.getHeader("User-Agent") != null)
    }

    @Test
    fun `all remote entries gate device headers for each consent state`() = runTest {
        HwidConsent.entries.forEach { consent ->
            settings.hwidConsent.value = consent
            listOf("add", "refresh", "periodic", "launch", "edit").forEach { entry ->
                seedSubscription()
                subscriptionDao.subs[1] = subscriptionDao.subs.getValue(1).copy(updateAlways = true)
                settings.autoRefresh.value = 15
                val callsBefore = settings.hwidCalls
                server.enqueue(MockResponse().setBody(uri("192.0.2.1", "Synthetic")))
                val result = when (entry) {
                    "add" -> repository.add(server.url("/add").toString(), "Synthetic", true)
                    "periodic" -> repository.refreshPeriodic(1)
                    "launch" -> repository.refreshOnLaunch(1)
                    "edit" -> repository.editUrl(1, server.url("/edit").toString())
                    else -> repository.refresh(1)
                }
                assertTrue("$consent $entry", result.isSuccess)
                val allowed = consent == HwidConsent.Allowed
                assertDeviceHeaders(server.takeRequest(), allowed)
                assertEquals(callsBefore + if (allowed) 1 else 0, settings.hwidCalls)
            }
        }
    }

    @Test
    fun `cross-origin transport fallback independently gates all device headers`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            HwidConsent.entries.forEach { consent ->
                settings.hwidConsent.value = consent
                seedSubscription(1, server.url("/sub").toString(), fallback.url("/sub").toString())
                val callsBefore = settings.hwidCalls
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
                fallback.enqueue(MockResponse().setBody(uri("192.0.2.1", "Synthetic")))
                assertTrue(repository.refresh(1).isSuccess)
                assertDeviceHeaders(fallback.takeRequest(), consent == HwidConsent.Allowed)
                assertEquals(callsBefore + if (consent == HwidConsent.Allowed) 2 else 0, settings.hwidCalls)
            }
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `200 refusal cannot replace last known good with valid remark nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setBody(uri("remark.example.com", "Remark"))
            .addHeader("x-hwid-max-devices-reached", "true"))
        val error = repository.refresh(1).exceptionOrNull()
        assertTrue(error is SubscriptionError.DeviceIdentificationRejected)
        assertEquals(listOf(old), nodeDao.forSubscription(1))
        assertEquals(0, validator.calls)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(0, subscriptionDao.successCalls)
        assertEquals(1, subscriptionDao.attemptCalls)
    }

    @Test
    fun `refresh validates before committing nodes`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.refresh(1)

        assertTrue(result.isSuccess)
        assertEquals(1, validator.calls)
        assertEquals(0, validator.replaceCallsAtValidate)
        assertEquals(1, nodeDao.replaceCalls)
        assertEquals(1, nodeDao.forSubscription(1).size)
        assertEquals(1, subscriptionDao.successCalls)
    }

    @Test
    fun `validation failure preserves last-known-good nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        validator.failure = EngineError.InvalidConfig("rejected")
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.ConfigRejected)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals("rejected by engine", subscriptionDao.lastAttemptError)
    }

    @Test
    fun `unparseable body preserves last-known-good nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setBody("definitely not a subscription body"))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertEquals(0, validator.calls)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
    }

    @Test
    fun `selection is cleared when the selected node vanishes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        settings.selected.value = old.id
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        assertTrue(repository.refresh(1).isSuccess)
        assertNull(settings.selected.value)
    }

    @Test
    fun `changed TLS identity prunes favorite and name and clears selection`() = runTest {
        seedSubscription()
        val original = "vless://00000000-0000-0000-0000-000000000001@server.example:443?security=tls&type=ws&host=host.example"
        val old = nodeEntity("$original&sni=server.example", 1)
        val replacement = nodeEntity(original, 1)
        assertFalse(old.id == replacement.id)
        nodeDao.nodes[old.id] = old
        nodePreferenceDao.setFavorite(old.id, true)
        nodePreferenceDao.setCustomName(old.id, "Personal name")
        settings.selected.value = old.id
        // Fake DAO cannot execute the SQL subquery; supply the post-commit table.
        nodePreferenceDao.liveNodeIds = setOf(replacement.id)
        server.enqueue(MockResponse().setBody(original))

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(listOf(replacement.id), nodeDao.forSubscription(1).map { it.id })
        assertNull(nodePreferenceDao.get(old.id))
        assertNull(nodePreferenceDao.get(replacement.id))
        assertNull(settings.selected.value)
    }

    @Test
    fun `selection is kept when the selected node survives`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("a.example.com", "A"), 1)
        nodeDao.nodes[old.id] = old
        settings.selected.value = old.id
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(old.id, settings.selected.value)
    }

    @Test
    fun `auto selection is not treated as a vanished node`() = runTest {
        seedSubscription()
        settings.selected.value = NodeSelection.AUTO_ID
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(NodeSelection.AUTO_ID, settings.selected.value)
    }

    @Test
    fun `add rejects cleartext url without opt-in`() = runTest {
        val result = repository.add(server.url("/sub").toString(), null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.InsecureTransport)
        assertTrue(subscriptionDao.subs.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `add accepts cleartext url with opt-in`() = runTest {
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.add(
            server.url("/sub").toString(), null, allowInsecureHttp = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, subscriptionDao.subs.size)
    }

    @Test
    fun `http failure preserves nodes and records attempt`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals("sub:http:500", subscriptionDao.lastAttemptError)
    }

    @Test
    fun `successful refresh registers scheduled auto-update`() = runTest {
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("profile-update-interval", "2"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, scheduler.scheduled.size)
        val call = scheduler.scheduled.single()
        assertEquals(1L, call.id)
        assertEquals(120, call.providerMinutes)
        assertEquals(0, call.userOverride)
        assertTrue(call.enabled)
    }

    @Test
    fun `failed refresh does not touch the scheduler`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(repository.refresh(1).isFailure)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `remove deletes rows atomically then cancels the scheduled job`() = runTest {
        seedSubscription()
        nodeDao.nodes["n1"] = nodeEntity(uri("a.example.com", "A"), 1)

        repository.remove(1)

        assertTrue(subscriptionDao.subs.isEmpty())
        assertTrue(nodeDao.nodes.isEmpty())
        // Cancel ran after the delete transaction committed, not before it.
        assertEquals(0, transactions.cancelledInsideBlock)
        assertEquals(listOf(1L), scheduler.cancelled)
    }

    // ---- failed add: nothing is left behind, the cause is typed ----

    private val addUrl get() = server.url("/sub").toString()

    /** Runs a first add against [response]; asserts no row, nodes or work remain. */
    private suspend fun failedAdd(
        response: MockResponse,
        retryResponse: MockResponse? = null,
    ): SubscriptionError {
        settings.autoRefresh.value = 60 // a leftover row would have been scheduled
        server.enqueue(response)
        retryResponse?.let(server::enqueue)
        val result = repository.add(addUrl, null, allowInsecureHttp = true)
        assertTrue(result.isFailure)
        assertTrue("row left behind", subscriptionDao.subs.isEmpty())
        assertTrue("nodes left behind", nodeDao.nodes.isEmpty())
        assertTrue("work scheduled", scheduler.scheduled.isEmpty())
        assertTrue("scheduler touched", scheduler.cancelled.isEmpty())
        assertTrue(repository.profiles.first().isEmpty())
        return result.exceptionOrNull() as SubscriptionError
    }

    @Test
    fun `failed add leaves nothing for every http status`() = runTest {
        listOf(403, 404, 429, 500, 503).forEach { code ->
            val e = failedAdd(
                MockResponse().setResponseCode(code),
                retryResponse = if (code in 500..599) MockResponse().setResponseCode(code) else null,
            )
            assertEquals(code, (e as SubscriptionError.Http).code)
        }
    }

    @Test
    fun `failed add leaves nothing for each hwid refusal and keeps them distinct from http`() = runTest {
        var e = failedAdd(MockResponse().setResponseCode(403).setHeader("x-hwid-max-devices-reached", "true"))
        assertEquals(SubscriptionError.HwidRefusal.MaxDevices, (e as SubscriptionError.DeviceIdentificationRejected).reason)
        assertFalse(e.sendingDisabled)
        e = failedAdd(MockResponse().setResponseCode(404).setHeader("x-hwid-not-supported", "true"))
        assertEquals(SubscriptionError.HwidRefusal.MissingOrInvalid, (e as SubscriptionError.DeviceIdentificationRejected).reason)
        e = failedAdd(MockResponse().setResponseCode(403).setHeader("x-hwid-limit", "true"))
        assertEquals(SubscriptionError.HwidRefusal.Ambiguous, (e as SubscriptionError.DeviceIdentificationRejected).reason)
        settings.hwidConsent.value = HwidConsent.Denied
        e = failedAdd(MockResponse().setResponseCode(403).setHeader("x-hwid-not-supported", "true"))
        assertTrue((e as SubscriptionError.DeviceIdentificationRejected).sendingDisabled)
    }

    @Test
    fun `failed add leaves nothing on network loss and bad bodies`() = runTest {
        assertTrue(
            failedAdd(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
                retryResponse = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            ) is SubscriptionError.Network,
        )
        assertTrue(failedAdd(MockResponse().setBody("not a subscription at all")) !is SubscriptionError.Http)
        assertTrue(failedAdd(MockResponse().setBody("""{"outbounds":[]}""")) is SubscriptionError.EmptyResult)
    }

    @Test
    fun `an empty response is inactive only when userinfo says so`() = runTest {
        val body = """{"outbounds":[]}"""
        val expired = failedAdd(MockResponse().setBody(body)
            .setHeader("subscription-userinfo", "upload=0; download=0; total=100; expire=1"))
        assertEquals(SubscriptionError.InactiveReason.Expired, (expired as SubscriptionError.Inactive).reason)
        val used = failedAdd(MockResponse().setBody(body)
            .setHeader("subscription-userinfo", "upload=60; download=50; total=100"))
        assertEquals(SubscriptionError.InactiveReason.TrafficUsedUp, (used as SubscriptionError.Inactive).reason)
        val live = failedAdd(MockResponse().setBody(body)
            .setHeader("subscription-userinfo", "upload=0; download=0; total=100; expire=99999999999"))
        assertTrue(live is SubscriptionError.EmptyResult)
    }

    @Test
    fun `failed add rejects an unusable address before any row exists`() = runTest {
        val result = repository.add("not a url", null)
        assertTrue(result.exceptionOrNull() is SubscriptionError.BadUrl)
        assertTrue(subscriptionDao.subs.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider message on an error response is bounded and plain`() = runTest {
        val noisy = "<b>Limit</b> reached\u202e\n\u0007 \tcall  <a href=\"https://example.com\">us</a> " + "x".repeat(600)
        val b64 = "base64:" + java.util.Base64.getEncoder().encodeToString(noisy.toByteArray())
        val http = failedAdd(MockResponse().setResponseCode(403).setHeader("announce", b64)) as SubscriptionError.Http
        val text = http.announce!!
        assertEquals(PROVIDER_MESSAGE_MAX_CHARS, text.length)
        assertTrue(text.startsWith("Limit reached call us x"))
        assertTrue(text.endsWith("…"))
        assertTrue(text.none { it.isISOControl() || it == '\u202e' || it == '<' })
        val hwid = failedAdd(MockResponse().setResponseCode(403)
            .setHeader("x-hwid-max-devices-reached", "true").setHeader("announce", "Remove an old device"))
        assertEquals("Remove an old device", (hwid as SubscriptionError.DeviceIdentificationRejected).announce)
        assertNull((failedAdd(MockResponse().setResponseCode(404)) as SubscriptionError.Http).announce)
        // toString feeds logs and crash reports: it must not carry the provider text.
        assertFalse(http.toString().contains("Limit"))
    }

    @Test
    fun `a failed refresh of an existing subscription keeps its nodes and row`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setResponseCode(403)
            .setHeader("x-hwid-max-devices-reached", "true").setHeader("announce", "Provider says no"))

        assertTrue(repository.refresh(1).isFailure)

        assertEquals(1, subscriptionDao.subs.size)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals("sub:hwid:max", subscriptionDao.lastAttemptError)
    }

    @Test
    fun `a cancelled add leaves nothing and an in-flight add stays hidden`() = runTest {
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        val gate = CompletableDeferred<Unit>()
        validator.gate = gate
        val job = launch { repository.add(addUrl, null, allowInsecureHttp = true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (validator.calls == 0 && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(1, subscriptionDao.subs.size)
        assertTrue("pending row visible", repository.profiles.first().isEmpty())

        job.cancelAndJoin()

        assertTrue(subscriptionDao.subs.isEmpty())
        assertTrue(nodeDao.nodes.isEmpty())
    }

    @Test
    fun `startup prune removes a row that never succeeded and has no nodes`() = runTest {
        seedSubscription(7L, "https://a.example.com/sub")

        assertEquals(1, repository.pruneAbandonedAdds())

        assertTrue(subscriptionDao.subs.isEmpty())
        assertEquals(listOf(7L), scheduler.cancelled)
    }

    @Test
    fun `startup prune keeps a never-updated row that owns nodes`() = runTest {
        seedSubscription(7L, "https://a.example.com/sub")
        nodeDao.nodes["n"] = nodeEntity(uri("a.example.com", "A"), 7L).copy(id = "n")

        assertEquals(0, repository.pruneAbandonedAdds())

        assertEquals(setOf(7L), subscriptionDao.subs.keys)
    }

    @Test
    fun `startup prune keeps an updated row whose nodes were all removed`() = runTest {
        seedSubscription(7L, "https://a.example.com/sub")
        subscriptionDao.subs[7L] = subscriptionDao.subs[7L]!!.copy(lastUpdatedAtEpochMs = 1_000L)

        assertEquals(0, repository.pruneAbandonedAdds())

        assertEquals(setOf(7L), subscriptionDao.subs.keys)
    }

    @Test
    fun `startup prune never touches the manual sentinel`() = runTest {
        seedSubscription(7L, SubscriptionRepository.MANUAL_SUBSCRIPTION_URL)

        assertEquals(0, repository.pruneAbandonedAdds())

        assertEquals(setOf(7L), subscriptionDao.subs.keys)
    }

    @Test
    fun `launch refresh candidates prune first so an orphan is never listed`() = runTest {
        // Current code paths never write updateAlways without lastUpdatedAt, so the orphan
        // is seeded directly: the contract must hold even if a row ever violates that.
        seedSubscription(7L, "https://a.example.com/sub")
        subscriptionDao.subs[7L] = subscriptionDao.subs.getValue(7L).copy(updateAlways = true)
        seedSubscription(8L, "https://b.example.com/sub")
        subscriptionDao.subs[8L] = subscriptionDao.subs.getValue(8L)
            .copy(updateAlways = true, lastUpdatedAtEpochMs = 5L)
        nodeDao.nodes["n"] = nodeEntity(uri("b.example.com", "B"), 8L).copy(id = "n")

        assertEquals(listOf(8L), repository.launchRefreshCandidates())

        assertEquals(setOf(8L), subscriptionDao.subs.keys)
    }

    @Test
    fun `a failing prune still lists the update-always candidates`() = runTest {
        seedSubscription(7L, "https://a.example.com/sub") // orphan: the prune will try (and fail) to delete it
        seedSubscription(8L, "https://b.example.com/sub")
        subscriptionDao.subs[8L] = subscriptionDao.subs.getValue(8L)
            .copy(updateAlways = true, lastUpdatedAtEpochMs = 5L)
        transactions.failWith = IllegalStateException("synthetic db failure")

        assertEquals(listOf(8L), repository.launchRefreshCandidates())
    }

    @Test
    fun `a cancelled prune cancels launch candidates instead of being swallowed`() = runTest {
        seedSubscription(7L, "https://a.example.com/sub")
        transactions.failWith = kotlinx.coroutines.CancellationException("synthetic")

        try {
            repository.launchRefreshCandidates()
            org.junit.Assert.fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test
    fun `startup prune and reconcile leave a row whose add is still in flight`() = runTest {
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        val gate = CompletableDeferred<Unit>()
        validator.gate = gate
        settings.autoRefresh.value = 60
        val job = launch { repository.add(addUrl, null, allowInsecureHttp = true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (validator.calls == 0 && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(1, subscriptionDao.subs.size)

        assertEquals(0, repository.pruneAbandonedAdds())
        repository.reconcileRefreshSchedules(inheritingOnly = false)

        assertEquals(1, subscriptionDao.subs.size)
        assertTrue("pending row got a job", scheduler.scheduled.isEmpty())
        job.cancelAndJoin()
        assertTrue(subscriptionDao.subs.isEmpty())
    }

    @Test
    fun `a successful add keeps the row nodes schedule and becomes visible`() = runTest {
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")).setHeader("profile-update-interval", "2"))

        assertTrue(repository.add(addUrl, null, allowInsecureHttp = true).isSuccess)

        assertEquals("rows", 1, subscriptionDao.subs.size)
        assertEquals("nodes", 1, nodeDao.nodes.size)
        assertEquals(120, scheduler.scheduled.single().providerMinutes)
        assertEquals("visible", 1, repository.profiles.first().size)
    }

    @Test
    fun `network failure retries once against fallback url`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fallback.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            val result = repository.refresh(1)

            assertTrue(result.isSuccess)
            assertEquals(1, nodeDao.forSubscription(1).size)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `fallback re-reads consent revoked after primary request preparation`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(1, server.url("/sub").toString(), fallback.url("/sub").toString())
            settings.afterHwid = { settings.hwidConsent.value = HwidConsent.Denied }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fallback.enqueue(MockResponse().setBody(uri("192.0.2.1", "Synthetic")))
            assertTrue(repository.refresh(1).isSuccess)
            assertDeviceHeaders(fallback.takeRequest(), false)
            assertEquals(1, settings.hwidCalls)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `http error does not retry fallback`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            server.enqueue(MockResponse().setResponseCode(500))

            assertTrue(repository.refresh(1).isFailure)
            assertEquals(0, fallback.requestCount)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `moved-permanently-to migrates the stored url`() = runTest {
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("moved-permanently-to", "https://new.example.com/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals("https://new.example.com/sub", subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `http migration target honored only under cleartext opt-in`() = runTest {
        // Opted-in subscription: an http migration target is permitted.
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("moved-permanently-to", "http://public.example.com/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals("http://public.example.com/sub", subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `migration transport policy`() {
        // The fetch can't be exercised over HTTPS without okhttp-tls, so the
        // transport gate is a pure function: https always allowed, http only
        // under the per-subscription opt-in.
        assertTrue(isMigrationAllowed("https://new.example.com/sub", allowInsecureHttp = false))
        assertFalse(isMigrationAllowed("http://new.example.com/sub", allowInsecureHttp = false))
        assertTrue(isMigrationAllowed("http://new.example.com/sub", allowInsecureHttp = true))
        assertFalse(isMigrationAllowed("not-a-url", allowInsecureHttp = true))
    }

    @Test
    fun `successful fallback keeps the stored fallback url`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            // Fallback answers without its own fallback-url header — the
            // known-working endpoint must survive markSuccess.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fallback.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            assertTrue(repository.refresh(1).isSuccess)
            assertEquals(fallback.url("/sub").toString(), subscriptionDao.subs[1]?.fallbackUrl)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `private new-url is ignored`() = runTest {
        seedSubscription()
        val original = subscriptionDao.subs[1]!!.url
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("new-url", "http://127.0.0.1:9/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(original, subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `expiry inside the window posts one alert`() = runTest {
        seedSubscription()
        val expire = (System.currentTimeMillis() / 1000) + 3600
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
        assertEquals("1:$expire" in settings.alerted.value, true)

        // Second refresh with the same expiry — already alerted, stays quiet.
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )
        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
    }

    @Test
    fun `denied notification is not recorded as alerted`() = runTest {
        seedSubscription()
        expiryNotifier.posted = false
        val expire = (System.currentTimeMillis() / 1000) + 3600
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
        // Nothing was posted — the key must stay unmarked so a later
        // refresh can alert once permission is granted.
        assertFalse("1:$expire" in settings.alerted.value)
    }

    @Test
    fun `persisted expiry scan alerts without a refresh`() = runTest {
        // The startup path: expiry already persisted in userInfoJson, no
        // fetch involved — checkPersistedExpiryAlerts must still fire.
        seedSubscription()
        val expire = (System.currentTimeMillis() / 1000) + 3600
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(
            userInfoJson = """{"uploadBytes":0,"downloadBytes":0,"totalBytes":0,"expireEpochSeconds":$expire}""",
        )

        repository.checkPersistedExpiryAlerts()

        assertEquals(1, expiryNotifier.calls.size)
        assertTrue(expiryAlertKey(1, expire) in settings.alerted.value)

        // Second scan — already alerted, stays quiet.
        repository.checkPersistedExpiryAlerts()
        assertEquals(1, expiryNotifier.calls.size)
    }

    // ---- share-link import (manual sentinel row) ----

    private suspend fun manualSubId(): Long =
        subscriptionDao.findIdByUrl(SubscriptionRepository.MANUAL_SUBSCRIPTION_URL)!!

    @Test
    fun `importShareLink creates the manual row and stores the node`() = runTest {
        val result = repository.importShareLink(uri("a.example.com", "A"))

        assertTrue(result.isSuccess)
        assertEquals(1, subscriptionDao.subs.size)
        val sub = subscriptionDao.subs.values.single()
        assertEquals(SubscriptionRepository.MANUAL_SUBSCRIPTION_URL, sub.url)
        assertEquals("Manual servers", sub.name)
        assertTrue(sub.enabled)
        assertEquals(1, nodeDao.forSubscription(sub.id).size)
        // Never a fetch, never a scheduled job.
        assertEquals(0, server.requestCount)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `importShareLink appends to the existing manual row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        repository.importShareLink(uri("b.example.com", "B"))

        assertEquals(1, subscriptionDao.subs.size)
        val nodes = nodeDao.forSubscription(manualSubId())
        assertEquals(2, nodes.size)
        // Appended, not replaced — positions bump monotonically.
        assertEquals(listOf(0, 1), nodes.sortedBy { it.position }.map { it.position })
    }

    @Test
    fun `importShareLink is idempotent on node id`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        repository.importShareLink(uri("a.example.com", "A"))

        assertEquals(1, nodeDao.forSubscription(manualSubId()).size)
    }

    @Test
    fun `importShareLink typed-fails on garbage and writes nothing`() = runTest {
        val result = repository.importShareLink("definitely not a share link")

        assertTrue(result.isFailure)
        // The sentinel row IS created before the parse (node identity is
        // salted with it) — but no node rows and no fetch happened.
        assertTrue(nodeDao.nodes.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh fails fast on the manual sentinel row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        val result = repository.refresh(id)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.NotFound)
        assertEquals(0, server.requestCount)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `remove is refused on the manual sentinel row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        repository.remove(id)

        assertEquals(1, subscriptionDao.subs.size)
        assertEquals(1, nodeDao.forSubscription(id).size)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `importShareLink keeps every node of a multi-line paste`() = runTest {
        val pasted =
            listOf(
                uri("a.example.com", "A"),
                uri("b.example.com", "B"),
                uri("c.example.com", "C"),
            ).joinToString("\n")

        val result = repository.importShareLink(pasted)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow().nodeCount)
        val stored = nodeDao.forSubscription(manualSubId())
        assertEquals(3, stored.size)
        // Contiguous positions in paste order.
        assertEquals(listOf(0, 1, 2), stored.sortedBy { it.position }.map { it.position })
    }

    @Test
    fun `importShareLink rejects an engine-invalid link and commits nothing`() = runTest {
        validator.failure = EngineError.InvalidConfig("rejected")

        val result = repository.importShareLink(uri("a.example.com", "A"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.ConfigRejected)
        assertEquals(1, validator.calls)
        // Nothing committed, and the sentinel row created for this attempt is
        // gone again — an outbound sing-box rejects must never reach the DB.
        assertTrue(nodeDao.nodes.isEmpty())
        assertTrue(subscriptionDao.subs.isEmpty())
    }

    @Test
    fun `a failed first import leaves no empty manual row`() = runTest {
        val result = repository.importShareLink("definitely not a share link")

        assertTrue(result.isFailure)
        assertTrue(subscriptionDao.subs.isEmpty())
    }

    @Test
    fun `a failed import keeps the existing manual row and its nodes`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        validator.failure = EngineError.InvalidConfig("rejected")

        val result = repository.importShareLink(uri("b.example.com", "B"))

        assertTrue(result.isFailure)
        assertEquals(1, subscriptionDao.subs.size)
        assertEquals(1, nodeDao.forSubscription(manualSubId()).size)
    }

    @Test
    fun `a cancelled first import leaves no empty manual row`() = runTest {
        val gate = CompletableDeferred<Unit>()
        validator.gate = gate

        val job = launch { repository.importShareLink(uri("a.example.com", "A")) }
        // The parse runs on Dispatchers.Default and the row insert precedes
        // validation — pump until the import is parked inside validate().
        val deadline = System.currentTimeMillis() + 5_000
        while (validator.calls == 0 && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(1, validator.calls)
        assertEquals(1, subscriptionDao.subs.size)

        job.cancelAndJoin()

        // Cleanup runs on the cancellation path too (NonCancellable in
        // `finally`) — an aborted first import must not leave the row behind.
        assertTrue(subscriptionDao.subs.isEmpty())
        assertTrue(nodeDao.nodes.isEmpty())
    }

    @Test
    fun `a cancelled import keeps the existing manual row and its nodes`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        // Counted before this run: the earlier import already entered
        // validation, so waiting on `calls == 0` would prove nothing about
        // where THIS one was cancelled.
        val callsBefore = validator.calls
        val gate = CompletableDeferred<Unit>()
        validator.gate = gate

        val job = launch { repository.importShareLink(uri("b.example.com", "B")) }
        val deadline = System.currentTimeMillis() + 5_000
        while (validator.calls == callsBefore && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        // Cancelled from inside validation — past the row lookup and the parse.
        assertEquals(callsBefore + 1, validator.calls)

        job.cancelAndJoin()

        assertEquals(1, subscriptionDao.subs.size)
        assertEquals(1, nodeDao.forSubscription(manualSubId()).size)
    }

    // ---- manual node removal ----

    @Test
    fun `removeManualNode deletes the node and clears its selection`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        repository.importShareLink(uri("b.example.com", "B"))
        val id = manualSubId()
        val nodes = nodeDao.forSubscription(id).sortedBy { it.position }
        settings.selected.value = nodes[0].id

        repository.removeManualNode(nodes[0].id)

        assertEquals(listOf(nodes[1].id), nodeDao.forSubscription(id).map { it.id })
        assertNull(settings.selected.value)
        assertEquals(1, subscriptionDao.subs.size)
    }

    @Test
    fun `removeManualNode drops the row with its last node`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val only = nodeDao.forSubscription(manualSubId()).single()

        repository.removeManualNode(only.id)

        assertTrue(nodeDao.nodes.isEmpty())
        assertTrue(subscriptionDao.subs.isEmpty())
    }

    @Test
    fun `removeManualNode refuses a node owned by a remote subscription`() = runTest {
        seedSubscription()
        val node = nodeEntity(uri("a.example.com", "A"), 1)
        nodeDao.nodes[node.id] = node

        repository.removeManualNode(node.id)

        assertEquals(1, nodeDao.forSubscription(1).size)
    }

    // ---- enable/disable ----

    @Test
    fun `setEnabled false flips the flag and cancels the job`() = runTest {
        seedSubscription()
        nodeDao.nodes["n1"] = nodeEntity(uri("a.example.com", "A"), 1)
        settings.autoRefresh.value = 60

        repository.setEnabled(1, false)

        assertFalse(subscriptionDao.subs[1]!!.enabled)
        val call = scheduler.scheduled.single()
        assertEquals(1L, call.id)
        assertFalse(call.enabled)
    }

    @Test
    fun `setEnabled true re-registers the job`() = runTest {
        seedSubscription()
        repository.setEnabled(1, false)

        repository.setEnabled(1, true)

        assertTrue(subscriptionDao.subs[1]!!.enabled)
        assertTrue(scheduler.scheduled.last().enabled)
    }

    @Test
    fun `disabling clears the selection when it owns the selected node`() = runTest {
        seedSubscription()
        val node = nodeEntity(uri("a.example.com", "A"), 1)
        nodeDao.nodes[node.id] = node
        settings.selected.value = node.id

        repository.setEnabled(1, false)

        assertNull(settings.selected.value)
    }

    @Test
    fun `disabling keeps a selection owned by another subscription`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        val other = nodeEntity(uri("other.example.com", "B"), 2)
        nodeDao.nodes[other.id] = other
        settings.selected.value = other.id

        repository.setEnabled(1, false)

        assertEquals(other.id, settings.selected.value)
    }

    // ---- rename ----

    @Test
    fun `rename stores the trimmed name`() = runTest {
        seedSubscription()
        repository.rename(1, "  My VPN  ")
        assertEquals("My VPN", subscriptionDao.subs[1]!!.name)
    }

    @Test
    fun `rename ignores blank input`() = runTest {
        seedSubscription()
        repository.rename(1, "   ")
        assertEquals("sub", subscriptionDao.subs[1]!!.name)
    }

    // ---- editUrl ----

    @Test
    fun `editUrl repoints the row and swaps nodes atomically`() = runTest {
        seedSubscription()
        val newServer = MockWebServer()
        try {
            newServer.start()
            val old = nodeEntity(uri("old.example.com", "Old"), 1)
            nodeDao.nodes[old.id] = old
            newServer.enqueue(MockResponse().setBody(uri("new.example.com", "New")))

            val result = repository.editUrl(1, newServer.url("/sub").toString())

            assertTrue(result.isSuccess)
            assertEquals(newServer.url("/sub").toString(), subscriptionDao.subs[1]!!.url)
            assertEquals(1, subscriptionDao.urlSuccessCalls)
            assertEquals(
                listOf("new.example.com"),
                nodeDao.forSubscription(1).map { it.server },
            )
            // The markSuccess path was not used — the URL must land in the
            // same write as the node swap.
            assertEquals(0, subscriptionDao.successCalls)
        } finally {
            newServer.shutdown()
        }
    }

    @Test
    fun `editUrl failure keeps the stored URL and nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        val originalUrl = subscriptionDao.subs[1]!!.url
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.editUrl(1, server.url("/broken").toString())

        assertTrue(result.isFailure)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals(0, subscriptionDao.urlSuccessCalls)
    }

    @Test
    fun `editUrl rejects a cleartext url without the opt-in`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(allowInsecureHttp = false)
        val originalUrl = subscriptionDao.subs[1]!!.url

        val result = repository.editUrl(1, "http://cleartext.example.com/sub")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.InsecureTransport)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(0, subscriptionDao.urlSuccessCalls)
    }

    @Test
    fun `editUrl with an unchanged url just refreshes`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.editUrl(1, subscriptionDao.subs[1]!!.url)

        assertTrue(result.isSuccess)
        // Same-URL edit delegates to refresh — markSuccess, not the URL write.
        assertEquals(0, subscriptionDao.urlSuccessCalls)
        assertEquals(1, subscriptionDao.successCalls)
    }

    @Test
    fun `editUrl on a validation failure preserves nodes and url`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        val originalUrl = subscriptionDao.subs[1]!!.url
        validator.failure = EngineError.InvalidConfig("rejected")
        server.enqueue(MockResponse().setBody(uri("bad.example.com", "B")))

        val result = repository.editUrl(1, server.url("/other").toString())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.ConfigRejected)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
    }

    // ---- lock granularity: the slow phase must not hold a global lock ----

    /** Parks the current validate() call until completed — phase-1 work that
     *  the old global mutex held for a whole refresh. */
    private suspend fun TestScope.parkInValidate(expectedCalls: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (validator.calls < expectedCalls && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(expectedCalls, validator.calls)
    }

    @Test
    fun `pref toggle completes while another subscription refresh is in flight`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        val node2 = nodeEntity(uri("other.example.com", "B"), 2)
        nodeDao.nodes[node2.id] = node2
        nodePreferenceDao.liveNodeIds = setOf(node2.id)

        validator.gate = CompletableDeferred()
        var refreshOutcome: Result<RefreshOutcome>? = null
        val refreshJob = launch { refreshOutcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // A pref toggle on the OTHER subscription's node must complete while
        // sub 1's refresh is parked — the old global mutex blocked it here.
        val toggle = launch { repository.setNodeFavorite(node2.id, true) }
        val toggleDeadline = System.currentTimeMillis() + 2_000
        while (toggle.isActive && System.currentTimeMillis() < toggleDeadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertFalse("pref toggle blocked by another subscription's refresh", toggle.isActive)
        assertTrue(nodePreferenceDao.prefs[node2.id]?.isFavorite == true)

        validator.gate!!.complete(Unit)
        refreshJob.join()
        assertTrue(refreshOutcome!!.isSuccess)
        assertEquals(listOf("a.example.com"), nodeDao.forSubscription(1).map { it.server })
        // The toggle's pref row survived the refresh's orphan pruning.
        assertTrue(nodePreferenceDao.prefs[node2.id]?.isFavorite == true)
    }

    @Test
    fun `concurrent refreshes of different subscriptions both commit`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        // Identical bodies: MockWebServer dispatch order between the two
        // in-flight requests is not deterministic, and node ids are salted
        // per subscription anyway.
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        // Both refreshes must reach validation concurrently — under the old
        // global mutex the second one could not even start fetching until
        // the first one had fully committed.
        validator.gate = CompletableDeferred()
        var result1: Result<RefreshOutcome>? = null
        var result2: Result<RefreshOutcome>? = null
        val job1 = launch { result1 = repository.refresh(1) }
        val job2 = launch { result2 = repository.refresh(2) }
        parkInValidate(expectedCalls = 2)

        validator.gate!!.complete(Unit)
        job1.join()
        job2.join()
        assertTrue(result1!!.isSuccess)
        assertTrue(result2!!.isSuccess)
        assertEquals(listOf("a.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(listOf("a.example.com"), nodeDao.forSubscription(2).map { it.server })
        assertEquals(2, subscriptionDao.successCalls)
    }

    @Test
    fun `stale refresh refetches instead of committing after a url change`() = runTest {
        seedSubscription()
        // 1: the stale refresh's fetch (row still points at /sub),
        // 2: editUrl's fetch of the new location,
        // 3: the stale refresh's re-pass under the lock after it notices the
        //    url changed mid-flight.
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        server.enqueue(MockResponse().setBody(uri("c.example.com", "C")))
        server.enqueue(MockResponse().setBody(uri("c.example.com", "C")))

        validator.gate = CompletableDeferred()
        var staleOutcome: Result<RefreshOutcome>? = null
        var editOutcome: Result<RefreshOutcome>? = null
        val staleJob = launch { staleOutcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // editUrl takes the subscription lock and holds it across its own
        // fetch + commit — the parked refresh's phase 2 can only run after
        // the url has been repointed.
        val editJob = launch { editOutcome = repository.editUrl(1, server.url("/sub2").toString()) }
        parkInValidate(expectedCalls = 2)

        validator.gate!!.complete(Unit)
        editJob.join()
        staleJob.join()

        assertTrue(editOutcome!!.isSuccess)
        assertTrue(staleOutcome!!.isSuccess)
        // The stale candidate (a.example.com) never committed: the final node
        // set comes from editUrl / the re-pass, both fetched from the new url.
        assertEquals(listOf("c.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(server.url("/sub2").toString(), subscriptionDao.subs[1]!!.url)
        // Exactly one markSuccess (the re-pass) and one URL write (editUrl).
        assertEquals(1, subscriptionDao.successCalls)
        assertEquals(1, subscriptionDao.urlSuccessCalls)
        // Third validation call = the re-pass really ran.
        assertEquals(3, validator.calls)
    }

    @Test
    fun `remove during an in-flight refresh refuses the late commit`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old

        validator.gate = CompletableDeferred()
        var refreshOutcome: Result<RefreshOutcome>? = null
        val refreshJob = launch { refreshOutcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // remove() must not wait for the in-flight refresh — the old global
        // mutex kept it blocked until the commit finished.
        var removed = false
        val removeJob = launch {
            repository.remove(1)
            removed = true
        }
        val removeDeadline = System.currentTimeMillis() + 2_000
        while (!removed && System.currentTimeMillis() < removeDeadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertTrue("remove blocked by an in-flight refresh", removed)
        assertTrue(subscriptionDao.subs.isEmpty())
        assertTrue(nodeDao.nodes.isEmpty())
        assertEquals(listOf(1L), scheduler.cancelled)

        // The parked refresh resumes into a world without its row: the
        // phase-2 re-read reports NotFound and nothing is written — no node
        // rows can be resurrected against the deleted (reusable) id.
        validator.gate!!.complete(Unit)
        refreshJob.join()
        assertTrue(refreshOutcome!!.isFailure)
        assertEquals(SubscriptionError.NotFound, refreshOutcome!!.exceptionOrNull())
        assertEquals(0, subscriptionDao.successCalls)
        assertEquals(0, nodeDao.replaceCalls)
        assertTrue(nodeDao.nodes.isEmpty())
        assertTrue(subscriptionDao.subs.isEmpty())
    }

    // ---- stale-result guard: two refreshes of the SAME subscription ----

    @Test
    fun `older success cannot overwrite a newer refresh's node set`() = runTest {
        seedSubscription()
        // A (seq 1) parks in validation; B (seq 2) completes first — the
        // same URL, so the fetchedUrl check cannot tell them apart. Only the
        // attempt sequence can: A's late candidate must be dropped.
        server.enqueue(MockResponse().setBody(uri("stale.example.com", "Old")))
        server.enqueue(MockResponse().setBody(uri("fresh.example.com", "New")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var outcomeA: Result<RefreshOutcome>? = null
        var outcomeB: Result<RefreshOutcome>? = null
        val jobA = launch { outcomeA = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // Swap in a fresh deferred for B before it reaches validate().
        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        val jobB = launch { outcomeB = repository.refresh(1) }
        parkInValidate(expectedCalls = 2)

        // Finish B first — it commits the newer node set.
        gateB.complete(Unit)
        jobB.join()
        assertTrue(outcomeB!!.isSuccess)
        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })

        // A completes later; its stale candidate must NOT overwrite B's.
        gateA.complete(Unit)
        jobA.join()

        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(1, subscriptionDao.successCalls)
        assertTrue(outcomeA!!.isFailure)
    }

    @Test
    fun `older failure cannot overwrite a newer refresh's success`() = runTest {
        seedSubscription()
        // A (seq 1) parks in validation and is then made to fail; B (seq 2)
        // commits first. A's markAttempt must not write lastError over B's
        // markSuccess.
        server.enqueue(MockResponse().setBody(uri("stale.example.com", "Old")))
        server.enqueue(MockResponse().setBody(uri("fresh.example.com", "New")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var outcomeA: Result<RefreshOutcome>? = null
        var outcomeB: Result<RefreshOutcome>? = null
        val jobA = launch { outcomeA = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        val jobB = launch { outcomeB = repository.refresh(1) }
        parkInValidate(expectedCalls = 2)

        gateB.complete(Unit)
        jobB.join()
        assertTrue(outcomeB!!.isSuccess)
        assertNull(subscriptionDao.subs[1]!!.lastError)

        // A's parked validation now fails — a late error from an older
        // attempt must not overwrite the newer success's lastError=NULL.
        validator.failure = EngineError.InvalidConfig("stale")
        gateA.complete(Unit)
        jobA.join()

        assertTrue(outcomeA!!.isFailure)
        assertNull(subscriptionDao.subs[1]!!.lastError)
        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(1, subscriptionDao.successCalls)
        // The stale attempt was dropped before any markAttempt write.
        assertEquals(0, subscriptionDao.attemptCalls)
    }

    @Test
    fun `superseded refresh does not look like a deleted subscription`() = runTest {
        // Regression: a dropped stale result used to surface as NotFound —
        // the periodic Worker would read that as "row deleted" and cancel
        // the subscription's recurring job. Superseded must be distinct.
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("stale.example.com", "Old")))
        server.enqueue(MockResponse().setBody(uri("fresh.example.com", "New")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var outcomeA: Result<RefreshOutcome>? = null
        var outcomeB: Result<RefreshOutcome>? = null
        val jobA = launch { outcomeA = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        val jobB = launch { outcomeB = repository.refresh(1) }
        parkInValidate(expectedCalls = 2)
        gateB.complete(Unit)
        jobB.join()
        assertTrue(outcomeB!!.isSuccess)

        gateA.complete(Unit)
        jobA.join()

        assertTrue(outcomeA!!.isFailure)
        // The stale result must not be NotFound — that error means "row
        // deleted" and the WorkManager consumer cancels the periodic job.
        assertEquals(SubscriptionError.Superseded, outcomeA!!.exceptionOrNull())
        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })
    }

    // ---- per-subscription refresh policy ----

    @Test
    fun `setRefreshPolicy persists and reschedules from the current row`() = runTest {
        seedSubscription()
        settings.autoRefresh.value = 0

        repository.setRefreshPolicy(1, RefreshPolicy.Fixed(60))

        assertEquals("fixed", subscriptionDao.subs[1]!!.refreshPolicy)
        assertEquals(60, subscriptionDao.subs[1]!!.refreshFixedMinutes)
        val call = scheduler.scheduled.last()
        assertEquals(1L, call.id)
        assertEquals(RefreshPolicy.Fixed(60), call.policy)
        assertEquals(0, call.userOverride)
    }

    @Test
    fun `setRefreshPolicy on the manual sentinel is refused`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        repository.setRefreshPolicy(id, RefreshPolicy.Disabled)

        // The row is untouched — no policy write, no schedule.
        assertEquals("inherit", subscriptionDao.subs[id]!!.refreshPolicy)
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `successful refresh re-registers under the stored policy`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] =
            subscriptionDao.subs[1]!!.copy(refreshPolicy = "disabled")
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("profile-update-interval", "2"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        // The post-commit reschedule re-read the row — Disabled survives a
        // refresh instead of being overwritten by the provider hint.
        val call = scheduler.scheduled.last()
        assertEquals(RefreshPolicy.Disabled, call.policy)
        assertEquals(120, call.providerMinutes)
    }

    @Test
    fun `editUrl retains the stored policy when rescheduling`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] =
            subscriptionDao.subs[1]!!.copy(refreshPolicy = "fixed", refreshFixedMinutes = 90)
        val newServer = MockWebServer()
        try {
            newServer.start()
            newServer.enqueue(MockResponse().setBody(uri("new.example.com", "N")))

            assertTrue(
                repository.editUrl(1, newServer.url("/sub").toString()).isSuccess,
            )
        } finally {
            newServer.shutdown()
        }
        assertEquals(RefreshPolicy.Fixed(90), scheduler.scheduled.last().policy)
    }

    @Test
    fun `setEnabled reschedules under the stored policy`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] =
            subscriptionDao.subs[1]!!.copy(refreshPolicy = "fixed", refreshFixedMinutes = 45)

        repository.setEnabled(1, false)
        scheduler.scheduled.last().let {
            assertEquals(RefreshPolicy.Fixed(45), it.policy)
            assertFalse(it.enabled)
        }
        repository.setEnabled(1, true)
        scheduler.scheduled.last().let {
            assertEquals(RefreshPolicy.Fixed(45), it.policy)
            assertTrue(it.enabled)
        }
    }

    @Test
    fun `a policy write during an in-flight refresh wins the reschedule`() = runTest {
        // Refresh parks in phase-1 validation (no lock held); the user's
        // Disabled write lands first. Phase 2's re-read must schedule under
        // Disabled — the stale pre-write row can't resurrect a job.
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        validator.gate = CompletableDeferred()
        var outcome: Result<RefreshOutcome>? = null
        val job = launch { outcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        repository.setRefreshPolicy(1, RefreshPolicy.Disabled)

        validator.gate!!.complete(Unit)
        job.join()

        assertTrue(outcome!!.isSuccess)
        assertEquals("disabled", subscriptionDao.subs[1]!!.refreshPolicy)
        assertEquals(RefreshPolicy.Disabled, scheduler.scheduled.last().policy)
    }

    @Test
    fun `reconcileRefreshSchedules re-reads row and settings under the lock`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] =
            subscriptionDao.subs[1]!!.copy(refreshPolicy = "provider")
        settings.autoRefresh.value = 120

        repository.reconcileRefreshSchedules(inheritingOnly = false)

        // Explicit PROVIDER sees the current row — the global override is
        // passed along for inheriting rows, not applied to this one.
        scheduler.scheduled.last().let {
            assertEquals(1L, it.id)
            assertEquals(RefreshPolicy.Provider, it.policy)
            assertEquals(120, it.userOverride)
        }
    }

    @Test
    fun `global-change reconcile touches only inheriting rows`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        seedSubscription(id = 3, url = server.url("/third").toString())
        subscriptionDao.subs[2] = subscriptionDao.subs[2]!!.copy(refreshPolicy = "provider")
        subscriptionDao.subs[3] =
            subscriptionDao.subs[3]!!.copy(refreshPolicy = "fixed", refreshFixedMinutes = 90)

        repository.reconcileRefreshSchedules(inheritingOnly = true)

        // Only the inheriting row was re-resolved — explicit policies can't
        // observe the global value, so a global change leaves them alone.
        assertEquals(listOf(1L), scheduler.scheduled.map { it.id })
        assertEquals(
            RefreshPolicy.InheritGlobal,
            scheduler.scheduled.single().policy,
        )

        // The startup pass still covers every row.
        scheduler.scheduled.clear()
        repository.reconcileRefreshSchedules(inheritingOnly = false)
        assertEquals(setOf(1L, 2L, 3L), scheduler.scheduled.map { it.id }.toSet())
    }

    @Test
    fun `a user policy change survives a reconcile that listed before it`() = runTest {
        // The reconcile reads each row again under its own lock — a policy
        // written between the reconcile's list read and the lock is what
        // actually gets scheduled, never the stale list entry.
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        subscriptionDao.subs[2] = subscriptionDao.subs[2]!!.copy(refreshPolicy = "provider")

        // User sets Disabled on sub 1, then a global reconcile runs.
        repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
        repository.reconcileRefreshSchedules(inheritingOnly = false)

        val calls1 = scheduler.scheduled.filter { it.id == 1L }
        // Both the setter's reschedule and the reconcile see Disabled — the
        // earlier inherit snapshot can't resurrect a job.
        assertTrue(calls1.isNotEmpty())
        assertTrue(calls1.all { it.policy == RefreshPolicy.Disabled })
        assertEquals(RefreshPolicy.Provider, scheduler.scheduled.last { it.id == 2L }.policy)
    }

    @Test
    fun `the manual sentinel is never scheduled by a reconcile`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()
        settings.autoRefresh.value = 60

        repository.reconcileRefreshSchedules(inheritingOnly = false)

        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(listOf(id), scheduler.cancelled)
    }

    @Test
    fun `setRefreshPolicy propagates a scheduler failure`() = runTest {
        seedSubscription()
        scheduler.failure = IllegalStateException("workmanager down")

        var thrown: Throwable? = null
        try {
            repository.setRefreshPolicy(1, RefreshPolicy.Fixed(30))
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is IllegalStateException)
        // The write itself persisted — the failure is only about the
        // reschedule, and the UI must be told instead of showing success.
        assertEquals("fixed", subscriptionDao.subs[1]!!.refreshPolicy)
        assertEquals(30, subscriptionDao.subs[1]!!.refreshFixedMinutes)
    }

    @Test
    fun `refreshOnLaunch skips a row disabled after the launch snapshot`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(updateAlways = true)
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        // First queued launch refresh runs normally.
        assertTrue(repository.refreshOnLaunch(1).isSuccess)
        assertEquals(1, server.requestCount)

        // The user flips the policy to Disabled before a second launch pass
        // — the gate re-reads the row at fetch entry, so no network runs.
        repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
        val skipped = repository.refreshOnLaunch(1)

        assertTrue(skipped.isFailure)
        assertEquals(SubscriptionError.Superseded, skipped.exceptionOrNull())
        assertEquals(1, server.requestCount)

        // Manual refresh stays unconditional — Disabled blocks scheduled
        // work, not the user's explicit pull.
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))
        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a skipped launch refresh does not supersede a parked manual refresh`() = runTest {
        // Regression: the gate ran AFTER nextRefreshSeq, so a skipped launch
        // bump made the parked manual's commit stale and it was dropped.
        seedSubscription()
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(updateAlways = true)
        // Exactly one fetchable body — a second fetch would hang/fail.
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        validator.gate = CompletableDeferred()
        var manualOutcome: Result<RefreshOutcome>? = null
        val manualJob = launch { manualOutcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // User flips the policy to Disabled while the manual refresh is
        // parked; a queued launch refresh is then skipped at the gate.
        repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
        val skipped = repository.refreshOnLaunch(1)
        assertTrue(skipped.isFailure)
        assertEquals(SubscriptionError.Superseded, skipped.exceptionOrNull())
        assertEquals(1, server.requestCount)

        // The parked manual refresh is NOT stale — the skipped launch took
        // no ticket, so its candidate commits normally.
        validator.gate!!.complete(Unit)
        manualJob.join()
        assertTrue(manualOutcome!!.isSuccess)
        assertEquals(listOf("a.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(1, server.requestCount)
        assertEquals("disabled", subscriptionDao.subs[1]!!.refreshPolicy)
    }

    @Test
    fun `refreshOnLaunch skips a disabled subscription`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(updateAlways = true)
        repository.setEnabled(1, false)

        val result = repository.refreshOnLaunch(1)

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `same-url editUrl supersedes an in-flight refresh`() = runTest {
        // A refresh tickets seq=1 and parks in validation; an editUrl to the
        // same URL delegates to refreshLocked which must also ticket — the
        // parked refresh then sees a newer seq and drops its candidate.
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("stale.example.com", "Old")))
        server.enqueue(MockResponse().setBody(uri("fresh.example.com", "New")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var outcomeA: Result<RefreshOutcome>? = null
        var outcomeB: Result<RefreshOutcome>? = null
        val jobA = launch { outcomeA = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        // same-URL editUrl: goes through refreshLocked — before the fix it
        // took no ticket, so A's late commit would overwrite its node set.
        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        val jobB = launch { outcomeB = repository.editUrl(1, subscriptionDao.subs[1]!!.url) }
        parkInValidate(expectedCalls = 2)

        gateB.complete(Unit)
        jobB.join()
        assertTrue(outcomeB!!.isSuccess)
        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })

        gateA.complete(Unit)
        jobA.join()
        assertTrue(outcomeA!!.isFailure)
        assertEquals(SubscriptionError.Superseded, outcomeA!!.exceptionOrNull())
        assertEquals(listOf("fresh.example.com"), nodeDao.forSubscription(1).map { it.server })
        // A's late failure after editUrl's success must not write lastError.
        assertNull(subscriptionDao.subs[1]!!.lastError)
    }

    // ---- periodic refresh entry (WorkManager) ----

    @Test
    fun `periodic refresh fetches an eligible row without update-always`() = runTest {
        // updateAlways gates only the launch pass — a periodic slot exists on
        // the resolved interval alone and must not require it.
        seedSubscription()
        settings.autoRefresh.value = 60
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        assertTrue(repository.refreshPeriodic(1).isSuccess)
        assertEquals(1, server.requestCount)
        assertEquals(1, subscriptionDao.successCalls)
    }

    @Test
    fun `periodic refresh skips a Disabled policy without fetching`() = runTest {
        seedSubscription()
        repository.setRefreshPolicy(1, RefreshPolicy.Disabled)

        val result = repository.refreshPeriodic(1)

        assertTrue(result.isFailure)
        assertEquals(SubscriptionError.Superseded, result.exceptionOrNull())
        // The gate ran before the ticket AND the fetch: no network ran and
        // no attempt was recorded against the row.
        assertEquals(0, server.requestCount)
        assertEquals(0, subscriptionDao.successCalls)
        assertEquals(0, subscriptionDao.attemptCalls)
    }

    @Test
    fun `periodic refresh skips an enabled=false row without fetching`() = runTest {
        seedSubscription()
        repository.setEnabled(1, false)

        val result = repository.refreshPeriodic(1)

        assertTrue(result.isFailure)
        assertEquals(SubscriptionError.Superseded, result.exceptionOrNull())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `periodic refresh skips when no interval resolves`() = runTest {
        // Inherit + global off — the row can't observe a schedule.
        seedSubscription()
        settings.autoRefresh.value = -1
        // Explicit Provider without a usable hint is manual-only — not a
        // global fallback.
        seedSubscription(id = 2, url = server.url("/other").toString())
        subscriptionDao.subs[2] = subscriptionDao.subs[2]!!.copy(refreshPolicy = "provider")

        assertEquals(
            SubscriptionError.Superseded,
            repository.refreshPeriodic(1).exceptionOrNull(),
        )
        assertEquals(
            SubscriptionError.Superseded,
            repository.refreshPeriodic(2).exceptionOrNull(),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `periodic refresh on a deleted row reports NotFound`() = runTest {
        // NotFound — not Superseded: the worker reads it as "row deleted" and
        // cancels its own job. Superseded would keep the job alive forever.
        val result = repository.refreshPeriodic(42)

        assertTrue(result.isFailure)
        assertEquals(SubscriptionError.NotFound, result.exceptionOrNull())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `periodic refresh skips the manual sentinel`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        val result = repository.refreshPeriodic(id)

        // Superseded, not NotFound — the row exists; it's just not fetchable.
        assertEquals(SubscriptionError.Superseded, result.exceptionOrNull())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a skipped periodic refresh does not supersede a parked manual refresh`() =
        runTest {
            // Same contract as the launch gate: the periodic gate runs BEFORE
            // nextRefreshSeq — a skipped run takes no ticket, so a manual
            // refresh parked in validation still owns the commit.
            seedSubscription()
            settings.autoRefresh.value = 60
            server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            validator.gate = CompletableDeferred()
            var manualOutcome: Result<RefreshOutcome>? = null
            val manualJob = launch { manualOutcome = repository.refresh(1) }
            parkInValidate(expectedCalls = 1)

            repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
            val skipped = repository.refreshPeriodic(1)
            assertEquals(SubscriptionError.Superseded, skipped.exceptionOrNull())
            assertEquals(1, server.requestCount)

            validator.gate!!.complete(Unit)
            manualJob.join()
            assertTrue(manualOutcome!!.isSuccess)
            assertEquals(listOf("a.example.com"), nodeDao.forSubscription(1).map { it.server })
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a periodic refresh does not commit after the policy is disabled mid-flight`() =
        runTest {
            // The gate re-runs inside the commit lock: a row eligible at
            // ticket time but Disabled while the fetch was parked must keep
            // last-known-good — the candidate is dropped, not committed.
            seedSubscription()
            settings.autoRefresh.value = 60
            val old = nodeEntity(uri("old.example.com", "Old"), 1)
            nodeDao.nodes[old.id] = old
            server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            validator.gate = CompletableDeferred()
            var outcome: Result<RefreshOutcome>? = null
            val job = launch { outcome = repository.refreshPeriodic(1) }
            parkInValidate(expectedCalls = 1)

            repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
            validator.gate!!.complete(Unit)
            job.join()

            assertTrue(outcome!!.isFailure)
            assertEquals(SubscriptionError.Superseded, outcome!!.exceptionOrNull())
            assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
            assertEquals(0, subscriptionDao.successCalls)
        }

    @Test
    fun `a manual refresh still commits after the policy is disabled mid-flight`() =
        runTest {
            // Sibling of the periodic re-gate: Disabled blocks scheduled work,
            // never the user's explicit pull — the parked manual refresh
            // commits normally.
            seedSubscription()
            val old = nodeEntity(uri("old.example.com", "Old"), 1)
            nodeDao.nodes[old.id] = old
            server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            validator.gate = CompletableDeferred()
            var outcome: Result<RefreshOutcome>? = null
            val job = launch { outcome = repository.refresh(1) }
            parkInValidate(expectedCalls = 1)

            repository.setRefreshPolicy(1, RefreshPolicy.Disabled)
            validator.gate!!.complete(Unit)
            job.join()

            assertTrue(outcome!!.isSuccess)
            assertEquals(listOf("a.example.com"), nodeDao.forSubscription(1).map { it.server })
            assertEquals(1, subscriptionDao.successCalls)
        }

    // ---- reconcile aggregate result + current-row orphan prune ----

    @Test
    fun `reconcile reports incomplete when a row reschedule fails`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        scheduler.failForIds = setOf(2L)

        assertFalse(repository.reconcileRefreshSchedules(inheritingOnly = false))
        // Row 1 rescheduled fine; only 2's job is missing.
        assertEquals(listOf(1L), scheduler.scheduled.map { it.id })

        // The next full pass repairs the missing job and reports complete.
        scheduler.failForIds = emptySet()
        assertTrue(repository.reconcileRefreshSchedules(inheritingOnly = false))
        assertEquals(setOf(1L, 2L), scheduler.scheduled.map { it.id }.toSet())
    }

    @Test
    fun `reconcile reports incomplete when the orphan prune fails`() = runTest {
        seedSubscription()
        scheduler.reconcileFailure = IllegalStateException("wm query failed")

        assertFalse(repository.reconcileRefreshSchedules(inheritingOnly = false))
        // The row pass still ran — the failure is reported, not hidden.
        assertEquals(listOf(1L), scheduler.scheduled.map { it.id })
    }

    @Test
    fun `orphan prune checks the current row, not the reconcile snapshot`() = runTest {
        seedSubscription()
        // Live jobs for 1 (row exists), 5 (genuine orphan) and 99 — where 99's
        // row is only inserted between the reconcile's list read and the
        // scheduler's existence check.
        scheduler.pendingWorkIds += listOf(1L, 5L, 99L)
        scheduler.onReconcile = {
            seedSubscription(id = 99, url = server.url("/late").toString())
        }

        assertTrue(repository.reconcileRefreshSchedules(inheritingOnly = false))

        // Every candidate went through the callback; a stale id snapshot
        // would have pruned 99's job — only the real orphan 5 was cancelled.
        assertEquals(setOf(1L, 5L, 99L), scheduler.existenceChecks.toSet())
        assertEquals(listOf(5L), scheduler.cancelled)
    }

    @Test
    fun `a periodic commit-gate read failure is a typed failure, commits nothing`() =
        runTest {
            // The commit re-gate re-reads row + settings inside the lock — a
            // DataStore failure there must land as Result.failure, not escape
            // the refresh() contract as a thrown exception.
            seedSubscription()
            settings.autoRefresh.value = 60
            val old = nodeEntity(uri("old.example.com", "Old"), 1)
            nodeDao.nodes[old.id] = old
            server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
            // Read #1 is the admission gate (passes, interval resolves);
            // read #2 is the commit re-gate and throws.
            settings.autoRefreshReadsUntilFailure = 1

            val result = repository.refreshPeriodic(1)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SubscriptionError)
            // Fetch ran (admission passed) but nothing committed — nodes and
            // success metadata untouched, attempt recorded as typed failure.
            assertEquals(1, server.requestCount)
            assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
            assertEquals(0, subscriptionDao.successCalls)
        }

    @Test
    fun `a cancelled commit-gate eligibility check propagates without commit`() =
        runTest {
            // Same injection point: a CancellationException at the re-gate
            // must propagate (structured cancellation), not collapse into a
            // typed refresh failure.
            seedSubscription()
            settings.autoRefresh.value = 60
            server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
            settings.autoRefreshReadsUntilFailure = 1
            settings.autoRefreshReadError = { CancellationException() }

            var outcome: Result<RefreshOutcome>? = null
            val job = launch { outcome = repository.refreshPeriodic(1) }
            job.join()

            assertTrue(job.isCancelled)
            assertNull(outcome)
            assertEquals(0, nodeDao.replaceCalls)
            assertEquals(0, subscriptionDao.successCalls)
        }

    @Test
    fun `a periodic url-change re-pass re-gates before committing`() = runTest {
        // Regression: the url-changed replacement commit used to skip the
        // periodic gate — a global-off write during the under-lock
        // replacement fetch (a DataStore write takes no subscription lock)
        // let a stale candidate commit into an unschedulable row.
        seedSubscription()
        settings.autoRefresh.value = 60
        // 1: periodic's initial fetch of /sub; 2: editUrl's /sub2 fetch;
        // 3: the periodic run's replacement fetch of /sub2 under the lock.
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        server.enqueue(MockResponse().setBody(uri("c.example.com", "C")))
        server.enqueue(MockResponse().setBody(uri("d.example.com", "D")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var periodicOutcome: Result<RefreshOutcome>? = null
        var editOutcome: Result<RefreshOutcome>? = null
        val periodicJob = launch { periodicOutcome = repository.refreshPeriodic(1) }
        parkInValidate(expectedCalls = 1)

        // Swap the gate BEFORE editUrl reaches validate() — the in-flight
        // call already captured gateA, a new call needs its own deferred.
        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        // editUrl takes the subscription lock across its own fetch+commit —
        // when it finishes, the row points at /sub2 and the periodic run's
        // phase 2 re-fetches under the held lock.
        val editJob =
            launch { editOutcome = repository.editUrl(1, server.url("/sub2").toString()) }
        parkInValidate(expectedCalls = 2)
        gateB.complete(Unit)
        editJob.join()
        assertTrue(editOutcome!!.isSuccess)
        assertEquals(server.url("/sub2").toString(), subscriptionDao.subs[1]!!.url)
        assertEquals(listOf("c.example.com"), nodeDao.forSubscription(1).map { it.server })

        // Release the periodic run's parked first validation — it commits
        // nothing until the under-lock replacement fetch of /sub2 validates.
        val gateC = CompletableDeferred<Unit>()
        validator.gate = gateC
        gateA.complete(Unit)
        parkInValidate(expectedCalls = 3)
        // Global "off" lands mid-replacement — a DataStore write needs no
        // subscription lock, so it can interleave even under lockFor.
        settings.autoRefresh.value = -1
        gateC.complete(Unit)
        periodicJob.join()

        assertTrue(periodicOutcome!!.isFailure)
        assertEquals(SubscriptionError.Superseded, periodicOutcome!!.exceptionOrNull())
        // editUrl's commit stands — the dropped replacement wrote nothing.
        assertEquals(listOf("c.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(server.url("/sub2").toString(), subscriptionDao.subs[1]!!.url)
        assertEquals(1, subscriptionDao.urlSuccessCalls)
        assertEquals(0, subscriptionDao.successCalls)
    }

    @Test
    fun `a manual url-change re-pass commits even with global refresh off`() = runTest {
        // Sibling of the periodic case: the manual pull is never re-gated —
        // its replacement commit lands even after global auto-refresh is off.
        seedSubscription()
        settings.autoRefresh.value = 60
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))
        server.enqueue(MockResponse().setBody(uri("c.example.com", "C")))
        server.enqueue(MockResponse().setBody(uri("d.example.com", "D")))

        val gateA = CompletableDeferred<Unit>()
        validator.gate = gateA
        var refreshOutcome: Result<RefreshOutcome>? = null
        var editOutcome: Result<RefreshOutcome>? = null
        val refreshJob = launch { refreshOutcome = repository.refresh(1) }
        parkInValidate(expectedCalls = 1)

        val gateB = CompletableDeferred<Unit>()
        validator.gate = gateB
        val editJob =
            launch { editOutcome = repository.editUrl(1, server.url("/sub2").toString()) }
        parkInValidate(expectedCalls = 2)
        gateB.complete(Unit)
        editJob.join()
        assertTrue(editOutcome!!.isSuccess)

        val gateC = CompletableDeferred<Unit>()
        validator.gate = gateC
        gateA.complete(Unit)
        parkInValidate(expectedCalls = 3)
        settings.autoRefresh.value = -1
        gateC.complete(Unit)
        refreshJob.join()

        assertTrue(refreshOutcome!!.isSuccess)
        // The manual run's replacement candidate committed — unconditional.
        assertEquals(listOf("d.example.com"), nodeDao.forSubscription(1).map { it.server })
        assertEquals(1, subscriptionDao.successCalls)
    }
}
