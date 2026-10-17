package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.DnsMode
import dev.typenil.vpnclient.core.engine.DnsProfile
import dev.typenil.vpnclient.core.engine.DnsUpstream
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.GEOSITE_RU_TAG
import dev.typenil.vpnclient.core.engine.LanBypassRoutes
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.RoutingRule
import dev.typenil.vpnclient.core.engine.URLTEST_INTERVAL_MINUTES
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.summary
import dev.typenil.vpnclient.core.subscription.model.isTunnelAllowed
import dev.typenil.vpnclient.core.subscription.model.isRussianServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiles parsed nodes into a sing-box configuration.
 *
 * Layout: every node becomes a named outbound; a `selector` group "proxy" points at the
 * selected node (switchable at runtime via CommandClient.selectOutbound) and an `urltest`
 * group "auto" measures latency across all nodes.
 */
@Singleton
class ConfigCompiler
    @Inject
    constructor() {
        private val json = Json { ignoreUnknownKeys = true }

        /** Build + validate (native `checkConfig`) — used on the connect path. */
        suspend fun compile(
            nodes: List<ProxyNode>,
            selectedNodeId: String?,
            ipv6Enabled: Boolean,
            routeMode: RouteMode = RouteMode.ALL,
            underlayIpv6: Boolean = true,
            ruleSetPaths: Map<String, String> = emptyMap(),
            selectAuto: Boolean = false,
            bypassLan: Boolean = false,
            dnsProfile: DnsProfile = DnsProfile(DnsMode.POLICY, DnsUpstream.Cloudflare),
            userRules: List<RoutingRule> = emptyList(),
        ): EngineConfig =
            withContext(Dispatchers.IO) {
                val compiled =
                    build(
                        nodes,
                        selectedNodeId,
                        ipv6Enabled,
                        routeMode,
                        underlayIpv6,
                        ruleSetPaths,
                        selectAuto,
                        bypassLan,
                        dnsProfile,
                        userRules,
                    )
                checkNative(compiled)
                compiled
            }

        /** Pure JSON construction — JVM-testable (no native calls). */
        fun build(
            nodes: List<ProxyNode>,
            selectedNodeId: String?,
            ipv6Enabled: Boolean,
            routeMode: RouteMode = RouteMode.ALL,
            underlayIpv6: Boolean = true,
            ruleSetPaths: Map<String, String> = emptyMap(),
            selectAuto: Boolean = false,
            bypassLan: Boolean = false,
            dnsProfile: DnsProfile = DnsProfile(DnsMode.POLICY, DnsUpstream.Cloudflare),
            userRules: List<RoutingRule> = emptyList(),
        ): EngineConfig {
            require(nodes.isNotEmpty()) { "no nodes to compile" }
            if (selectedNodeId != null && !selectAuto) {
                val selected = nodes.firstOrNull { it.id == selectedNodeId }
                    ?: throw EngineError.InvalidConfig("selected node no longer exists")
                if (!isTunnelAllowed(selected)) throw EngineError.UnencryptedTransport
            }
            val allowed = nodes.filter(::isTunnelAllowed)
            if (allowed.isEmpty()) throw EngineError.UnencryptedTransport
            if (selectAuto) {
                val candidates = if (selectedNodeId == NodeSelection.AUTO_RUSSIA_ID) {
                    allowed.filter(ProxyNode::isRussianServer)
                } else {
                    allowed.filterNot(ProxyNode::isRussianServer)
                }
                if (candidates.isEmpty()) {
                    throw EngineError.InvalidConfig(
                        if (selectedNodeId == NodeSelection.AUTO_RUSSIA_ID) {
                            "no Russian servers found"
                        } else {
                            "no non-Russian servers found"
                        },
                    )
                }
            }
            return assemble(allowed, selectedNodeId, ipv6Enabled, routeMode, underlayIpv6,
                ruleSetPaths, selectAuto, bypassLan, dnsProfile, userRules)
        }

        /** Import validation keeps every candidate; never used for a running tunnel. */
        suspend fun validateCandidates(
            nodes: List<ProxyNode>,
            routeMode: RouteMode,
            ruleSetPaths: Map<String, String>,
        ) = withContext(Dispatchers.IO) {
            checkNative(buildCandidate(nodes, routeMode, ruleSetPaths))
        }

        internal fun buildCandidate(
            nodes: List<ProxyNode>,
            routeMode: RouteMode = RouteMode.ALL,
            ruleSetPaths: Map<String, String> = emptyMap(),
        ): EngineConfig = assemble(nodes, null, true, routeMode, ruleSetPaths = ruleSetPaths)

        private fun checkNative(config: EngineConfig) {
            try {
                Libbox.checkConfig(config.configJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw EngineError.InvalidConfig("config rejected")
            }
        }

        private fun assemble(
            nodes: List<ProxyNode>,
            selectedNodeId: String?,
            ipv6Enabled: Boolean,
            routeMode: RouteMode = RouteMode.ALL,
            underlayIpv6: Boolean = true,
            ruleSetPaths: Map<String, String> = emptyMap(),
            selectAuto: Boolean = false,
            bypassLan: Boolean = false,
            dnsProfile: DnsProfile = DnsProfile(DnsMode.POLICY, DnsUpstream.Cloudflare),
            userRules: List<RoutingRule> = emptyList(),
        ): EngineConfig {
            require(nodes.isNotEmpty()) { "no nodes to compile" }
            // Local rule sets only — a missing file must fail at compile, never
            // surface as a silently-wrong route or an engine-start fetch error.
            require(ruleSetPaths.keys.containsAll(routeMode.ruleSetTags)) {
                "missing rule set files for ${routeMode.key}"
            }
            // A stale selection (node removed by a refresh) must fail loudly —
            // silently connecting to a different server surprises the user.
            // [selectAuto] skips the node lookup entirely: the selector default
            // becomes the urltest group and EngineConfig.node describes Auto
            // itself — no node is impersonated as the session node.
            val selected =
                when {
                    selectAuto -> {
                        null
                    }

                    selectedNodeId == null -> {
                        nodes.first()
                    }

                    else -> {
                        nodes.firstOrNull { it.id == selectedNodeId }
                            ?: throw EngineError.InvalidConfig("selected node no longer exists")
                    }
                }
            val nodeTags = nodes.map { it.id }
            val russianNodes = nodes.filter(ProxyNode::isRussianServer)
            val nonRussianNodes = nodes.filterNot(ProxyNode::isRussianServer)
            val (endpointNodes, outboundNodes) = nodes.partition {
                (json.parseToJsonElement(it.outboundJson) as JsonObject)["type"] == JsonPrimitive("wireguard")
            }

            val outbounds =
                buildJsonArray {
                    addJsonObject {
                        put("type", "selector")
                        put("tag", SELECTOR_TAG)
                        putJsonArray("outbounds") {
                            if (nonRussianNodes.isNotEmpty()) add(AUTO_TAG)
                            if (russianNodes.isNotEmpty()) add(AUTO_RUSSIA_TAG)
                            nodeTags.forEach { add(it) }
                        }
                        put(
                            "default",
                            selected?.id ?: if (selectedNodeId == NodeSelection.AUTO_RUSSIA_ID) {
                                AUTO_RUSSIA_TAG
                            } else {
                                AUTO_TAG
                            },
                        )
                        put("interrupt_exist_connections", false)
                    }
                    if (nonRussianNodes.isNotEmpty()) {
                        addJsonObject {
                            put("type", "urltest")
                            put("tag", AUTO_TAG)
                            putJsonArray("outbounds") { nonRussianNodes.forEach { add(it.id) } }
                            put("url", "https://cp.cloudflare.com/generate_204")
                            put("interval", "${URLTEST_INTERVAL_MINUTES}m")
                            put("tolerance", 50)
                            put("idle_timeout", "20m")
                            put("interrupt_exist_connections", false)
                        }
                    }
                    if (russianNodes.isNotEmpty()) {
                        addJsonObject {
                            put("type", "urltest")
                            put("tag", AUTO_RUSSIA_TAG)
                            putJsonArray("outbounds") { russianNodes.forEach { add(it.id) } }
                            put("url", "https://cp.cloudflare.com/generate_204")
                            put("interval", "${URLTEST_INTERVAL_MINUTES}m")
                            put("tolerance", 50)
                            put("idle_timeout", "20m")
                            put("interrupt_exist_connections", false)
                        }
                    }
                    // Outbound-shaped nodes only — WireGuard nodes compile to
                    // `endpoints[]` below (the wireguard outbound was removed in
                    // sing-box 1.13; endpoint tags are still selectable/urltestable).
                    outboundNodes.forEach { node ->
                        add(runtimeOutbound(node))
                    }
                    addJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    }
                }

            val config =
                buildJsonObject {
                    putJsonObject("log") {
                        put("level", "warn")
                        put("timestamp", true)
                    }
                    putJsonObject("dns") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("type", "local")
                                put("tag", "local")
                            }
                            addJsonObject {
                                val upstream = dnsProfile.upstream
                                val url = upstream.serverUrl
                                val scheme = url.substringBefore("://")
                                val rest = url.substringAfter("://")
                                put(
                                    "type",
                                    when (scheme) {
                                        "https" -> "https"
                                        "tls" -> "tls"
                                        "quic" -> "quic"
                                        else -> "udp"
                                    },
                                )
                                put("tag", "remote")
                                // sing-box's typed dns schema wants the bare
                                // host in `server` — not a URL, not host:port.
                                // https gets `path` + `server_port`; tls/quic
                                // get `server_port`; udp is bare host.
                                val authority = rest.substringBefore('/')
                                val (host, port) =
                                    dev.typenil.vpnclient.core.engine
                                        .splitHostPort(authority)
                                put("server", host)
                                port?.toIntOrNull()?.let { put("server_port", it) }
                                if (scheme == "https") {
                                    rest
                                        .substringAfter('/', "")
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { put("path", "/$it") }
                                }
                                // DNS resolution dials like an outbound: without a
                                // detour it goes direct, leaking queries past the
                                // proxy in policy/proxy-only mode alike.
                                put("detour", SELECTOR_TAG)
                                if (upstream.hostname) {
                                    // Hostname upstreams (e.g. AdGuard) need
                                    // bootstrap resolution — route it through the
                                    // local resolver so remote→proxy→remote can't
                                    // form a loop.
                                    putJsonObject("domain_resolver") {
                                        put("server", "local")
                                    }
                                }
                            }
                        }
                        when {
                            // Proxy-only: every user query rides the proxied
                            // upstream — no dns.rules entry may point at `local`
                            // for user traffic; `local` exists solely as the
                            // bootstrap resolver (its own hostname, node names).
                            dnsProfile.mode == DnsMode.PROXY_ONLY -> {}

                            // RU domains resolve via the ISP resolver so the direct
                            // route gets CDN-local answers; everything else keeps the
                            // proxied upstream. IPv4-only: an AAAA answer wins
                            // client-side ordering (the tun advertises v6), and a
                            // v6 RU dial is a dead end on IPv4-only underlays.
                            routeMode == RouteMode.BYPASS_RU -> {
                                putJsonArray("rules") {
                                    addJsonObject {
                                        putJsonArray("rule_set") { add(GEOSITE_RU_TAG) }
                                        put("server", "local")
                                        put("strategy", "ipv4_only")
                                    }
                                }
                            }

                            // Blocked domains must not touch ISP DNS (spoofed answers)
                            // — they resolve through the proxied upstream. Everything
                            // else stays on the local resolver below.
                            routeMode == RouteMode.PROXY_BLOCKED -> {
                                putJsonArray("rules") {
                                    addJsonObject {
                                        putJsonArray("rule_set") {
                                            routeMode.ruleSetTags.forEach { add(it) }
                                        }
                                        put("server", "remote")
                                    }
                                }
                            }

                            else -> {}
                        }
                        put(
                            "final",
                            when {
                                dnsProfile.mode == DnsMode.PROXY_ONLY -> "remote"
                                routeMode == RouteMode.PROXY_BLOCKED -> "local"
                                else -> "remote"
                            },
                        )
                        put("strategy", if (ipv6Enabled) "prefer_ipv4" else "ipv4_only")
                        // IP→domain map so rule sets can match connections dialed by
                        // bare IP — non-sniffable traffic (MTProto, ECH) otherwise
                        // falls through to `final` and leaks past the proxy rules.
                        put("reverse_mapping", true)
                    }
                    putJsonArray("inbounds") {
                        addJsonObject {
                            put("type", "tun")
                            put("tag", "tun-in")
                            put("mtu", 9000)
                            putJsonArray("address") {
                                add(TUN_V4)
                                if (ipv6Enabled) add(TUN_V6)
                            }
                            put("auto_route", true)
                            put("strict_route", false)
                            if (bypassLan) {
                                // One exclusion list per family — libbox maps
                                // these to TunOptions.inet*RouteExcludeAddress,
                                // surfaced to openTun as TunRequest excluded
                                // routes (excludeRoute on API 33+, complement
                                // prefixes below). The TUN's own subnets live
                                // INSIDE the excluded ranges; the service adds
                                // explicit more-specific routes for them so
                                // virtual-DNS hijack keeps working.
                                putJsonArray("route_exclude_address") {
                                    LanBypassRoutes.excludedV4.forEach {
                                        add("${it.address}/${it.prefix}")
                                    }
                                    if (ipv6Enabled) {
                                        LanBypassRoutes.excludedV6.forEach {
                                            add("${it.address}/${it.prefix}")
                                        }
                                    }
                                }
                            }
                            // gvisor is required on pinned libbox 1.14.1 (system stack
                            // fails inbound TCP there); revisit on a 1.15+ upgrade.
                            put("stack", "gvisor")
                        }
                    }
                    put("outbounds", outbounds)
                    if (endpointNodes.isNotEmpty()) {
                        putJsonArray("endpoints") {
                            endpointNodes.forEach { node ->
                                add(json.parseToJsonElement(node.outboundJson))
                            }
                        }
                    }
                    putJsonObject("route") {
                        // Required for socket protection: without it the core never
                        // calls autoDetectInterfaceControl, so outbound sockets stay
                        // unprotected — fine only while our own package is disallowed
                        // on the TUN. With self routed through the tunnel, an
                        // unprotected engine socket loops back into the TUN and the
                        // tunnel is dead.
                        put("auto_detect_interface", true)
                        putJsonArray("rules") {
                            addJsonObject { put("action", "sniff") }
                            addJsonObject {
                                put("protocol", "dns")
                                put("action", "hijack-dns")
                            }
                            // User rules sit ahead of every built-in
                            // fallback — including ip_is_private: blocking or
                            // proxying a private range is a legitimate pick
                            // the fallback must not shadow. (With bypassLan
                            // on, excluded LAN prefixes never reach sing-box
                            // at all — rules on them are inert, which the
                            // bypass switch already implies.)
                            userRules.forEach { rule ->
                                addJsonObject {
                                    when (rule.kind) {
                                        // sing-box wants numeric ports and a
                                        // string port_range — a bare "443"
                                        // string would fail checkConfig.
                                        RoutingRule.Kind.PORT -> {
                                            if (':' in rule.pattern) {
                                                putJsonArray("port_range") {
                                                    add(rule.pattern)
                                                }
                                            } else {
                                                putJsonArray("port") {
                                                    add(rule.pattern.toInt())
                                                }
                                            }
                                        }

                                        else -> {
                                            putJsonArray(rule.matchField) {
                                                add(rule.pattern)
                                            }
                                        }
                                    }
                                    when (rule.action) {
                                        RoutingRule.Action.BLOCK -> put("action", "reject")
                                        else -> put("outbound", rule.outboundTag)
                                    }
                                }
                            }
                            addJsonObject {
                                put("ip_is_private", true)
                                put("outbound", "direct")
                            }
                            // Without underlay IPv6 a "direct" v6 dial is dead on
                            // arrival, so v6 goes through the proxy (which carries it
                            // fine). ALL mode already routes everything to the proxy.
                            if (routeMode != RouteMode.ALL && !underlayIpv6) {
                                addJsonObject {
                                    put("ip_version", 6)
                                    put("outbound", SELECTOR_TAG)
                                }
                            }
                            when (routeMode) {
                                // RU resources bypass the proxy entirely.
                                RouteMode.BYPASS_RU -> {
                                    addJsonObject {
                                        putJsonArray("rule_set") {
                                            routeMode.ruleSetTags.forEach { add(it) }
                                        }
                                        put("outbound", "direct")
                                    }
                                }

                                // Only the curated blocked list is worth proxy
                                // bandwidth — final below drops to "direct".
                                RouteMode.PROXY_BLOCKED -> {
                                    addJsonObject {
                                        putJsonArray("rule_set") {
                                            routeMode.ruleSetTags.forEach { add(it) }
                                        }
                                        put("outbound", SELECTOR_TAG)
                                    }
                                }

                                RouteMode.ALL -> {}
                            }
                        }
                        put("final", if (routeMode == RouteMode.PROXY_BLOCKED) "direct" else SELECTOR_TAG)
                        if (routeMode.ruleSetTags.isNotEmpty()) {
                            putJsonArray("rule_set") {
                                routeMode.ruleSetTags.forEach { tag ->
                                    addJsonObject {
                                        // Local files fetched app-side (RuleSetStore)
                                        // — a remote fetch inside engine start would
                                        // fail the whole connect on bad networks.
                                        put("type", "local")
                                        put("tag", tag)
                                        put("format", "binary")
                                        put("path", ruleSetPaths.getValue(tag))
                                    }
                                }
                            }
                        }
                        putJsonObject("default_domain_resolver") {
                            // Must stay "local": "remote" detours through the proxy,
                            // and the proxy's server names are resolved by this
                            // resolver — pointing it at "remote" would deadlock
                            // bootstrap (remote → proxy → resolve via remote → …).
                            put("server", "local")
                        }
                    }
                }

            return EngineConfig(
                configJson = config.toString(),
                node = selected?.summary() ?: if (selectedNodeId == NodeSelection.AUTO_RUSSIA_ID) {
                    AUTO_RUSSIA_NODE_SUMMARY
                } else {
                    AUTO_NODE_SUMMARY
                },
                routeMode = routeMode,
                bypassLan = bypassLan,
                dnsProfile = dnsProfile,
            )
        }

        private fun runtimeOutbound(node: ProxyNode): JsonObject {
            val outbound = json.parseToJsonElement(node.outboundJson) as JsonObject
            // localhost must never be sent to network DNS under the sidecar exception.
            val server = outbound["server"] as? JsonPrimitive
            return if (server?.isString == true && server.content.equals("localhost", true)) {
                JsonObject(outbound + ("server" to JsonPrimitive("127.0.0.1")))
            } else outbound
        }

        companion object {
            const val SELECTOR_TAG = "proxy"
            const val AUTO_TAG = "auto"
            const val AUTO_RUSSIA_TAG = "auto-ru"

            /** TUN addresses — referenced by LanBypassRoutes keep-routes and
             *  the service's more-specific carve-outs. */
            const val TUN_V4 = "172.18.0.1/30"
            const val TUN_V6 = "fdfe:dcba:9877::1/126"

            /** Session-label summary for Auto — stands for the urltest group
             *  itself, never for one of its members. */
            val AUTO_NODE_SUMMARY =
                NodeSummary(
                    id = AUTO_TAG,
                    name = "Auto",
                    protocol = ProtocolType.OTHER,
                    server = "",
                )

            val AUTO_RUSSIA_NODE_SUMMARY =
                NodeSummary(
                    id = AUTO_RUSSIA_TAG,
                    name = "Auto Russia",
                    protocol = ProtocolType.OTHER,
                    server = "",
                )
        }
    }
