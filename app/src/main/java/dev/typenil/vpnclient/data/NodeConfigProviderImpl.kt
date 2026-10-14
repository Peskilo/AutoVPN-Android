package dev.typenil.vpnclient.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.engine.singbox.RuleSetStore
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.summary
import dev.typenil.vpnclient.core.subscription.model.isTunnelAllowed
import dev.typenil.vpnclient.core.subscription.model.isRussianServer
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.net.Inet6Address
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeConfigProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val nodeDao: NodeDao,
        private val settings: SettingsRepository,
        private val compiler: ConfigCompiler,
        private val ruleSetStore: RuleSetStore,
        private val routingRuleDao: dev.typenil.vpnclient.data.db.RoutingRuleDao,
    ) : NodeConfigProvider {
        override val selectedNodeId: Flow<String?> = settings.selectedNodeId

        /** Fingerprint of the node set the last compile ran against. Written
         *  from the same DB read the compile used, so a live session can
         *  detect a change without racing the compile itself. */
        private val _compiledNodeSetFingerprint = MutableStateFlow<String?>(null)
        override val compiledNodeSetFingerprint: StateFlow<String?> =
            _compiledNodeSetFingerprint.asStateFlow()

        /** Global-IPv6 reachability of the *physical* underlay — pushed by
         *  ClientVpnService's NOT_VPN-tracked observer. Reading it here
         *  (not `activeNetwork`) keeps a live-session recompile from
         *  resolving the VPN's own default network as the underlay.
         *
         *  Default is optimistic because a first compile may race the
         *  tracker's first callback; the conservative read happens in
         *  [resolveUnderlayIpv6] which probes the physical network directly
         *  when no tracker value has landed yet. */
        @Volatile
        override var underlayHasIpv6: Boolean = true

        /** True once the service has pushed a value — distinguishes
         *  "tracker hasn't run yet" from "tracker reported no v6". Only
         *  trusted while a tracker epoch is active (see [underlayEpoch]). */
        @Volatile
        private var underlayReported: Boolean = false

        /** Epoch of the current underlay tracker owner. Each
         *  [acquireUnderlayEpoch] bumps it and clears [underlayReported] so a
         *  previous session's snapshot can't leak into the next compile;
         *  [releaseUnderlayEpoch] only invalidates when its epoch still
         *  matches — a stale teardown can't clobber a newer owner's value. */
        @Volatile
        private var underlayEpoch: Long = 0

        override fun acquireUnderlayEpoch(): Long {
            underlayEpoch++
            underlayReported = false
            return underlayEpoch
        }

        override fun releaseUnderlayEpoch(epoch: Long) {
            if (epoch == underlayEpoch) {
                underlayReported = false
            }
        }

        override val enabledNodeSetFingerprint: Flow<String> =
            // The fingerprint tracks the effective set — a pref-disabled node
            // changes the compiled config, so it must change the fingerprint
            // (else a live session would never rebuild for a node toggle).
            nodeDao
                .observeUsable()
                .map { entities -> nodeSetFingerprint(entities) }
                // Hashing a few hundred outbound JSONs — off the collector's
                // thread (the service collects on the main dispatcher).
                .flowOn(Dispatchers.Default)

        override suspend fun nodeSummary(id: String): NodeSummary? =
            // The Auto sentinel has no node row — its label comes from the
            // compiler's AUTO_NODE_SUMMARY, not the database.
            when (id) {
                NodeSelection.AUTO_ID -> ConfigCompiler.AUTO_NODE_SUMMARY
                NodeSelection.AUTO_RUSSIA_ID -> ConfigCompiler.AUTO_RUSSIA_NODE_SUMMARY
                else -> nodeDao.get(id)?.toDomain()?.summary()
            }

        /*
                nodeDao.get(id)?.toDomain()?.summary()
            }

        override suspend fun isSelectionAllowed(id: String): Boolean =
            if (id == NodeSelection.AUTO_ID) {
                nodeDao.getUsable().any { isTunnelAllowed(it.toDomain()) }
            } else {
                // Missing nodes retain the compiler's existing stale-selection handling.
                nodeDao.get(id)?.let { isTunnelAllowed(it.toDomain()) } ?: true
            }

        override suspend fun revertSelectionIf(rejected: String, to: String): Boolean =
            settings.replaceSelectedNodeIdIf(rejected, to)

        override suspend fun compileSelected(): EngineConfig? {
            val entities = nodeDao.getUsable()
            val nodes = entities.map { it.toDomain() }
            if (nodes.isEmpty()) {
                // Nothing to compile — record it so a live session comparing
                // fingerprints sees the emptied set rather than a stale one.
                // Null (not the empty-set digest): no config was built.
                _compiledNodeSetFingerprint.value = null
                return null
            } // A refresh can commit a node set that no longer contains the
            // persisted selection before its post-commit cleanup runs — clear it
            // here so a connect in that window falls back instead of failing.
            // The conditional clear can't wipe a selection the user just made.
            val persisted = settings.selectedNodeId.first()
            if (persisted != null &&
                persisted != NodeSelection.AUTO_ID &&
                persisted != NodeSelection.AUTO_RUSSIA_ID &&
                nodes.none { it.id == persisted }
            ) {
                settings.clearSelectedNodeIdIf(persisted)
            }
            // Re-read after the conditional clear: a concurrent user pick
            // lands here, and a still-stale id keeps failing loudly.
            val pick = settings.selectedNodeId.first()
            val routeMode = settings.routeMode.first()
            val compiled =
                compiler.compile(
                    nodes = nodes,
                    selectedNodeId = pick,
                    ipv6Enabled = settings.ipv6Enabled.first(),
                    routeMode = routeMode,
                    underlayIpv6 = resolveUnderlayIpv6(),
                    ruleSetPaths = ruleSetStore.ensureReady(routeMode),
                    selectAuto = pick == NodeSelection.AUTO_ID || pick == NodeSelection.AUTO_RUSSIA_ID,
                    bypassLan = settings.bypassLan.first(),
                    dnsProfile = settings.dnsProfile.first(),
                    userRules = userRoutingRules(),
                )
            // Only a successful compile becomes the baseline — a throw leaves
            // the previous fingerprint so the change stays pending.
            _compiledNodeSetFingerprint.value = nodeSetFingerprint(entities)
            return compiled
        }

        /** Enabled user rules in table order — a malformed stored row is
         *  dropped rather than failing the whole connect (the editor's
         *  validation is the gate; the read path stays defensive). */
        private suspend fun userRoutingRules() =
            routingRuleDao
                .getAll()
                .filter { it.isEnabled }
                .mapNotNull { entity ->
                    val kind =
                        dev.typenil.vpnclient.core.engine.RoutingRule.Kind
                            .fromKey(entity.kind)
                            ?: return@mapNotNull null
                    val action =
                        dev.typenil.vpnclient.core.engine.RoutingRule.Action
                            .fromKey(entity.action)
                            ?: return@mapNotNull null
                    dev.typenil.vpnclient.core.engine.RoutingRule(
                        kind = kind,
                        pattern = entity.pattern,
                        action = action,
                    )
                }

        /** The service pushes [underlayHasIpv6] and marks it reported;
         *  until then the compile path can't trust the optimistic default —
         *  the first connect() runs before the NOT_VPN tracker fires.
         *  An epoch acquired then released without a report leaves
         *  [underlayReported] false, so the next compile re-probes. */
        override fun reportUnderlay(hasIpv6: Boolean) {
            underlayHasIpv6 = hasIpv6
            underlayReported = true
        }

        /** The underlay signal the compile actually uses: the service's
         *  NOT_VPN-tracked value once reported; before that, a one-shot
         *  physical-network probe (never `activeNetwork` — self is tunneled
         *  once a session is live). An unknown/no-network read stays true:
         *  v6-via-proxy still works, and a wrong "no v6" would force proxy
         *  for every v6 host on a network that could carry it direct. */
        private fun resolveUnderlayIpv6(): Boolean {
            if (underlayReported) return underlayHasIpv6
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            // Find a physical candidate: INTERNET + NOT_VPN, prefer the
            // app's active network when it isn't the tunnel itself.
            val candidates =
                cm.allNetworks.filter { n ->
                    val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            val active =
                cm.activeNetwork?.takeIf { candidates.contains(it) } ?: candidates.firstOrNull()
                    ?: return true
            val link = cm.getLinkProperties(active) ?: return true
            val hasDefaultV6Route =
                link.routes.any { route ->
                    route.destination.address is Inet6Address && route.destination.prefixLength == 0
                }
            return hasDefaultV6Route &&
                link.linkAddresses.any { (it.address as? Inet6Address)?.let(::isGlobalIpv6) == true }
        }
    }

/** Global-unicast IPv6: not link-local, site-local, ULA, loopback,
 *  multicast, or the unspecified `::` address. Pure — JVM-testable. */
internal fun isGlobalIpv6(address: Inet6Address): Boolean =
    !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress && !address.isLoopbackAddress &&
        !address.isMulticastAddress &&
        // ULA fc00::/7 is not global reachability either.
        (address.address[0].toInt() and 0xfe) != 0xfc

fun NodeEntity.toDomain(): ProxyNode =
    ProxyNode(
        id = id,
        subscriptionId = subscriptionId,
        name = name,
        protocol = runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.OTHER),
        server = server,
        port = port,
        outboundJson = outboundJson,
        rawUri = rawUri,
    )

/**
 * Stable digest of the enabled node set's tunnel-relevant content: node ids
 * plus their outbound JSON. Order-insensitive (row position is presentation
 * and a refresh that only reorders must not force a rebuild) and blind to
 * display-only fields (name, protocol label) — the compiled outbound list is
 * what a live session depends on. An empty set digests to a stable value of
 * its own, never to null. Pure — JVM-testable.
 */
internal fun nodeSetFingerprint(entities: List<NodeEntity>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entities.sortedBy { it.id }.forEach { node ->
        digest.update(node.id.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(node.outboundJson.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
