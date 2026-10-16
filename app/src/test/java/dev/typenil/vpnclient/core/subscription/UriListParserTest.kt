package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UriListParserTest {

    private val parser = UriListParser()
    private val json = Json { ignoreUnknownKeys = true }

    private fun outbound(node: ProxyNode): JsonObject =
        json.parseToJsonElement(node.outboundJson).jsonObject

    private fun b64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `vless reality node builds reality tls block`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=abc123pubkey&sid=01ab" +
            "&type=tcp&flow=xtls-rprx-vision#Reality%20Node"
        val nodes = parser.parse(uri, 7).nodes
        assertEquals(1, nodes.size)
        val n = nodes[0]
        assertEquals(ProtocolType.VLESS, n.protocol)
        assertEquals("example.com", n.server)
        assertEquals(443, n.port)
        assertEquals("Reality Node", n.name)
        assertEquals(uri, n.rawUri)

        val o = outbound(n)
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        assertEquals(n.id, o["tag"]!!.jsonPrimitive.content)
        assertEquals("11111111-2222-3333-4444-555555555555", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", o["flow"]!!.jsonPrimitive.content)
        assertEquals("local", o["domain_resolver"]!!.jsonPrimitive.content)
        assertNull(o["transport"])
        val tls = o["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("www.example.org", tls["server_name"]!!.jsonPrimitive.content)
        val reality = tls["reality"]!!.jsonObject
        assertEquals("abc123pubkey", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("01ab", reality["short_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless ws tls node builds ws transport`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@ws.example.com:8443" +
            "?security=tls&sni=ws.example.com&type=ws&host=cdn.example.com" +
            "&path=%2Fapi%2Fv1&alpn=h2,http/1.1#wsnode"
        val n = parser.parse(uri, 1).nodes.single()
        val o = outbound(n)
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/api/v1", transport["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertEquals("ws.example.com", tls["server_name"]!!.jsonPrimitive.content)
        val alpn = tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("h2", "http/1.1"), alpn)
        assertNull(tls["reality"])
    }

    @Test
    fun `vless xhttp transport is skipped with a reason`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@x.example.com:443" +
            "?security=reality&pbk=k&type=xhttp&mode=auto#xhttp"
        try {
            parser.parse(uri, 1).nodes
            fail("xhttp-only list should be empty")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `skipped nodes report reasons`() {
        val ok = "ss://aes-256-gcm:pw123@ss.example.com:8388#OK"
        val xhttp = "vless://11111111-2222-3333-4444-555555555555@x.example.com:443" +
            "?type=xhttp#X"
        val unknown = "hysteria://pw@h1.example.com:443"
        val junk = "not-a-uri-at-all"
        val result = parser.parse("$ok\n$xhttp\n$unknown\n$junk", 1)
        assertEquals(1, result.nodes.size)
        // Junk without a scheme separator is ignored entirely — not a skip.
        assertEquals(2, result.skipped.size)
        assertEquals("unsupported transport: xhttp", result.skipped[0].reason)
        assertEquals("X", result.skipped[0].name)
        assertEquals("unsupported protocol: hysteria", result.skipped[1].reason)
    }

    @Test
    fun `vless quic transport emits quic block`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@q.example.com:443" +
            "?security=tls&sni=q.example.com&type=quic#Q"
        val n = parser.parse(uri, 1).nodes.single()
        val transport = outbound(n)["transport"]!!.jsonObject
        assertEquals("quic", transport["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless kcp transport is skipped`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@k.example.com:443" +
            "?type=kcp#K"
        val ok = "ss://aes-256-gcm:pw123@ss.example.com:8388"
        val result = parser.parse("$uri\n$ok", 1)
        assertEquals(1, result.nodes.size)
        assertEquals("unsupported transport: kcp", result.skipped.single().reason)
    }

    @Test
    fun `hysteria2 mport emits server_ports ranges`() {
        val uri = "hy2://pw@hy.example.com:443?mport=5000-5010,6000&sni=hy.example.com"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(5000, n.port)
        val o = outbound(n)
        assertEquals(5000, o["server_port"]!!.jsonPrimitive.int)
        val ports = o["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("5000:5010", "6000"), ports)
    }

    @Test
    fun `hysteria2 range-valued authority port parses via mport`() {
        val uri = "hy2://pw@hy.example.com:20000-30000?mport=20000-30000&sni=hy.example.com"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(20000, n.port)
        val ports = outbound(n)["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("20000:30000"), ports)
    }

    @Test
    fun `hysteria2 fragment cannot inject mport`() {
        // `#name?mport=...` is the display fragment, not a query param —
        // the node must keep its real port and no server_ports.
        val uri = "hy2://pw@hy.example.com:443?sni=hy.example.com#note?mport=1-2"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(443, n.port)
        val o = outbound(n)
        assertEquals(443, o["server_port"]!!.jsonPrimitive.int)
        assertNull(o["server_ports"])
    }

    @Test
    fun `vmess base64 node parses fields and ws transport`() {
        val payload = b64(
            """{"v":"2","ps":"Vmess Node","add":"vm.example.com","port":"8443",""" +
                """"id":"22222222-3333-4444-5555-666666666666","aid":"4","scy":"auto",""" +
                """"net":"ws","type":"none","host":"cdn.example.com","path":"/ws","tls":"tls","sni":"vm.example.com"}""",
        )
        val n = parser.parse("vmess://$payload", 3).nodes.single()
        assertEquals(ProtocolType.VMESS, n.protocol)
        assertEquals("vm.example.com", n.server)
        assertEquals(8443, n.port)
        assertEquals("Vmess Node", n.name)

        val o = outbound(n)
        assertEquals("vmess", o["type"]!!.jsonPrimitive.content)
        assertEquals("22222222-3333-4444-5555-666666666666", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("auto", o["security"]!!.jsonPrimitive.content)
        assertEquals(4, o["alter_id"]!!.jsonPrimitive.int)
        assertTrue(o["authenticated_length"]!!.jsonPrimitive.boolean)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", transport["path"]!!.jsonPrimitive.content)
        assertEquals("vm.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan node always gets tls and ws transport`() {
        val uri = "trojan://t0psecret@tr.example.com:443?sni=tr.example.com" +
            "&type=ws&host=h.example.com&path=%2Ftj#Trojan"
        val n = parser.parse(uri, 2).nodes.single()
        assertEquals(ProtocolType.TROJAN, n.protocol)
        assertEquals("Trojan", n.name)
        val o = outbound(n)
        assertEquals("trojan", o["type"]!!.jsonPrimitive.content)
        assertEquals("t0psecret", o["password"]!!.jsonPrimitive.content)
        assertEquals("tr.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        val transport = o["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/tj", transport["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsupported shadowsocks plugin is reported instead of silently ignored`() {
        val valid = "ss://aes-256-gcm:pw123@ss.example.com:8388#Plain"
        val pluginNode = "ss://aes-256-gcm:pw123@ss.example.com:8388?plugin=unsupported#Plugin"
        val result = parser.parse("$valid\n$pluginNode", 1)
        assertEquals(1, result.nodes.size)
        assertEquals("Plain", result.nodes.single().name)
        assertEquals(1, result.skipped.size)
        assertEquals("Plugin", result.skipped.single().name)
        assertEquals("unsupported Shadowsocks plugin: unsupported", result.skipped.single().reason)
    }

    @Test
    fun `ss userinfo base64 form`() {
        val uri = "ss://${b64("aes-256-gcm:pw123")}@ss.example.com:8388#SS-A"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.SHADOWSOCKS, n.protocol)
        assertEquals("ss.example.com", n.server)
        assertEquals(8388, n.port)
        assertEquals("SS-A", n.name)
        val o = outbound(n)
        assertEquals("shadowsocks", o["type"]!!.jsonPrimitive.content)
        assertEquals("aes-256-gcm", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw123", o["password"]!!.jsonPrimitive.content)
        assertEquals(null, o["network"])
    }

    @Test
    fun `ss plaintext and per-part base64 userinfo forms`() {
        val plain = "ss://aes-256-gcm:pw123@ss.example.com:8388"
        val perPart = "ss://${b64("aes-256-gcm")}:${b64("pw123")}@ss.example.com:8388"
        val nodes = parser.parse("$plain\n$perPart", 1).nodes
        assertEquals(2, nodes.size)
        nodes.forEach { n ->
            val o = outbound(n)
            assertEquals("aes-256-gcm", o["method"]!!.jsonPrimitive.content)
            assertEquals("pw123", o["password"]!!.jsonPrimitive.content)
            assertEquals("ss.example.com", o["server"]!!.jsonPrimitive.content)
            assertEquals(8388, o["server_port"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `ss sip002 whole-thing base64 form`() {
        val uri = "ss://${b64("chacha20-ietf-poly1305:pw456@ss2.example.com:8389")}#SS-C"
        val n = parser.parse(uri, 1).nodes.single()
        val o = outbound(n)
        assertEquals("chacha20-ietf-poly1305", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw456", o["password"]!!.jsonPrimitive.content)
        assertEquals("ss2.example.com", o["server"]!!.jsonPrimitive.content)
        assertEquals(8389, o["server_port"]!!.jsonPrimitive.int)
        assertEquals("SS-C", n.name)
    }

    @Test
    fun `hysteria2 node with obfs`() {
        val uri = "hysteria2://hy2pass@hy.example.com:443?sni=hy.example.com" +
            "&insecure=1&obfs=salamander&obfs-password=obfspw#Hy2"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.HYSTERIA2, n.protocol)
        val o = outbound(n)
        assertEquals("hysteria2", o["type"]!!.jsonPrimitive.content)
        assertEquals("hy2pass", o["password"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("hy.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertTrue(tls["insecure"]!!.jsonPrimitive.boolean)
        val obfs = o["obfs"]!!.jsonObject
        assertEquals("salamander", obfs["type"]!!.jsonPrimitive.content)
        assertEquals("obfspw", obfs["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hy2 scheme alias parses`() {
        val uri = "hy2://pw@hy.example.com:1443?sni=hy.example.com"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.HYSTERIA2, n.protocol)
        assertEquals(1443, n.port)
    }

    @Test
    fun `tuic node with congestion control and alpn`() {
        val uri = "tuic://33333333-4444-5555-6666-777777777777:tuicpw@tuic.example.com:443" +
            "?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=t.example.com#Tuic"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.TUIC, n.protocol)
        val o = outbound(n)
        assertEquals("tuic", o["type"]!!.jsonPrimitive.content)
        assertEquals("33333333-4444-5555-6666-777777777777", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuicpw", o["password"]!!.jsonPrimitive.content)
        assertEquals("bbr", o["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("native", o["udp_relay_mode"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertEquals("t.example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("h3", tls["alpn"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `unknown and unsupported schemes are skipped`() {
        val body = """
            |hysteria://pw@h1.example.com:443
            |socks5://u:p@s.example.com:1080
            |wireguard://wg.example.com:51820
            |ss://aes-256-gcm:pw123@ss.example.com:8388
            |not-a-uri-at-all
        """.trimMargin()
        val nodes = parser.parse(body, 1).nodes
        // hysteria1 and the non-URI line are skipped; the wireguard link has
        // no key material so it drops too — socks5 and ss survive.
        assertEquals(2, nodes.size)
        assertEquals(ProtocolType.SOCKS, nodes[0].protocol)
        assertEquals(ProtocolType.SHADOWSOCKS, nodes[1].protocol)
    }

    @Test
    fun `comment lines are skipped but fragment-only hash inside uri is not`() {
        val body = "# comment\n// another comment\nss://aes-256-gcm:pw123@ss.example.com:8388#Hashtag"
        val nodes = parser.parse(body, 1).nodes
        assertEquals(1, nodes.size)
        assertEquals("Hashtag", nodes[0].name)
    }

    @Test
    fun `malformed line does not fail sibling lines`() {
        val body = "vless://not-a-uuid\nss://aes-256-gcm:pw123@ss.example.com:8388#OK"
        val nodes = parser.parse(body, 1).nodes
        assertEquals(1, nodes.size)
        assertEquals("OK", nodes[0].name)
    }

    @Test
    fun `all bad input throws EmptyResult`() {
        try {
            parser.parse("garbage\nhysteria://x@y:1\nss://broken", 1).nodes
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `node ids are deterministic and scoped to subscription`() {
        val uri = "trojan://pw@tr.example.com:443#N"
        val first = parser.parse(uri, 5).nodes.single()
        val second = parser.parse(uri, 5).nodes.single()
        val otherSub = parser.parse(uri, 6).nodes.single()
        assertEquals(first.id, second.id)
        assertNotEquals(first.id, otherSub.id)
        assertTrue(first.id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `vless links sharing uuid host port but different network get distinct ids`() {
        // Regression: identity used to hash only scheme|server:port|credential,
        // so transport/TLS differences collided and distinctBy dropped a node.
        val uuid = "11111111-2222-3333-4444-555555555555"
        val tcp = "vless://$uuid@shared.example.com:443?security=tls&sni=a.example.com#TCP"
        val ws = "vless://$uuid@shared.example.com:443?security=tls&sni=a.example.com" +
            "&type=ws&host=cdn.example.com&path=%2Fws#WS"
        val nodes = parser.parse("$tcp\n$ws", 1).nodes
        assertEquals(2, nodes.size)
        assertNotEquals(nodes[0].id, nodes[1].id)
        // Both survive the repository's distinctBy { it.id } dedupe.
        assertEquals(2, nodes.distinctBy { it.id }.size)
    }

    @Test
    fun `links differing only in display name share an id`() {
        val a = "trojan://pw@tr.example.com:443#Alpha"
        val b = "trojan://pw@tr.example.com:443#Beta"
        val nodes = parser.parse("$a\n$b", 1).nodes
        assertEquals(2, nodes.size)
        assertEquals(nodes[0].id, nodes[1].id)
        assertEquals(1, nodes.distinctBy { it.id }.size)
    }

    @Test
    fun `fragment name is percent decoded including unicode`() {
        val uri = "ss://aes-256-gcm:pw123@ss.example.com:8388#" +
            "%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0%201"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals("Москва 1", n.name)
    }

    @Test
    fun `missing fragment falls back to server-port name`() {
        val n = parser.parse("ss://aes-256-gcm:pw123@ss.example.com:8388", 1).nodes.single()
        assertEquals("ss.example.com:8388", n.name)
    }

    @Test
    fun `crlf line endings parse`() {
        val body = "ss://aes-256-gcm:pw@a.example.com:1\r\nss://aes-256-gcm:pw@b.example.com:2\r\n"
        assertEquals(2, parser.parse(body, 1).nodes.size)
    }

    @Test
    fun `anytls link builds anytls outbound with tls`() {
        val uri = "anytls://secretpass@at.example.com:443?sni=at.example.com&insecure=0#AnyTLS"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.ANYTLS, n.protocol)
        assertEquals("at.example.com", n.server)
        assertEquals(443, n.port)
        assertEquals("AnyTLS", n.name)

        val o = outbound(n)
        assertEquals("anytls", o["type"]!!.jsonPrimitive.content)
        assertEquals("secretpass", o["password"]!!.jsonPrimitive.content)
        val tls = o["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("at.example.com", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wireguard link builds endpoint-shaped config`() {
        val uri = "wireguard://cPrivKey@wg.example.com:51820" +
            "?publickey=peerPub&address=10.0.0.2/32,fd00::2/128" +
            "&presharedkey=psk123&reserved=1,2,3&mtu=1280#WG"
        val n = parser.parse(uri, 1).nodes.single()
        assertEquals(ProtocolType.WIREGUARD, n.protocol)

        // sing-box >=1.13 shape: endpoints[] entry — address/peers, not
        // the removed outbound's local_address/peer_public_key.
        val o = outbound(n)
        assertEquals("wireguard", o["type"]!!.jsonPrimitive.content)
        assertEquals("cPrivKey", o["private_key"]!!.jsonPrimitive.content)
        assertEquals(1280, o["mtu"]!!.jsonPrimitive.int)
        assertEquals("local", o["domain_resolver"]!!.jsonPrimitive.content)
        val addrs = o["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), addrs)
        val peer = o["peers"]!!.jsonArray.single().jsonObject
        assertEquals("wg.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals(51820, peer["port"]!!.jsonPrimitive.int)
        assertEquals("peerPub", peer["public_key"]!!.jsonPrimitive.content)
        assertEquals("psk123", peer["pre_shared_key"]!!.jsonPrimitive.content)
        val reserved = peer["reserved"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(1, 2, 3), reserved)
        val allowed = peer["allowed_ips"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("0.0.0.0/0", "::/0"), allowed)
    }

    @Test
    fun `wireguard missing publickey or address is skipped`() {
        val noKey = "wireguard://cPrivKey@wg.example.com:51820?address=10.0.0.2/32"
        val noAddr = "wireguard://cPrivKey@wg.example.com:51820?publickey=peerPub"
        val ok = "wireguard://cPrivKey@wg.example.com:51820" +
            "?publickey=peerPub&address=10.0.0.2/32"
        val nodes = parser.parse("$noKey\n$noAddr\n$ok", 1).nodes
        assertEquals(1, nodes.size)
        assertEquals("wg.example.com", nodes[0].server)
    }

    @Test
    fun `socks link with auth builds socks5 outbound`() {
        val n = parser.parse("socks://user:p%40ss@socks.example.com:1080#S", 1).nodes.single()
        assertEquals(ProtocolType.SOCKS, n.protocol)
        val o = outbound(n)
        assertEquals("socks", o["type"]!!.jsonPrimitive.content)
        assertEquals("5", o["version"]!!.jsonPrimitive.content)
        assertEquals("user", o["username"]!!.jsonPrimitive.content)
        assertEquals("p@ss", o["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `socks userinfo splits on literal colon before decoding`() {
        // %3A inside the username is data, not the user:pass separator.
        val n = parser.parse("socks5://a%3Ab:p%40ss@proxy.example.com:1080", 1).nodes.single()
        val o = outbound(n)
        assertEquals("a:b", o["username"]!!.jsonPrimitive.content)
        assertEquals("p@ss", o["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `socks link without auth omits credentials`() {
        val n = parser.parse("socks://socks.example.com:1080", 1).nodes.single()
        val o = outbound(n)
        assertEquals("socks", o["type"]!!.jsonPrimitive.content)
        assertNull(o["username"])
        assertNull(o["password"])
    }

    @Test
    fun `ech param lands in tls ech config`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@e.example.com:443" +
            "?security=tls&sni=e.example.com&ech=AAAA%2Bconfig#E"
        val n = parser.parse(uri, 1).nodes.single()
        val tls = outbound(n)["tls"]!!.jsonObject
        val ech = tls["ech"]!!.jsonObject
        assertTrue(ech["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("AAAA+config", ech["config"]!!.jsonPrimitive.content)
    }
}
