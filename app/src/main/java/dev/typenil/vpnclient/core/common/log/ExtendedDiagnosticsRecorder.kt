package dev.typenil.vpnclient.core.common.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional, local-only VPN session journal. It records metadata, never proxy URIs,
 * credentials, UUIDs, visited domains, or payload data. The file is bounded.
 */
@Singleton
class ExtendedDiagnosticsRecorder @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val file = File(context.filesDir, "extended-diagnostics.log")
    private val lock = Any()
    private var sessionStartedAt: Long? = null

    suspend fun record(event: String, node: NodeSummary? = null, detail: String? = null) {
        if (!settings.extendedDiagnostics.first()) return
        val now = System.currentTimeMillis()
        val duration = if (event == "DISCONNECTED") sessionStartedAt?.let { (now - it).coerceAtLeast(0) } else null
        if (event == "CONNECTED") sessionStartedAt = now
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(now))
        val line = buildString {
            append(stamp).append(" event=").append(clean(event, 48))
            node?.let {
                append(" serverName=").append(clean(it.name, 100))
                append(" server=").append(clean(it.server, 160))
                append(" protocol=").append(clean(it.protocol.label, 32))
            }
            detail?.takeIf { it.isNotBlank() }?.let { append(" detail=").append(clean(it, 80)) }
            duration?.let { append(" durationMs=").append(it) }
        }
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
            trimIfNeeded()
        }
        if (event == "DISCONNECTED" || event == "START_FAILED") sessionStartedAt = null
    }

    fun snapshotText(): String = synchronized(lock) {
        if (!file.isFile) return@synchronized ""
        runCatching { file.readText(Charsets.UTF_8).takeLast(MAX_CHARS) }.getOrDefault("")
    }

    private fun trimIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val tail = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES).joinToString("\n", postfix = "\n")
        file.writeText(tail, Charsets.UTF_8)
    }

    private fun clean(value: String, limit: Int): String =
        value.replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("(vless|vmess|trojan|ss|hysteria2|tuic|anytls|wireguard)://[^ ]+", RegexOption.IGNORE_CASE), "[proxy-uri-redacted]")
            .take(limit)

    private companion object {
        const val MAX_BYTES = 512 * 1024L
        const val MAX_LINES = 2500
        const val MAX_CHARS = 300_000
    }
}
