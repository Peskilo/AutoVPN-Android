#!/usr/bin/env python3
"""Patch the pinned LibreXrayVPN source to accept subscription URLs from clipboard."""
import sys
from pathlib import Path

root = Path(sys.argv[1])
path = root / "data/src/main/java/org/librexray/vpn/data/repository_impl/ServerConfigImportRepositoryImpl.kt"
source = path.read_text()
start = source.index("    private fun importBatchConfig(server: String?): ImportResult {")
end = source.index("\n    /**", start)
replacement = r'''    private fun importBatchConfig(server: String?): ImportResult {
        return runCatching {
            val raw = server?.trim().orEmpty()
            // Clipboard may contain a subscription URL, not only a single server URI.
            val payload = if (raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true)) {
                val connection = java.net.URL(raw).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "SayanVPN")
                }
                connection.getInputStream().bufferedReader().use { it.readText() }
            } else raw
            val addedDecoded = parseBatchConfig(Utils.decode(payload))
            val added = if (addedDecoded == 0) parseBatchConfig(payload) else addedDecoded
            if (added > 0) ImportResult.Success else ImportResult.Empty
        }.getOrElse { e ->
            Utils.error("Failed to import from text", e)
            ImportResult.Error
        }
    }'''
path.write_text(source[:start] + replacement + source[end:])
print("Patched clipboard subscription URL import")
