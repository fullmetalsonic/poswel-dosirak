package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.web.PoswelWebView

@Composable
internal fun SiteScreen(state: UiState, onAction: (UiAction) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.sessionLabel, modifier = Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onAction(UiAction.CheckLogin) }, enabled = !state.busy) { Text("로그인 확인") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.busy) Text("자동 신청 중 · 사이트 조작 잠금", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        PoswelWebView(path = state.sitePath, busy = state.busy)
    }
}
