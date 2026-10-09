package com.fullmetalsonic.dosirak.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.BackgroundCheckMode
import java.time.LocalTime

@Composable
internal fun BackgroundSettings(state: UiState, settings: AppSettings, onSettings: (AppSettings) -> Unit,
    onAction: (UiAction) -> Unit) {
    val context = LocalContext.current
    val batteryStatus = state.environment.firstOrNull { it.key == "battery" }?.status
    val preparationReady = batteryStatus == "확인됨"
    val preparationChecking = batteryStatus !in setOf("확인됨", "주의", "차단")
    Text("주문 전 사전 준비", style = MaterialTheme.typography.titleMedium)
    Text(when {
        preparationReady -> "배터리 제한 해제 완료"
        preparationChecking -> "준비 상태 확인 중"
        else -> "배터리 제한 해제 필요"
    }, modifier = Modifier.testTag("background_preparation_status"))
    if (preparationReady) {
        Text("신청 2분 전 준비", style = MaterialTheme.typography.bodySmall)
    }
    if (!preparationReady && !preparationChecking) {
        Text("제한을 해제하지 않으면 신청 시각에 준비를 시작합니다.", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = {
        onAction(if (preparationReady || preparationChecking) UiAction.RefreshEnvironment else UiAction.OpenEnvironment("battery"))
    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("background_battery_settings")) {
        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(when {
            preparationChecking -> "준비 상태 확인"
            preparationReady -> "상태 다시 확인"
            else -> "배터리 제한 해제"
        })
    }
    HorizontalDivider()
    ToggleRow("백그라운드 자동실행 점검", settings.backgroundCheckEnabled,
        { onSettings(settings.copy(backgroundCheckEnabled = it)) })
    if (settings.backgroundCheckEnabled) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(BackgroundCheckMode.HOURLY to "1시간마다", BackgroundCheckMode.DAILY to "매일 지정 시각").forEachIndexed { index, (mode, label) ->
                SegmentedButton(selected = settings.backgroundCheckMode == mode,
                    onClick = { onSettings(settings.copy(backgroundCheckMode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag("background_${mode.name}")) {
                    Text(label)
                }
            }
        }
        if (settings.backgroundCheckMode == BackgroundCheckMode.DAILY) {
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    onSettings(settings.copy(backgroundCheckTime = LocalTime.of(hour, minute)))
                }, settings.backgroundCheckTime.hour, settings.backgroundCheckTime.minute, true).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("점검 시각 ${settings.backgroundCheckTime.format(TimeFormat)}") }
        }
        Text("절전 상태에서는 점검이 늦어질 수 있습니다.", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Text("예약 실행 상태", style = MaterialTheme.typography.titleMedium)
    state.registrationMessage?.let { Text(it, color = if (state.registrationProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    Text("마지막 점검: ${state.backgroundLastCheck}", modifier = Modifier.testTag("background_last_check"))
    Text("다음 점검: ${state.backgroundNextCheck}", modifier = Modifier.testTag("background_next_check"))
    state.backgroundCheckSummary?.let { Text(it, modifier = Modifier.testTag("background_check_summary"), style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = { onAction(UiAction.CheckBackgroundNow) }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("background_check_now")) { Text("지금 점검") }
    OutlinedButton(onClick = { onAction(UiAction.RefreshEnvironment) }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("background_refresh")) { Text("상태 새로고침") }
    Text("신청 전에만 실행해 대기합니다.", style = MaterialTheme.typography.bodySmall)
    Text("휴대폰 설정에서 강제 중지한 경우 앱을 다시 열어야 합니다.", style = MaterialTheme.typography.bodySmall)
}
