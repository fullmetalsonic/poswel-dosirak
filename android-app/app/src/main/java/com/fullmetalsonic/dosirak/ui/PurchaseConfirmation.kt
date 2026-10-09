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
import com.fullmetalsonic.dosirak.domain.*

@Composable
internal fun PurchaseConfirmation(state: UiState, plan: OrderPlan, recurring: Boolean, immediate: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    val settings = state.settings
    val amount = appliedLimit(settings, plan.quantity)
    val invalid = ReservationLimits.validate(settings)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (immediate) "실제 구매 신청" else if (recurring) "반복 실제구매 활성화" else "선택 날짜 실제구매 활성화") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("계정: ${state.accountLabel.ifBlank { state.sessionLabel }}")
                Text("수령일: ${plan.date}")
                Text("수량: ${plan.quantity}개")
                Text("신청 시각: ${if (immediate) "지금" else plan.time.format(TimeFormat)}")
                Text("개당 허용액: ${settings.unitLimit?.let { "${it}원" } ?: "미입력"}")
                Text("1회 총액 상한: ${settings.orderLimit?.let { "${it}원" } ?: "미입력"}")
                Text("적용 상한: ${if (!settings.limitEnabled) "금액 상한 없음" else amount?.let { "${it}원" } ?: "계산 불가"}")
                Text("조회부터 접수 사이 가격이 바뀌면 실제 공제액이 표시금액과 한도를 넘을 수 있습니다. 서버 가격 고정은 검증되지 않았습니다.", color = MaterialTheme.colorScheme.error)
                if (recurring) Text("저장된 자동·수동 계획을 반복 실행합니다.")
                invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted, onCheckedChange = null)
                    Text("가격 변동과 한도 초과 가능성을 확인하고 실제 구매에 동의합니다.", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소") }
                    TextButton(onClick = onConfirm, enabled = accepted && invalid == null && !state.busy) { Text(if (immediate) "실제 신청" else "확정") }
                }
            }
        })
}

internal fun appliedLimit(settings: AppSettings, quantity: Int): Long? {
    val p = settings.unitLimit ?: return null
    val c = settings.orderLimit ?: return null
    if (p <= 0 || c <= 0 || quantity !in 1..5 || p > Long.MAX_VALUE / quantity) return null
    return minOf(p * quantity, c)
}
