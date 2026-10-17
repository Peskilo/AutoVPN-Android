#!/usr/bin/env python3
"""Patch the pinned LibreXrayVPN source with manual text/subscription import."""
import sys
from pathlib import Path

root = Path(sys.argv[1])

def replace_once(path, old, new):
    p = root / path
    s = p.read_text()
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}")
    p.write_text(s.replace(old, new, 1))

repo_path = "data/src/main/java/org/librexray/vpn/data/repository_impl/ServerConfigImportRepositoryImpl.kt"
p = root / repo_path
source = p.read_text()
start = source.index("    private fun importBatchConfig(server: String?): ImportResult {")
end = source.index("\n    /**", start)
replacement = r'''    private fun importBatchConfig(server: String?): ImportResult {
        return runCatching {
            val raw = server?.trim().orEmpty()
            // Subscription URLs return a list of configs; individual URI imports work too.
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
p.write_text(source[:start] + replacement + source[end:])

replace_once(
    "domain/src/main/java/org/librexray/vpn/domain/interfaces/repository/ServerConfigImportRepository.kt",
    "    suspend fun importFromClipboard(): ImportResult\n",
    "    suspend fun importFromClipboard(): ImportResult\n    suspend fun importFromText(text: String): ImportResult\n"
)
replace_once(
    repo_path,
    "    /**\n     * Imports configuration(s) from the system clipboard.\n     */",
    '''    override suspend fun importFromText(text: String): ImportResult = try {
        val value = text.trim()
        if (value.isEmpty()) ImportResult.Empty
        else withContext(Dispatchers.IO) { importBatchConfig(value) }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Utils.error("Failed to import config from text field", e)
        ImportResult.Error
    }

    /**
     * Imports configuration(s) from the system clipboard.
     */'''
)
replace_once(
    "domain/src/main/java/org/librexray/vpn/domain/interfaces/interactor/ConfigInteractor.kt",
    "    suspend fun importClipboardConfig(): ImportResult\n",
    "    suspend fun importClipboardConfig(): ImportResult\n    suspend fun importTextConfig(text: String): ImportResult\n"
)
replace_once(
    "domain/src/main/java/org/librexray/vpn/domain/interactor_impl/ConfigInteractorImpl.kt",
    "    override suspend fun importClipboardConfig(): ImportResult {\n        return serverConfigImportRepository.importFromClipboard()\n    }",
    "    override suspend fun importClipboardConfig(): ImportResult {\n        return serverConfigImportRepository.importFromClipboard()\n    }\n\n    override suspend fun importTextConfig(text: String): ImportResult {\n        return serverConfigImportRepository.importFromText(text)\n    }"
)
replace_once(
    "presentation/src/main/java/org/librexray/vpn/presentation/intent/VpnScreenIntent.kt",
    "    object ImportConfigFromClipboard : VpnScreenIntent\n",
    "    object ImportConfigFromClipboard : VpnScreenIntent\n    data class ImportConfigFromText(val text: String) : VpnScreenIntent\n"
)
replace_once(
    "presentation/src/main/java/org/librexray/vpn/presentation/view_model/VpnScreenViewModel.kt",
    "            VpnScreenIntent.ImportConfigFromClipboard -> importConfigFromClipboard()\n",
    "            VpnScreenIntent.ImportConfigFromClipboard -> importConfigFromClipboard()\n            is VpnScreenIntent.ImportConfigFromText -> importConfigFromText(intent.text)\n"
)
replace_once(
    "presentation/src/main/java/org/librexray/vpn/presentation/view_model/VpnScreenViewModel.kt",
    "    /** Forces a full refresh of the server list. */",
    '''    /** Imports manually entered configuration or a subscription URL. */
    private fun importConfigFromText(text: String) {
        viewModelScope.launch(io) {
            when (configInteractor.importTextConfig(text)) {
                ImportResult.Empty -> _state.update {
                    it.copy(error = VpnScreenError.EmptyConfigError)
                }
                ImportResult.Error -> _state.update {
                    it.copy(error = VpnScreenError.ImportConfigError)
                }
                ImportResult.Success -> updateServerList(configInteractor.getServerList())
            }
        }
    }

    /** Forces a full refresh of the server list. */'''
)

sheet_path = "presentation/src/main/java/org/librexray/vpn/presentation/composable_element/ConfigSheet.kt"
p = root / sheet_path
source = p.read_text()
source = source.replace(
    "import androidx.compose.material.MaterialTheme\n",
    "import androidx.compose.material.MaterialTheme\nimport androidx.compose.material.OutlinedTextField\n"
)
source = source.replace(
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\n"
)
source = source.replace(
    "import androidx.compose.ui.platform.LocalConfiguration\n",
    "import androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.platform.LocalClipboardManager\n"
)
source = source.replace(
    "import androidx.compose.ui.text.style.TextOverflow\n",
    "import androidx.compose.ui.text.AnnotatedString\nimport androidx.compose.ui.text.style.TextOverflow\n"
)
start = source.index("@Composable\nprivate fun ImportButtonRow(")
replacement_sheet = r'''@Composable
private fun ImportButtonRow(
    modifier: Modifier = Modifier,
    onIntent: (VpnScreenIntent) -> Unit,
    onQrCodeClick: () -> Unit
) {
    val text = remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text.value,
            onValueChange = { text.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Configuration or subscription link") },
            placeholder = { Text("Paste vless://, vmess:// or https:// link") },
            maxLines = 4,
            textStyle = MaterialTheme.typography.body2
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    if (clip.isNotBlank()) text.value = clip
                }
            ) { Text("Paste") }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { if (text.value.isNotBlank()) onIntent(VpnScreenIntent.ImportConfigFromText(text.value)) }
            ) { Text("Import text") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = Modifier.weight(1f).height(72.dp).padding(end = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.7f)
                ),
                elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
                onClick = onQrCodeClick
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        painter = AppIcons.QrScan.rememberPainter(),
                        contentDescription = stringResource(R.string.qr_code),
                        tint = MaterialTheme.colors.onSurface
                    )
                    Text(text = stringResource(R.string.qr_code), style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                modifier = Modifier.weight(1f).height(72.dp).padding(start = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.7f)
                ),
                elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
                onClick = { onIntent(VpnScreenIntent.ImportConfigFromClipboard) }
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        painter = AppIcons.Clipboard.rememberPainter(),
                        contentDescription = stringResource(R.string.clipboard),
                        tint = MaterialTheme.colors.onSurface
                    )
                    Text(text = stringResource(R.string.clipboard), style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
'''
source = source[:start] + replacement_sheet
# Remove unused AnnotatedString import if any; clipboard API returns AnnotatedString but doesn't need type explicitly.
source = source.replace("import androidx.compose.ui.text.AnnotatedString\n", "")
p.write_text(source)
print("Patched text-field import flow, paste shortcut, and subscription URL handling")
