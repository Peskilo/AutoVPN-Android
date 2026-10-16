package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ParseResult
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.parse.ShareLink
import dev.typenil.vpnclient.core.subscription.parse.anytlsOutbound
import dev.typenil.vpnclient.core.subscription.parse.base64Decode
import dev.typenil.vpnclient.core.subscription.parse.commaList
import dev.typenil.vpnclient.core.subscription.parse.hysteria2Outbound
import dev.typenil.vpnclient.core.subscription.parse.NetworkClass
import dev.typenil.vpnclient.core.subscription.parse.classifyNetwork
import dev.typenil.vpnclient.core.subscription.parse.param
import dev.typenil.vpnclient.core.subscription.parse.percentDecode
import dev.typenil.vpnclient.core.subscription.parse.portHoppingList
import dev.typenil.vpnclient.core.subscription.parse.shadowsocksOutbound
import dev.typenil.vpnclient.core.subscription.parse.stableNodeId
import dev.typenil.vpnclient.core.subscription.parse.tlsBlock
import dev.typenil.vpnclient.core.subscription.parse.tlsServerName
import dev.typenil.vpnclient.core.subscription.parse.transportBlock
import dev.typenil.vpnclient.core.subscription.parse.trojanOutbound
import dev.typenil.vpnclient.core.subscription.parse.truthyParam
import dev.typenil.vpnclient.core.subscription.parse.tuicOutbound
import dev.typenil.vpnclient.core.subscription.parse.vlessOutbound
import dev.typenil.vpnclient.core.subscription.parse.vmessOutbound
import dev.typenil.vpnclient.core.subscription.parse.socksOutbound
import dev.typenil.vpnclient.core.subscription.parse.wireguardEndpoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses newline-separated share links (vless/vmess/trojan/ss/hysteria2/tuic)
 * into nodes carrying sing-box outbound JSON.
 *
 * Malformed lines and unsupported schemes/transports are skipped
 * individually and reported in [ParseResult.skipped];
 * [SubscriptionError.EmptyResult] is thrown only when nothing usable
 * remains.
 */
