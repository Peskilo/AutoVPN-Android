package dev.typenil.vpnclient.core.subscription.model

/**
 * A selectable proxy endpoint parsed from a subscription or a pasted URI.
 *
 * [outboundJson] is the engine-native (sing-box) outbound object as JSON —
 * the pure transport policy inspects it for confidentiality; other consumers
 * hand it to the config compiler without exposing credentials. [rawUri] is the original share link when the node
 * came from a URI list; it is kept for re-parse/debug but never logged.
 */
data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: ProtocolType,
    val server: String,
    val port: Int,
    val outboundJson: String,
    val rawUri: String?,
    val subscriptionId: Long,
)

/** Compact node info for state-machine/UI display — no secrets. */
data class NodeSummary(
    val id: String,
    val name: String,
    val protocol: ProtocolType,
    val server: String,
)

fun ProxyNode.summary() = NodeSummary(id, name, protocol, server)


/**
 * Country detection based on common labels used by subscription providers.
 * Unknown labels are treated as non-Russian; matching is deliberately limited
 * to explicit country names/flag rather than guessing from hostnames.
 */
fun ProxyNode.isRussianServer(): Boolean {
    val label = name.lowercase()
    return "россия" in label ||
        "russia" in label ||
        "russian" in label ||
        "🇷🇺" in name
}
