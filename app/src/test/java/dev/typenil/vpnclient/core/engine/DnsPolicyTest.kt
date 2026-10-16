package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    @Test
    fun `round-trip keys for presets and custom`() {
        DnsUpstream.presets.forEach { preset ->
            assertEquals(preset, DnsUpstream.fromKey(preset.key))
        }
        val custom = DnsUpstream.Custom("https://dns.example.com/dns-query")
        assertEquals(custom, DnsUpstream.fromKey(custom.key))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey(null))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey("bogus"))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey("custom: not a spec !!"))
    }

    @Test
    fun `parseCustom accepts supported schemes`() {
        val cases =
            mapOf(
                // input -> canonical stored spec
                "https://1.1.1.1/dns-query" to "https://1.1.1.1/dns-query",
                "tls://one.one.one.one" to "tls://one.one.one.one",
                "quic://dns.adguard-dns.com" to "quic://dns.adguard-dns.com",
                "udp://9.9.9.9" to "udp://9.9.9.9",
                // bare IP normalizes to udp:// — the spec must carry a scheme
                // so the compiler can pick the server type from it
                "1.1.1.1" to "udp://1.1.1.1",
            )
        cases.forEach { (input, canonical) ->
            assertEquals(canonical, DnsUpstream.parseCustom(input)?.spec)
        }
    }

    @Test
    fun `parseCustom rejects bad specs`() {
        listOf(
            "",
            "  ",
            "ftp://1.1.1.1",
            "https://",
            "https://a b.com/x",
            "plain text",
            "tcp://1.1.1.1",
            // a port suffix isn't an IP literal — udp://9.9.9.9:9953 is the
            // accepted spelled-out form, bare "9.9.9.9:9953" is rejected
            "9.9.9.9:9953",
        ).forEach { assertNull("must reject: $it", DnsUpstream.parseCustom(it)) }
    }

    @Test
    fun `hostname upstreams flag for bootstrap`() {
        assertTrue(DnsUpstream.parseCustom("tls://one.one.one.one")!!.hostname)
        assertTrue(DnsUpstream.parseCustom("https://dns.example.com/dns-query")!!.hostname)
        assertFalse(DnsUpstream.parseCustom("tls://1.1.1.1")!!.hostname)
        assertTrue(DnsUpstream.Cloudflare.hostname)
        assertTrue(DnsUpstream.Google.hostname)
        assertTrue(DnsUpstream.Quad9.hostname)
        assertFalse(DnsUpstream.parseCustom("udp://9.9.9.9")!!.hostname)
    }

    @Test
    fun `summary redacts the custom path but keeps the host`() {
        assertEquals(
            "policy:cloudflare",
            DnsProfile(DnsMode.POLICY, DnsUpstream.Cloudflare).summary,
        )
        assertEquals(
            "custom:https://x.com/…",
            DnsProfile(DnsMode.PROXY_ONLY, DnsUpstream.Custom("https://x.com/dns-query")).summary,
        )
    }

    @Test
    fun `dnsMode fromKey is lenient`() {
        assertEquals(DnsMode.POLICY, DnsMode.fromKey(null))
        assertEquals(DnsMode.POLICY, DnsMode.fromKey("bogus"))
        assertEquals(DnsMode.PROXY_ONLY, DnsMode.fromKey("proxy_only"))
    }

    @Test
    fun `ipv6 upstreams canonicalize to bracketed and survive parse`() {
        // Bare IPv6 → bracketed udp:// spec
        val bare = DnsUpstream.parseCustom("2001:db8::53")
        assertEquals("udp://[2001:db8::53]", bare?.spec)
        // Bracketed forms parse as literal, not hostname → no bootstrap
        assertFalse(DnsUpstream.parseCustom("https://[2001:db8::853]/dns-query")!!.hostname)
        assertFalse(DnsUpstream.parseCustom("tls://[2001:db8::853]")!!.hostname)
        // tls://[v6]:port round-trips with the port intact
        assertEquals(
            "tls://[2001:db8::853]:8853",
            DnsUpstream.parseCustom("tls://[2001:db8::853]:8853")?.spec,
        )
    }

    @Test
    fun `splitHostPort handles v4 v6 bracketed and hostname authorities`() {
        // (authority) -> (host, port)
        data class Case(
            val authority: String,
            val host: String,
            val port: String?,
        )
        listOf(
            Case("1.1.1.1", "1.1.1.1", null),
            Case("1.1.1.1:853", "1.1.1.1", "853"),
            Case("dns.example.com", "dns.example.com", null),
            Case("dns.example.com:853", "dns.example.com", "853"),
            Case("[2001:db8::853]", "[2001:db8::853]", null),
            Case("[2001:db8::853]:8853", "[2001:db8::853]", "8853"),
            // bare multi-colon IPv6 without brackets — whole is the host
            Case("2001:db8::853", "2001:db8::853", null),
        ).forEach { (authority, host, port) ->
            val (h, p) =
                dev.typenil.vpnclient.core.engine
                    .splitHostPort(authority)
            assertEquals("host for $authority", host, h)
            assertEquals("port for $authority", port, p)
        }
    }

    @Test
    fun `malformed ipv6 literals rejected`() {
        listOf(
            "2001:db8:::53", // triple colon
            "2001::db8::53", // two compressions
            ":2001:db8::53", // leading single colon
            "2001:db8::53:", // trailing single colon
            "2001:db8:gggg::1", // non-hex group
            "12345::1", // group > 4 hex
            "1:2:3:4:5:6:7:8:9", // too many groups
        ).forEach { s ->
            assertNull("must reject: $s", DnsUpstream.parseCustom(s))
            assertNull("must reject: $s", DnsUpstream.parseCustom("udp://$s"))
        }
        // Garbage after ']' must not be silently dropped into a valid spec
        assertNull(DnsUpstream.parseCustom("udp://[2001:db8::53]garbage"))
        assertNull(DnsUpstream.parseCustom("https://[2001:db8::853]x/dns-query"))
        assertNull(DnsUpstream.parseCustom("tls://[2001:db8::853]:bogus"))
        assertNull(DnsUpstream.parseCustom("udp://[2001:db8::53]:99999"))
        // tls/quic must reject trailing garbage after ']' too (P3 follow-up)
        assertNull(DnsUpstream.parseCustom("tls://[2001:db8::53]garbage"))
        assertNull(DnsUpstream.parseCustom("quic://[2001:db8::53]garbage"))
        assertNull(DnsUpstream.parseCustom("tls://[2001:db8::53]extra:853"))
        // But clean tls/quic hosts still parse — literals, hostnames, v6
        assertEquals("tls://1.1.1.1", DnsUpstream.parseCustom("tls://1.1.1.1")?.spec)
        assertEquals("tls://one.one.one.one", DnsUpstream.parseCustom("tls://one.one.one.one")?.spec)
        assertEquals("tls://[2001:db8::853]", DnsUpstream.parseCustom("tls://[2001:db8::853]")?.spec)
    }
}
