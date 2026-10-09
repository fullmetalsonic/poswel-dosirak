package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*

@Composable
internal fun RecurringSummary(settings: AppSettings, state: UiState) {
    val next = futureUiPlans(settings, state).firstOrNull()
    Text("계정: ${state.accountLabel.ifBlank { "저장된 계정 확인 필요" }}")
    if (settings.dayAutoEnabled || settings.patternConfirmed) Text("근무조: ${settings.shiftType.label} · ${if (settings.patternConfirmed) "근무표 확인됨" else "근무표 확인 필요"}")
    Text(if (settings.dayAutoEnabled) "주간근무일 자동예약: 켜짐" else "주간근무일 자동예약: 꺼짐 · 저장한 수동 예약만 실행")
    Text("자동·수동 계획을 반복 실행 · 신청 제외 날짜는 실행하지 않음", style = MaterialTheme.typography.bodySmall)
    val futureOverrides = state.overrides.values.filter { it.date >= todaySeoul() }
    Text("날짜별 수동 예약 ${futureOverrides.count { it.policy == DatePolicy.MANUAL }}일 · 신청 제외 ${futureOverrides.count { it.policy == DatePolicy.EXCLUDE }}일", style = MaterialTheme.typography.bodySmall)
    Text("기본 ${settings.defaultQuantity}개 · ${settings.orderTime.format(TimeFormat)}")
    if (settings.limitEnabled) {
        Text("도시락 1개 최대금액: ${settings.unitLimit?.let { "${it}원" } ?: "미입력"}")
        Text("한 번에 주문할 최대금액: ${settings.orderLimit?.let { "${it}원" } ?: "미입력"}")
    } else Text("금액 한도 사용 안 함 · 가격 상한 보호 없음", color = MaterialTheme.colorScheme.error)
    if (next != null) {
        Text("다음 계획: ${next.date} · ${next.quantity}개 · ${next.time.format(TimeFormat)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (settings.limitEnabled) Text("다음 주문 적용 상한: ${appliedLimit(settings, next.quantity)?.let { "${it}원" } ?: "금액 확인 필요"}")
    } else Text("06:00~08:00 사이에 실행할 미래 계획 없음", color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun RecurringConsentDialog(snapshot: ActivationSnapshot, state: UiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("자동주문 시작 확인") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecurringSummary(snapshot.settings, state.copy(accountLabel = snapshot.accountLabel))
            Text("조회부터 접수 사이 가격이 바뀌면 실제 공제액이 표시금액과 한도를 넘을 수 있습니다. 서버 가격 고정은 검증되지 않았습니다.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(accepted, onCheckedChange = null)
                Text("반복 실제구매와 가격 변동·한도 초과 가능성에 동의합니다.", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("취소") }
                TextButton(onClick = onConfirm, enabled = accepted && setupIssues(snapshot.settings, state).isEmpty(), modifier = Modifier.weight(1f)) { Text("동의하고 자동주문 시작") }
            }
        }
    })
}