@Singleton
class UriListParser @Inject constructor() : SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(body: String, subscriptionId: Long): ParseResult {
        val skipped = mutableListOf<SkippedNode>()
        val nodes = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("//") || it.startsWith("#") }
            // Lines without a scheme separator are junk, not nodes — never
            // counted as skips.
            .filter { "://" in it }
            .mapNotNull { line ->
                try {
                    parseLine(line, subscriptionId)
                } catch (e: SkipNode) {
                    skipped += SkippedNode(e.nodeName ?: fragmentName(line), e.message!!)
                    null
                } catch (e: Exception) {
                    skipped += SkippedNode(fragmentName(line), "malformed")
                    null
                }
            }
            .toList()
        if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()
        return ParseResult(nodes, skipped)
    }

    /** `#fragment` is the node's display name — best-effort for diagnostics. */
    private fun fragmentName(line: String): String? =
        line.substringAfter('#', "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { percentDecode(it) }.getOrNull() ?: it }

    private fun parseLine(line: String, subscriptionId: Long): ProxyNode =
        when (line.substringBefore("://", "").lowercase()) {
            "vless" -> parseVless(line, subscriptionId)
            "vmess" -> parseVmess(line, subscriptionId)
            "trojan" -> parseTrojan(line, subscriptionId)
            "ss" -> parseShadowsocks(line, subscriptionId)
            "hysteria2", "hy2" -> parseHysteria2(line, subscriptionId)
            "tuic" -> parseTuic(line, subscriptionId)
            "anytls" -> parseAnytls(line, subscriptionId)
            "wireguard", "wg" -> parseWireguard(line, subscriptionId)
            "socks", "socks5" -> parseSocks(line, subscriptionId)
            // hysteria1, http(s), unknown schemes — unsupported
            else -> throw SkipNode(null, "unsupported protocol: ${line.substringBefore("://").lowercase()}")
        }

    // vless://<uuid>@<server>:<port>?<params>#<name>
    private fun parseVless(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        val uuid = link.userinfo?.let(::percentDecode)
            ?: throw SkipNode(link.fragment, "malformed")
        val p = link.params
        val network = p.param("type", "network") ?: "tcp"
        if (classifyNetwork(network) == NetworkClass.Unsupported) {
            throw SkipNode(link.fragment, "unsupported transport: $network")
        }
        val security = p["security"]?.lowercase()
        val tls = when (security) {
            "tls", "reality" -> tlsBlock(
                serverName = tlsServerName(p["sni"], p["host"], network),
                insecure = truthyParam(p.param("allowInsecure", "allow_insecure", "insecure")),
                alpn = commaList(p["alpn"]),
                fingerprint = p["fp"],
                realityPublicKey = if (security == "reality") p["pbk"] else null,
                realityShortId = p["sid"],
                ech = p["ech"],
            )
            else -> null
        }
        val transport = transportBlock(
            network = network,
            host = p["host"],
            path = p["path"],
            serviceName = p.param("serviceName", "service_name"),
            maxEarlyData = p.param("ed", "maxEarlyData", "max_early_data")?.toIntOrNull(),
            earlyDataHeaderName = p.param("eh", "earlyDataHeaderName", "early_data_header_name"),
        )
        return node(subscriptionId, ProtocolType.VLESS, link, line) { tag ->
            vlessOutbound(
                tag, link.host, link.port, uuid,
                flow = p["flow"],
                tls = tls,
                transport = transport,
                packetEncoding = p.param("packetEncoding", "packet_encoding"),
            )
        }
    }

    // vmess://<base64(json)>
    private fun parseVmess(line: String, subscriptionId: Long): ProxyNode {
        val payload = line.substringAfter("://").substringBefore('#').substringBefore('?')
        val decoded = base64Decode(payload)
            ?: throw SkipNode(null, "malformed")
        val obj = json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
        val server = obj.str("add") ?: throw SkipNode(obj.str("ps"), "malformed")
        val port = obj.intField("port") ?: throw SkipNode(obj.str("ps"), "malformed")
        val uuid = obj.str("id") ?: throw SkipNode(obj.str("ps"), "malformed")
        val network = obj.str("net") ?: "tcp"
        if (classifyNetwork(network) == NetworkClass.Unsupported) {
            throw SkipNode(obj.str("ps"), "unsupported transport: $network")
        }

        val path = obj.str("path")
        val serviceName = obj.str("serviceName") ?: when {
            network.equals("grpc", ignoreCase = true) && path != null ->
                if (path.startsWith("serviceName=")) path.substringAfter("serviceName=").substringBefore('&') else path
            else -> null
        }
        val transport = transportBlock(network, host = obj.str("host"), path = path, serviceName = serviceName)
        val security = obj.str("tls")?.lowercase()
        val tls = when (security) {
            "tls", "reality" -> tlsBlock(
                serverName = tlsServerName(obj.str("sni"), obj.str("host"), network),
                insecure = truthyParam(obj.str("allowInsecure") ?: obj.str("allow_insecure")),
                alpn = commaList(obj.str("alpn")),
                fingerprint = obj.str("fp"),
                realityPublicKey = if (security == "reality") obj.str("pbk") else null,
                realityShortId = obj.str("sid"),
                ech = obj.str("ech"),
            )
            else -> null
        }
        return node(
            subscriptionId, ProtocolType.VMESS,
            server, port, obj.str("ps"), line,
        ) { tag ->
            vmessOutbound(
                tag, server, port, uuid,
                security = obj.str("scy") ?: obj.str("security"),
                alterId = obj.intField("aid") ?: 0,
                tls = tls,
                transport = transport,
                packetEncoding = obj.str("packetEncoding") ?: obj.str("packet_encoding"),
            )
        }
    }

    // trojan://<password>@<server>:<port>?<params>#<name>
    private fun parseTrojan(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        val password = link.userinfo?.let(::percentDecode)
            ?: throw SkipNode(link.fragment, "malformed")
        val p = link.params
        val network = p.param("type", "network") ?: "tcp"
        if (classifyNetwork(network) == NetworkClass.Unsupported) {
            throw SkipNode(link.fragment, "unsupported transport: $network")
        }
        // Trojan is TLS-only — always emit the tls block (incl. REALITY params).
        val tls = tlsBlock(
            serverName = p["sni"] ?: link.host,
            insecure = truthyParam(p.param("allowInsecure", "allow_insecure", "insecure")),
            alpn = commaList(p["alpn"]),
            fingerprint = p["fp"],
            realityPublicKey = if (p["security"]?.lowercase() == "reality") p["pbk"] else null,
            realityShortId = p["sid"],
            ech = p["ech"],
        )
        val transport = transportBlock(
            network = network,
            host = p["host"],
            path = p["path"],
            serviceName = p.param("serviceName", "service_name"),
        )
        return node(subscriptionId, ProtocolType.TROJAN, link, line) { tag ->
            trojanOutbound(tag, link.host, link.port, password, tls = tls, transport = transport)
        }
    }

    // ss://<base64(method:password)>@<server>:<port>
    // ss://<method>:<password>@<server>:<port>   (parts may be individually base64'd)
    // ss://<base64(method:password@server:port)>
    private fun parseShadowsocks(line: String, subscriptionId: Long): ProxyNode {
        var rest = line.substringAfter("://")
        var fragment: String? = null
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = percentDecode(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }
        var plugin: String? = null
        var pluginOpts: String? = null
        val q = rest.indexOf('?')
        if (q >= 0) {
            val query = ShareLink.parseQuery(rest.substring(q + 1))
            rest = rest.substring(0, q)
            query["plugin"]?.let { raw ->
                val name = raw.substringBefore(';').trim()
                if (name != "obfs-local" && name != "v2ray-plugin") {
                    // Never silently turn a plugin-based Shadowsocks node into
                    // plain Shadowsocks: it will parse successfully but cannot connect.
                    throw SkipNode(fragment, "unsupported Shadowsocks plugin: $name")
                }
                plugin = name
                pluginOpts = raw.substringAfter(';', "").takeIf { it.isNotBlank() }
            }
        }
        var authority = rest
        if ('@' !in authority) {
            authority = base64Decode(authority)?.toString(Charsets.UTF_8)
                ?: throw SkipNode(fragment, "malformed")
        }
        val at = authority.lastIndexOf('@')
        if (at < 0) throw SkipNode(fragment, "malformed")
        val (method, password) = parseSsUserInfo(authority.substring(0, at))
            ?: throw SkipNode(fragment, "malformed")
        val (server, port) = ShareLink.splitHostPort(authority.substring(at + 1))
        return node(
            subscriptionId, ProtocolType.SHADOWSOCKS,
            server, port, fragment, line,
        ) { tag ->
            shadowsocksOutbound(tag, server, port, method, password, plugin = plugin, pluginOpts = pluginOpts)
        }
    }

    private fun parseSsUserInfo(raw: String): Pair<String, String>? {
        // Whole-userinfo base64: base64(method:password)
        base64Decode(raw)?.toString(Charsets.UTF_8)?.let { decoded ->
            val i = decoded.indexOf(':')
            if (i > 0) return decoded.substring(0, i) to decoded.substring(i + 1)
        }
        val i = raw.indexOf(':')
        if (i < 0) return null
        var method = raw.substring(0, i)
        var password = percentDecode(raw.substring(i + 1))
        // Per-part base64: base64(method):base64(password)
        if (method !in SS_METHODS) {
            val decodedMethod = base64Decode(method)?.toString(Charsets.UTF_8)
            if (decodedMethod != null && decodedMethod in SS_METHODS) {
                method = decodedMethod
                password = base64Decode(raw.substring(i + 1))?.toString(Charsets.UTF_8) ?: password
            }
        }
        return method to password
    }

    // hysteria2://<password>@<server>:<port>?<params>#<name>  (also hy2://)
    private fun parseHysteria2(line: String, subscriptionId: Long): ProxyNode {
        // mport carries the port-hopping list; the authority port itself may
        // be a range (host:20000-30000) which ShareLink.splitHostPort
        // rejects — retry with the authority port replaced by mport's first
        // entry. A present-but-invalid mport is a skip, never a silent
        // fallback to a guessed port.
        // Search only the pre-fragment part: a `#name?mport=...` fragment is
        // the display name, not a query param — matching it would let a
        // fragment inject port-hopping into a node that never declared it.
        val mport = MPORT_REGEX.find(line.substringBefore('#'))?.groupValues?.get(1)
            ?.let { runCatching { percentDecode(it) }.getOrDefault(it) }
        val hopList = mport?.let { raw ->
            val entries = raw.split(',')
            val parsed = portHoppingList(raw)
            if (parsed.size != entries.size) {
                throw SkipNode(fragmentName(line), "malformed")
            }
            parsed
        }
        val link = try {
            ShareLink.parse(line)
        } catch (e: Exception) {
            if (mport == null) throw e
            val firstPort = hopList?.first()?.substringBefore(':')
                ?: throw SkipNode(fragmentName(line), "malformed")
            ShareLink.parse(replaceAuthorityPort(line, firstPort))
        }
        val password = link.userinfo?.let(::percentDecode)
            ?: throw SkipNode(link.fragment, "malformed")
        val p = link.params
        // hopList was already validated — a present-but-invalid mport
        // threw above rather than silently degrading to the authority port.
        val serverPorts = hopList?.takeIf { it.isNotEmpty() }
        // server_port is schema-required but ignored once server_ports is
        // set — keep it at the first hop port.
        val port = serverPorts?.first()?.substringBefore(':')?.toIntOrNull() ?: link.port
        val tls = tlsBlock(
            serverName = p["sni"] ?: link.host,
            insecure = truthyParam(p["insecure"]),
            ech = p["ech"],
        )
        val obfsPassword = if (p["obfs"] != null) p["obfs-password"].orEmpty() else null
        return node(
            subscriptionId, ProtocolType.HYSTERIA2,
            link.host, port, link.fragment, line,
        ) { tag ->
            hysteria2Outbound(
                tag, link.host, port, password,
                tls = tls, obfsPassword = obfsPassword, serverPorts = serverPorts,
            )
        }
    }

    /** Replace the port inside `scheme://authority` — used when the
     *  authority carries a port range splitHostPort can't parse. */
    private fun replaceAuthorityPort(line: String, port: String): String {
        val schemeEnd = line.indexOf("://") + 3
        val authorityEnd = line.indexOfAny(charArrayOf('?', '#', '/'), schemeEnd)
            .takeIf { it >= 0 } ?: line.length
        val authority = line.substring(schemeEnd, authorityEnd)
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return line
        return line.substring(0, schemeEnd + colon + 1) + port + line.substring(authorityEnd)
    }

    // tuic://<uuid>:<password>@<server>:<port>?<params>#<name>
    private fun parseTuic(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        val userinfo = link.userinfo ?: throw SkipNode(link.fragment, "malformed")
        val sep = userinfo.indexOf(':')
        if (sep < 0) throw SkipNode(link.fragment, "malformed")
        val uuid = percentDecode(userinfo.substring(0, sep))
        val password = percentDecode(userinfo.substring(sep + 1))
        val p = link.params
        val tls = tlsBlock(
            serverName = if (truthyParam(p["disable_sni"])) null else p["sni"] ?: link.host,
            insecure = truthyParam(p.param("allow_insecure", "allowInsecure", "insecure")),
            alpn = commaList(p["alpn"]) ?: listOf("h3"),
            ech = p["ech"],
        )
        return node(subscriptionId, ProtocolType.TUIC, link, line) { tag ->
            tuicOutbound(
                tag, link.host, link.port, uuid, password,
                congestionControl = p.param("congestion_control", "congestion-controller"),
                udpRelayMode = p["udp_relay_mode"],
                tls = tls,
            )
        }
    }

    // anytls://<password>@<server>:<port>?<params>#<name> — TLS is mandatory.
    private fun parseAnytls(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        val password = link.userinfo?.let(::percentDecode)?.takeIf { it.isNotEmpty() }
            ?: throw SkipNode(link.fragment, "malformed")
        val p = link.params
        val tls = tlsBlock(
            serverName = p["sni"] ?: link.host,
            insecure = truthyParam(p.param("allowInsecure", "allow_insecure", "insecure")),
            alpn = commaList(p["alpn"]),
            fingerprint = p["fp"],
            ech = p["ech"],
        )
        return node(subscriptionId, ProtocolType.ANYTLS, link, line) { tag ->
            anytlsOutbound(tag, link.host, link.port, password, tls = tls)
        }
    }

    // wireguard://<private_key>@<server>:<port>?publickey=<pk>&address=<cidrs>&…#<name>
    private fun parseWireguard(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        val privateKey = link.userinfo?.let(::percentDecode)?.takeIf { it.isNotEmpty() }
            ?: throw SkipNode(link.fragment, "malformed")
        val p = link.params
        val peerPublicKey = p.param("publickey", "public_key", "peer_public_key")
            ?: throw SkipNode(link.fragment, "malformed")
        val localAddress = commaList(p.param("address", "local_address", "addresses"))
            ?: throw SkipNode(link.fragment, "malformed")
        val reserved = commaList(p["reserved"])?.mapNotNull(String::toIntOrNull)
        val mtu = p["mtu"]?.toIntOrNull()
        return node(subscriptionId, ProtocolType.WIREGUARD, link, line) { tag ->
            wireguardEndpoint(
                tag, link.host, link.port,
                privateKey = privateKey,
                peerPublicKey = peerPublicKey,
                localAddress = localAddress,
                preSharedKey = p.param("presharedkey", "pre_shared_key", "preshared_key"),
                reserved = reserved,
                mtu = mtu,
            )
        }
    }

    // socks://[user:pass@]<server>:<port>#<name>  (also socks5://)
    private fun parseSocks(line: String, subscriptionId: Long): ProxyNode {
        val link = ShareLink.parse(line)
        // Split on the first LITERAL colon before decoding — a %3A inside the
        // username is data, not the user:pass separator (RFC 3986 §2.4).
        val rawUserinfo = link.userinfo
        val (username, password) = when {
            rawUserinfo.isNullOrEmpty() -> null to null
            ':' in rawUserinfo -> percentDecode(rawUserinfo.substringBefore(':')) to
                percentDecode(rawUserinfo.substringAfter(':'))
            else -> percentDecode(rawUserinfo) to null
        }
        return node(subscriptionId, ProtocolType.SOCKS, link, line) { tag ->
            socksOutbound(tag, link.host, link.port, username = username, password = password)
        }
    }

    private fun node(
        subscriptionId: Long, protocol: ProtocolType,
        link: ShareLink, rawUri: String,
        outbound: (tag: String) -> JsonObject,
    ): ProxyNode = node(
        subscriptionId, protocol, link.host, link.port, link.fragment, rawUri, outbound,
    )

    private fun node(
        subscriptionId: Long, protocol: ProtocolType,
        server: String, port: Int, name: String?, rawUri: String,
        outbound: (tag: String) -> JsonObject,
    ): ProxyNode {
        // The tag is generated from the id, so identity must come from the
        // tagless outbound — build once with a placeholder, hash, then stamp.
        val template = outbound("")
        val id = stableNodeId(subscriptionId, template)
        return ProxyNode(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: "$server:$port",
            protocol = protocol,
            server = server,
            port = port,
            outboundJson = JsonObject(template + ("tag" to JsonPrimitive(id))).toString(),
            rawUri = rawUri,
            subscriptionId = subscriptionId,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private companion object {
        /** `mport` may sit anywhere in the query — pre-extract it from the
         *  raw line so a range-valued authority port can be retried. */
        val MPORT_REGEX = Regex("[?&]mport=([^&#]*)")

        val SS_METHODS = setOf(
            "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
            "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
            "aes-128-ofb", "aes-192-ofb", "aes-256-ofb",
            "chacha20", "chacha20-ietf", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "salsa20", "bf-cfb", "rc4-md5", "rc4-md5-6",
            "camellia-128-cfb", "camellia-192-cfb", "camellia-256-cfb",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "none", "plain",
        )
    }
}
