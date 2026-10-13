package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionFormat
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionClassifierTest {

    private val classifier = SubscriptionClassifier()

    private fun classify(text: String, contentType: String? = null) =
        classifier.classify(text.toByteArray(Charsets.UTF_8), contentType)

    @Test
    fun `plain uri list is detected`() {
        val body = "vless://11111111-2222-3333-4444-555555555555@a.example.com:443#x\n" +
            "ss://aes-256-gcm:pw@b.example.com:8388"
        val c = classify(body)
        assertEquals(SubscriptionFormat.UriList, c.format)
        assertEquals(body, c.body)
    }

    @Test
    fun `UTF-8 BOM does not prevent plain URI list detection`() {
        val body = "\uFEFFvless://11111111-2222-3333-4444-555555555555@a.example.com:443#x"
        val c = classify(body)
        assertEquals(SubscriptionFormat.UriList, c.format)
        assertEquals(body.removePrefix("\uFEFF"), c.body)
    }

    @Test
    fun `base64 wrapped uri list is detected and decoded`() {
        val plain = "trojan://pw@t.example.com:443#n\nss://aes-256-gcm:pw@s.example.com:8388"
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray())
        val c = classify(encoded)
        assertEquals(SubscriptionFormat.Base64UriList, c.format)
        assertEquals(plain, c.body)
    }

    @Test
    fun `sing-box json is detected`() {
        val body = """{"inbounds":[],"outbounds":[{"type":"vless","tag":"n","server":"a.example.com","server_port":443}]}"""
        val c = classify(body)
        assertEquals(SubscriptionFormat.SingBoxJson, c.format)
    }

    @Test
    fun `clash yaml is detected by proxies key`() {
        val body = "proxies:\n  - name: n\n    type: ss\n    server: a.example.com\n    port: 8388"
        val c = classify(body)
        assertEquals(SubscriptionFormat.ClashYaml, c.format)
    }

    @Test
    fun `yaml content type forces clash format`() {
        val c = classify("mixed-port: 7890\nproxies: []", "application/yaml")
        assertEquals(SubscriptionFormat.ClashYaml, c.format)
    }

    @Test
    fun `empty body throws EmptyResult`() {
        try {
            classify("   \n  ")
            fail("expected EmptyResult")
        } catch (e: SubscriptionError.EmptyResult) {
            // expected
        }
    }

    @Test
    fun `unrecognized body throws UnsupportedFormat`() {
        try {
            classify("hello world this is not a subscription at all")
            fail("expected UnsupportedFormat")
        } catch (e: SubscriptionError.UnsupportedFormat) {
            // expected
        }
    }

    @Test
    fun `xray json is classified separately`() {
        val body = """{"outbounds":[{"protocol":"vless","settings":{}}]}"""
        val c = classify(body)
        assertEquals(SubscriptionFormat.XrayJson, c.format)
    }
}
