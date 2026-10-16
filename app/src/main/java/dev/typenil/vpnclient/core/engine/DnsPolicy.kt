package dev.typenil.vpnclient.core.engine

import dev.typenil.vpnclient.core.common.log.Redactor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DNS resolution mode — persisted in DataStore as [key].
 *
 * [POLICY]: DNS follows the RouteMode (RU/Blocked lists get their per-mode
 * resolver; default stays the proxied DoH). The honest promise: user-domain
 * queries ride the selected proxy; only *bootstrap* names (the DoH/DoT
 * server's own hostname, proxy server names) resolve via the local/system
 * resolver — that bootstrap is required to break the remote→proxy→remote
 * loop and is always documented, never hidden.
 *
 * [PROXY_ONLY]: every user DNS query goes through the proxied upstream;
 * `dns.final` is `remote` and no mode rule maps traffic to `local`.
 * Bootstrap still uses `local` (it only ever resolves infrastructure
 * names — no user traffic leaks there).
 */
enum class DnsMode(
    val key: String,
) {
    POLICY("policy"),
    PROXY_ONLY("proxy_only"),
    ;

    companion object {
        fun fromKey(key: String?): DnsMode = entries.firstOrNull { it.key == key } ?: POLICY
    }
}

/**
 * The upstream resolver choice. Presets are fixed (versioned) entries —
 * [custom] is a user-entered spec validated by [parseCustom].
 *
 * Persisted as [key] in DataStore: preset keys or `"custom:<spec>"`.
 */
sealed class DnsUpstream {
    abstract val key: String

    /** sing-box `server` URL for the engine config. */
    abstract val serverUrl: String

    /** Whether the server name is a hostname (needs bootstrap resolution). */
    abstract val hostname: Boolean

    /** User-facing short label (resource-resolved at the UI). */
    abstract val labelResName: String

    data object Cloudflare : DnsUpstream() {
        override val key = "cloudflare"
        override val serverUrl = "https://cloudflare-dns.com/dns-query"
        override val hostname = true
        override val labelResName = "dns_upstream_cloudflare"
    }

    data object Google : DnsUpstream() {
        override val key = "google"
        override val serverUrl = "https://dns.google/dns-query"
        override val hostname = true
        override val labelResName = "dns_upstream_google"
    }

    data object Quad9 : DnsUpstream() {
        override val key = "quad9"
        override val serverUrl = "https://dns.quad9.net/dns-query"
        override val hostname = true
        override val labelResName = "dns_upstream_quad9"
    }

    data object AdGuard : DnsUpstream() {
        override val key = "adguard"
        override val serverUrl = "https://dns.adguard-dns.com/dns-query"
        override val hostname = true
        override val labelResName = "dns_upstream_adguard"
    }

    data class Custom(
        val spec: String,
    ) : DnsUpstream() {
        override val key = "custom:$spec"
        override val serverUrl = spec

        // Syntax check only — never resolve the host: this property runs on
        // the UI/main path during settings read and would otherwise fire a
        // real DNS lookup per custom upstream. Authority parsing is
        // bracket-aware so a literal IPv6 isn't truncated at the first ':'.
        override val hostname =
            spec
                .substringAfter("://")
                .substringBefore('/')
                .let { authority -> !isIpLiteral(splitHostPort(authority).first.removePrefix("[").removeSuffix("]")) }
        override val labelResName = "dns_upstream_custom"
    }

    companion object {
        val presets: List<DnsUpstream> get() = listOf(Cloudflare, Google, Quad9, AdGuard)
        val default: DnsUpstream get() = Cloudflare

        fun fromKey(key: String?): DnsUpstream {
            if (key == null) return default
            // Re-validate rather than trusting the store: a corrupt or
            // hand-edited value would otherwise compile verbatim into the
            // engine config and fail the whole connect in checkConfig.
            if (key.startsWith("custom:")) {
                return parseCustom(key.removePrefix("custom:")) ?: default
            }
            return presets.firstOrNull { it.key == key } ?: default
        }

        /**
         * Validate a user-entered resolver spec into its canonical form.
         * Accepts: `https://host/path` (DoH), `tls://host` (DoT), a bare IPv4
         * (plain UDP/53), or `udp://IP`. Rejects anything else — the value is
         * compiled verbatim into the engine config, so an unvalidated string
         * would fail the whole connect inside `checkConfig`.
         */
        fun parseCustom(input: String): Custom? {
            val s = input.trim()
            if (s.isEmpty()) return null
            return when {
                s.startsWith("https://") -> {
                    val url = s.toHttpUrlOrNull() ?: return null
                    if (url.host.isBlank() || url.username.isNotEmpty() ||
                        url.password.isNotEmpty() || url.querySize > 0
                    ) {
                        return null
                    }
                    Custom(s)
                }

                s.startsWith("tls://") || s.startsWith("quic://") -> {
                    // host | host:port | [v6] | [v6]:port — no path allowed.
                    val rest = s.substringAfter("://")
                    if ('/' in rest || rest.isBlank()) return null
                    val (host, port) = splitHostPort(rest)
                    if (host.isBlank() || host.any { it.isWhitespace() }) return null
                    if (port != null && port.toIntOrNull() !in 1..65535) return null
                    // Reject anything that isn't a clean hostname or a valid
                    // IP literal — '[v6]garbage' must not slip through just
                    // because it isn't blank (splitHostPort returns the whole
                    // bad authority as host on malformed brackets).
                    val bare = host.removePrefix("[").removeSuffix("]")
                    val isValidHost =
                        if (isIpLiteral(bare)) {
                            true // v4 or v6 literal (bare v6 gets bracketed below)
                        } else {
                            // Hostname: no brackets, no stray chars, sane.
                            '[' !in host && ']' !in host &&
                                host.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
                        }
                    if (!isValidHost) return null
                    // A bare multi-colon IPv6 must be bracketed in the
                    // canonical spec so the compiler's split stays exact.
                    Custom(
                        if ('[' !in host && host.count { it == ':' } >= 2) {
                            "${s.substringBefore("://")}://[$host]"
                        } else {
                            s
                        },
                    )
                }

                s.startsWith("udp://") -> {
                    // host[:port] / [v6][:port] — keep the user's port
                    // verbatim; only validate that the host is a literal (a
                    // hostname here would need bootstrap we don't emit for
                    // udp).
                    val rest = s.removePrefix("udp://")
                    val (host, port) = splitHostPort(rest)
                    val bare = host.removePrefix("[").removeSuffix("]")
                    if (!isIpLiteral(bare)) return null
                    if (port != null && port.toIntOrNull() !in 1..65535) return null
                    Custom("udp://$rest")
                }

                isIpLiteral(s.removePrefix("[").removeSuffix("]")) -> {
                    // Bare IP → plain UDP/53. Canonicalize IPv6 to the
                    // bracketed form so a later :port parse stays exact.
                    val bare = s.removePrefix("[").removeSuffix("]")
                    Custom(if (':' in bare) "udp://[$bare]" else "udp://$bare")
                }

                else -> {
                    null
                }
            }
        }

        /** Syntax-only IP check — no DNS resolution. IPv4: dotted quad,
         *  each octet 0–255. IPv6: hex/colon groups, at least one ':' and
         *  no characters outside hex+colon. Anything else is a hostname. */
        private fun isIpLiteral(s: String): Boolean {
            if (s.isEmpty()) return false
            return when {
                // IPv4
                '.' in s && ':' !in s -> {
                    val parts = s.split('.')
                    parts.size == 4 &&
                        parts.all { p ->
                            p.isNotEmpty() && p.length <= 3 &&
                                p.all { it.isDigit() } && p.toIntOrNull()?.let { it in 0..255 } == true
                        }
                }

                // IPv6 — strict: ≤8 hex groups of 1–4 chars, at most one
                // '::' compression, no leading/trailing single ':', no ':::'.
                ':' in s -> {
                    isIpv6Literal(s)
                }

                else -> {
                    false
                }
            }
        }

        /** Strict IPv6 literal — hex groups + optional single '::'. No DNS,
         *  no InetAddress. Zone ids and IPv4 tails are not accepted. */
        private fun isIpv6Literal(s: String): Boolean {
            if (!s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }) return false
            if (":::" in s) return false
            if (s.indexOf("::") != s.lastIndexOf("::")) return false // '::' at most once
            val compressed = "::" in s
            // A lone leading/trailing ':' that isn't part of '::' is invalid.
            if (s.startsWith(":") && !s.startsWith("::")) return false
            if (s.endsWith(":") && !s.endsWith("::")) return false
            val groups = s.split(':').filter { it.isNotEmpty() }
            if (groups.any { it.length > 4 || !it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }) {
                return false
            }
            return if (compressed) groups.size < 8 else groups.size == 8
        }
    }
}

