package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountSettings(state: UiState, onAction: (UiAction) -> Unit, onSite: () -> Unit) {
    var userId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    SectionTitle("계정 · 사이트")
    Text(state.sessionLabel)
    Text(if (state.credentialsSaved) "기기에 계정 저장됨" else "저장된 계정 없음", style = MaterialTheme.typography.bodySmall)
    ToggleRow("저장 계정 사용", state.settings.useStoredCredentials, { onAction(UiAction.SaveSettings(state.settings.copy(useStoredCredentials = it))) })
    OutlinedTextField(userId, { userId = it }, label = { Text("아이디") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "비밀번호 숨기기" else "비밀번호 표시") } })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAction(UiAction.SaveCredentials(userId.trim(), password)); password = "" }, enabled = userId.isNotBlank() && password.isNotBlank() && !state.busy, modifier = Modifier.weight(1f)) { Text("계정 저장") }
        OutlinedButton(onClick = { delete = true }, enabled = state.credentialsSaved && !state.busy, modifier = Modifier.weight(1f)) { Text("저장 계정 삭제") }
    }
    TextButton(onClick = onSite, modifier = Modifier.fillMaxWidth()) { Text("포스웰 로그인 · 배송지") }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("저장 계정을 삭제할까요?") }, text = { Text("예약 실행에 사용할 저장 계정을 삭제합니다.") }, confirmButton = { TextButton(onClick = { onAction(UiAction.DeleteCredentials); password = ""; userId = ""; delete = false }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("취소") } })
}
