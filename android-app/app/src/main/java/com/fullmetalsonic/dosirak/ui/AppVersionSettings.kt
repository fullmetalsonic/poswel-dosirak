package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticStatus
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticStorageError
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    AnalysisSettings(state, onAction)
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

@Composable
internal fun AnalysisSettings(state: UiState, onAction: (UiAction) -> Unit) {
    val status = state.diagnostics
    val busy = status.busy || state.diagnosticActionBusy
    val hasLogs = status.bytes > 0 || status.detailBytes > 0
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDetail by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.enabled) { if (!status.enabled) confirmDetail = false }
    ToggleRow("분석 모드", status.enabled, onChange = {
        onAction(UiAction.SetAnalysisEnabled(it))
    }, enabled = !busy)
    ToggleRow("상세분석 모드", status.enabled && status.detailEnabled, onChange = { enabled ->
        if (enabled && !status.detailAcknowledged) confirmDetail = true
        else onAction(UiAction.SetDetailAnalysisEnabled(enabled))
    }, enabled = !busy && status.enabled)
    Text(analysisRecordingLabel(status), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("analysis_recording_status"))
    Text("마지막 기록: ${status.lastSavedEpochMillis?.let { analysisTime(it) } ?: "없음"}",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("analysis_last_saved"))
    Text("저장 용량: 기본 ${analysisSize(status.bytes)} · 상세 ${analysisSize(status.detailBytes)}", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("analysis_storage"))
    if (status.dropped > 0 || status.detailDropped > 0) Text("누락된 기록: 기본 ${status.dropped}개 · 상세 ${status.detailDropped}개", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("analysis_dropped"))
    status.error?.let { error ->
        Text(analysisErrorLabel(error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("analysis_error"))
    }
    state.diagnosticMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("analysis_action_result"))
    }
    OutlinedButton(onClick = { onAction(UiAction.ShareAnalysis) }, enabled = !busy && hasLogs,
        modifier = Modifier.fillMaxWidth().testTag("analysis_share")) {
        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("로그 전송하기")
    }
    OutlinedButton(onClick = { onAction(UiAction.ExportAnalysis) }, enabled = !busy && hasLogs,
        modifier = Modifier.fillMaxWidth().testTag("analysis_export")) {
        Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("로그 파일 저장")
    }
    TextButton(onClick = { confirmClear = true }, enabled = !busy && hasLogs,
        modifier = Modifier.fillMaxWidth().testTag("analysis_clear")) {
        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("로그 지우기")
    }
    if (confirmDetail && status.enabled) AlertDialog(onDismissRequest = { confirmDetail = false },
        title = { Text("상세분석 모드를 켤까요?") },
        text = { Text("주문 관련 상세 자료를 기기에 기록합니다. 공유 시 함께 포함됩니다.") },
        confirmButton = { TextButton(onClick = {
            confirmDetail = false
            onAction(UiAction.SetDetailAnalysisEnabled(true))
        }, enabled = !busy, modifier = Modifier.testTag("analysis_detail_confirm")) { Text("켜기") } },
        dismissButton = { TextButton(onClick = { confirmDetail = false }) { Text("취소") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("로그를 지울까요?") },
        text = { Text("예약과 계정은 유지됩니다. 이미 전송·저장한 파일은 삭제되지 않습니다.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            onAction(UiAction.ClearAnalysis)
        }, modifier = Modifier.testTag("analysis_clear_confirm")) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
}

internal fun analysisRecordingLabel(status: DiagnosticStatus): String = when {
    status.busy -> "기록 정리 중"
    status.error != null -> "기록에 문제가 있습니다"
    !status.enabled -> "기록 꺼짐"
    status.detailEnabled -> "기록 켜짐 · 상세 켜짐"
    else -> "기록 켜짐 · 상세 꺼짐"
}

internal fun analysisErrorLabel(error: DiagnosticStorageError): String = when (error) {
    DiagnosticStorageError.SETTINGS_READ -> "분석 설정을 읽지 못했습니다."
    DiagnosticStorageError.SETTINGS_WRITE -> "분석 설정을 저장하지 못했습니다."
    DiagnosticStorageError.READ -> "로그를 읽지 못했습니다."
    DiagnosticStorageError.WRITE -> "로그를 저장하지 못했습니다."
    DiagnosticStorageError.DELETE -> "일부 로그를 지우지 못했습니다."
    DiagnosticStorageError.SNAPSHOT -> "로그 파일을 만들지 못했습니다."
    DiagnosticStorageError.QUEUE_FULL -> "처리할 기록이 많아 일부를 누락했습니다."
    DiagnosticStorageError.UNAVAILABLE -> "지금은 분석 기록을 사용할 수 없습니다."
}

private fun analysisTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Seoul")))

private fun analysisSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    else -> "${(bytes + 1023) / 1024} KB"
}
