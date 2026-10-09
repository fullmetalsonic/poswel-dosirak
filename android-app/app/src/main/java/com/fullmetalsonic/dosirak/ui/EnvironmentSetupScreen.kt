package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.AppSettings

@Composable
internal fun EnvironmentSetupScreen(state: UiState, settings: AppSettings, onSettings: (AppSettings) -> Unit,
    onAction: (UiAction) -> Unit, onReviewBlock: () -> Unit) {
    Text("자동주문에 필요한 휴대폰 설정", style = MaterialTheme.typography.titleMedium)
    Text("마지막 확인: ${state.lastEnvironmentCheck}", style = MaterialTheme.typography.bodySmall)
    if (state.environment.isEmpty()) Text("휴대폰 설정 상태를 아직 확인하지 않았습니다.")
    state.environment.filter { it.key != "media" }.forEach { item ->
        OutlinedButton(onClick = { onAction(UiAction.OpenEnvironment(item.key)) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("${item.title} · ${item.status}", color = if (item.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(settings.manufacturerSettingsConfirmed, { onSettings(settings.copy(manufacturerSettingsConfirmed = it)) }, modifier = Modifier.semantics { contentDescription = "삼성 절전 설정 확인" })
        Text("삼성 절전·자동실행 설정을 직접 확인했습니다.", Modifier.weight(1f))
    }
    OutlinedButton(onClick = { onAction(UiAction.RefreshEnvironment) }, modifier = Modifier.fillMaxWidth()) { Text("휴대폰 설정 다시 확인") }
    state.settings.liveBlockedReason?.let {
        Text(friendlyBlockReason(it), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onReviewBlock, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("차단 해제 검토") }
    }
}
