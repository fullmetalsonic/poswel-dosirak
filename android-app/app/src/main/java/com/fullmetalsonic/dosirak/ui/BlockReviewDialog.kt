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

@Composable
internal fun BlockReviewDialog(reason: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var approved by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("차단 해제 검토") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(reason, color = MaterialTheme.colorScheme.error)
            Text("미래 실행의 차단 해제를 요청합니다. 확인필요 기록을 재전송하거나 사이트 주문을 취소하지 않습니다. 예약을 새로 켜지 않습니다.")
            Row(Modifier.fillMaxWidth().toggleable(approved, role = Role.Checkbox, onValueChange = { approved = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(approved, onCheckedChange = null)
                Text("사이트 주문내역과 금액/문제를 확인했습니다.", Modifier.weight(1f))
            }
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = approved && enabled) { Text("확인 후 차단 해제") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
