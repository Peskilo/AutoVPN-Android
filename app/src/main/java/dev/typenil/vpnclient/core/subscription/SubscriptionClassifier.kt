package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a raw subscription body into a known format.
 * Handles plain URI lists, Base64-encoded URI lists, sing-box/Xray JSON, and YAML configs.
 */
@Singleton
class SubscriptionClassifier @Inject constructor() {

    data class Classified(
        val format: SubscriptionFormat,
        /** Decoded body ready for the format-specific parser. */
        val body: String,
    )

    fun classify(body: ByteArray, contentType: String?): Classified {
        // Some panels prepend a UTF-8 BOM; remove it before format detection.
        val text = body.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw SubscriptionError.EmptyResult()

        return when {
            looksLikeJson(text) -> classifyJson(text)
            looksLikeYaml(text, contentType) -> Classified(SubscriptionFormat.ClashYaml, text)
            looksLikeUriList(text) -> Classified(SubscriptionFormat.UriList, text)
            else -> tryBase64(text) ?: throw SubscriptionError.UnsupportedFormat(sniff(text))
        }
    }

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")

    private fun looksLikeYaml(text: String, contentType: String?): Boolean {
        if (contentType?.contains("yaml") == true) return true
        // Clash/Mihomo configs have distinctive top-level keys.
        return text.startsWith("proxies:") || text.startsWith("proxy-providers:")
            || text.contains("\nproxies:") || text.startsWith("mixed-port:")
    }

    private fun looksLikeUriList(text: String): Boolean =
        text.lineSequence().filter { it.isNotBlank() }
            .any { KNOWN_SCHEMES.any { scheme -> it.startsWith(scheme, ignoreCase = true) } }

    private fun classifyJson(text: String): Classified {
        // sing-box configs have "outbounds" with typed objects / "inbounds".
        // Xray configs have "outbounds" where entries use "protocol"/"settings",
        // or a bare list of share-link JSON objects.
        val isSingBox = "\"inbounds\"" in text && "\"outbounds\"" in text &&
            ("\"type\"" in text || "\"tag\"" in text)
        val isXray = "\"outbounds\"" in text && "\"protocol\"" in text
        return when {
            isSingBox -> Classified(SubscriptionFormat.SingBoxJson, text)
            isXray -> Classified(SubscriptionFormat.XrayJson, text)
            else -> Classified(SubscriptionFormat.SingBoxJson, text) // try sing-box first; parser will fail cleanly
        }
    }

    /** Decode a Base64(-url) whole-body subscription; null if it isn't one. */
    private fun tryBase64(text: String): Classified? {
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.isEmpty() || compact.length % 4 == 1) return null
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(compact)
        }.recoverCatching {
            java.util.Base64.getUrlDecoder().decode(compact)
        }.getOrNull() ?: return null
        val decodedText = decoded.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        return when {
            looksLikeUriList(decodedText) -> Classified(SubscriptionFormat.Base64UriList, decodedText)
            looksLikeJson(decodedText) -> classifyJson(decodedText)
            looksLikeYaml(decodedText, null) -> Classified(SubscriptionFormat.ClashYaml, decodedText)
            else -> null
        }
    }

    private fun sniff(text: String): String =
        text.take(80).replace("\n", "\\n")

    companion object {
        val KNOWN_SCHEMES = listOf(
            "vless://", "vmess://", "trojan://", "ss://",
            "hysteria2://", "hy2://", "tuic://", "hysteria://",
            "socks://", "socks5://", "http://", "https://",
            "wireguard://", "wg://", "anytls://",
        )
    }
}