/** Split a URI authority into (host, port) — bracket-aware so `[v6]:port`
 *  keeps its brackets and `host:port` splits. A bare multi-colon IPv6 (no
 *  brackets) returns the whole string as host. Shared with ConfigCompiler. */
internal fun splitHostPort(authority: String): Pair<String, String?> {
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 0) return authority to null
        val after = authority.substring(end + 1)
        // After ']' only a :port suffix is legal — anything else means the
        // brackets weren't an authority (e.g. "[v6]garbage"); hand the whole
        // string back so the caller's host validation rejects it.
        if (after.isNotEmpty() && !after.startsWith(':')) return authority to null
        val host = authority.substring(0, end + 1)
        val port = if (after.startsWith(':')) after.substring(1) else ""
        return host to port.ifEmpty { null }
    }
    val last = authority.lastIndexOf(':')
    val first = authority.indexOf(':')
    return when {
        last < 0 -> {
            authority to null
        }

        // more than one colon without brackets = bare IPv6
        last != first -> {
            authority to null
        }

        else -> {
            authority.substring(0, last) to
                authority.substring(last + 1).ifEmpty { null }
        }
    }
}

/** Compiled DNS profile — carried on EngineConfig for applied-vs-saved UI. */
data class DnsProfile(
    val mode: DnsMode,
    val upstream: DnsUpstream,
) {
    /** Safe display label — never logs the raw custom spec's credentials-free
     *  host only; for Custom the host is what the user typed (their own data). */
    val summary: String =
        when (upstream) {
            is DnsUpstream.Custom -> "custom:${Redactor.urlForDisplay(upstream.spec)}"
            else -> "${mode.key}:${upstream.key}"
        }
}
