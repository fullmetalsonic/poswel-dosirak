package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AppVersionSettings(state: UiState, onAction: (UiAction) -> Unit) {
    val context = LocalContext.current
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val update = state.updateStatus
    Text("현재 버전", style = MaterialTheme.typography.labelMedium)
    Text(state.currentVersion.ifBlank { "확인할 수 없음" }, modifier = Modifier.testTag("app_current_version"))
    Text("최신 정식 버전", style = MaterialTheme.typography.labelMedium)
    Text(update.latestVersion ?: "확인 전", modifier = Modifier.testTag("app_latest_version"))
    Text(if (update.checking) "새 버전을 확인하고 있습니다." else update.message,
        color = if (update.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("app_update_status"))
    Button(onClick = { onAction(UiAction.CheckUpdate) }, enabled = !update.checking,
        modifier = Modifier.fillMaxWidth().testTag("app_check_update")) {
        if (update.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (update.checking) "확인 중" else "새 버전 확인")
    }
    OutlinedButton(onClick = { onAction(UiAction.OpenRelease) }, modifier = Modifier.fillMaxWidth().testTag("app_open_release")) {
        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("배포 페이지")
    }
    HorizontalDivider()
    TextButton(onClick = { document = "privacy.txt" }, modifier = Modifier.testTag("app_privacy")) {
        Text("개인정보 안내")
    }
    TextButton(onClick = { document = "third_party_notices.txt" }, modifier = Modifier.testTag("app_notices")) {
        Text("오픈소스 고지")
    }
    document?.let { file ->
        val body = remember(context, file) {
            runCatching { context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrDefault("안내 문서를 열지 못했습니다.")
        }
        AlertDialog(onDismissRequest = { document = null },
            title = { Text(if (file == "privacy.txt") "개인정보 안내" else "오픈소스 고지") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app_document_body"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { document = null }) { Text("닫기") } })
    }
}
