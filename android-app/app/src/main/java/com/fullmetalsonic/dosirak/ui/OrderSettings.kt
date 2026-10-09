package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*

@Composable
internal fun OrderSettings(settings: AppSettings, quantity: String, time: String, p: String, c: String,
    retries: String, interval: String, onQuantity: (String) -> Unit, onTime: (String) -> Unit,
    onP: (String) -> Unit, onC: (String) -> Unit, onRetries: (String) -> Unit, onInterval: (String) -> Unit,
    onSettings: (AppSettings) -> Unit) {
    QuantityControl(quantity, onQuantity)
    Text("자동주문 시각 (HH:mm:ss)", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(time, onTime, singleLine = true, isError = parseTime(time)?.let { !validOrderTime(it) } ?: true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "자동주문 시각 (HH:mm:ss)" })
    Text("신청 가능 시각 06:00:00~07:59:59", style = MaterialTheme.typography.bodySmall)
    ToggleRow("금액 한도 사용", settings.limitEnabled, { onSettings(settings.copy(limitEnabled = it)) })
    if (settings.limitEnabled) {
        SetupNumberField("도시락 1개 최대금액 (원)", p, onP, p.isNotEmpty() && (p.toLongOrNull() ?: 0) <= 0)
        SetupNumberField("한 번에 주문할 최대금액 (원)", c, onC, c.isNotEmpty() && (c.toLongOrNull() ?: 0) <= 0)
        appliedLimit(settings, quantity.toIntOrNull() ?: 0)?.let { Text("${quantity.toIntOrNull() ?: 0}개 주문 최대금액: ${it}원") }
    } else Text("금액 한도 사용 안 함 · 가격 상한 보호 없음", color = MaterialTheme.colorScheme.error)
    HorizontalDivider()
    SetupNumberField("주문 실패 후 다시 시도 횟수 (0~10회)", retries, onRetries, retries.toIntOrNull() !in 0..10)
    SetupNumberField("다시 시도까지 기다리는 시간 (1~300초)", interval, onInterval, interval.toIntOrNull() !in 1..300)
    Text("접수 여부가 불확실하면 조회만 합니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AlertSettings(state: UiState, settings: AppSettings, onSettings: (AppSettings) -> Unit, onAction: (UiAction) -> Unit) {
    val preferences = settings.effectiveNotifications()
    val permission = state.environment.firstOrNull { it.key == "notifications" }
    val notificationsAllowed = permission?.status == "확인됨"
    val permissionChecking = !notificationsAllowed && permission?.status != "차단"
    if (!notificationsAllowed) {
        Column(Modifier.fillMaxWidth().testTag("notification_permission_notice"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (permissionChecking) "알림 권한 확인 중" else "앱 알림이 꺼져 있습니다", style = MaterialTheme.typography.titleMedium)
            Text(if (permissionChecking) "알림 권한 상태를 확인한 뒤 시험할 수 있습니다." else "준비·주문 결과 알림을 받으려면 앱 알림을 허용해 주세요.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onAction(if (permissionChecking) UiAction.RefreshEnvironment else UiAction.OpenEnvironment("notifications")) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("notification_permission_action")) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.padding(end = 8.dp))
                Text(if (permissionChecking) "알림 권한 확인" else "알림 켜기")
            }
            HorizontalDivider()
        }
    }
    NotificationEvent.entries.forEach { event ->
        var expanded by rememberSaveable(event.name) { mutableStateOf(false) }
        val options = when (event) {
            NotificationEvent.PREPARATION -> preferences.preparation
            NotificationEvent.SUCCESS -> preferences.success
            NotificationEvent.FAILURE -> preferences.failure
        }
        val title = when (event) {
            NotificationEvent.PREPARATION -> "준비 알림"
            NotificationEvent.SUCCESS -> "예약 성공 알림"
            NotificationEvent.FAILURE -> "예약 실패 알림"
        }
        fun update(next: AlertOptions) {
            val updated = when (event) {
                NotificationEvent.PREPARATION -> preferences.copy(preparation = next)
                NotificationEvent.SUCCESS -> preferences.copy(success = next)
                NotificationEvent.FAILURE -> preferences.copy(failure = next)
            }.normalized()
            onSettings(settings.copy(notifications = updated))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notifications, null, Modifier.padding(end = 12.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(options.enabled, onCheckedChange = { enabled ->
                val next = if (!enabled) options.copy(enabled = false)
                    else if (options.statusBar || options.popup || options.sound || options.vibration) options.copy(enabled = true)
                    else options.copy(enabled = true, statusBar = true, popup = false, sound = false, vibration = false)
                update(next.normalized())
            }, modifier = Modifier.testTag("alert_${event.name}_enabled").semantics { contentDescription = title })
        }
        val methods = listOfNotNull(if (options.statusBar) "상태 표시줄" else null, if (options.popup) "팝업" else null,
            if (options.sound) "알림음" else null, if (options.vibration) "진동" else null)
        Text(if (methods.isEmpty()) "알림 방법 없음" else methods.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("alert_${event.name}_summary"))
        if (!options.enabled || !options.statusBar) Text("이 알림은 꺼져 있어 시험하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f).testTag("alert_${event.name}_details")) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text(if (expanded) "세부 설정 접기" else "세부 설정")
            }
            OutlinedButton(onClick = { onAction(when {
                permissionChecking -> UiAction.RefreshEnvironment
                !notificationsAllowed -> UiAction.OpenEnvironment("notifications")
                else -> UiAction.TestNotification(event, preferences.normalized())
            }) },
                enabled = options.enabled && options.statusBar && !state.busy,
                modifier = Modifier.weight(1f).testTag("alert_${event.name}_test").semantics { contentDescription = "$title 시험" }) {
                Icon(Icons.Outlined.PlayArrow, null)
                Text("시험")
            }
        }
        if (expanded) {
            AlertMethodRow(event, "statusBar", "상태 표시줄", options.statusBar, options.enabled) { selected ->
                update(if (selected) options.copy(statusBar = true).normalized()
                    else options.copy(enabled = false, statusBar = false, popup = false, sound = false, vibration = false))
            }
            AlertMethodRow(event, "popup", "팝업", options.popup, options.enabled) { selected -> update(options.copy(popup = selected, statusBar = options.statusBar || selected).normalized()) }
            AlertMethodRow(event, "sound", "알림음", options.sound, options.enabled) { selected -> update(options.copy(sound = selected, statusBar = options.statusBar || selected).normalized()) }
            AlertMethodRow(event, "vibration", "진동", options.vibration, options.enabled) { selected -> update(options.copy(vibration = selected, statusBar = options.statusBar || selected).normalized()) }
            TextButton(onClick = { onAction(UiAction.OpenNotificationSettings(event)) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("alert_${event.name}_system_settings")) { Text("이 알림의 휴대폰 설정") }
        }
        HorizontalDivider()
    }
    Text("팝업·소리·진동은 휴대폰 알림 설정의 영향을 받습니다.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { onAction(UiAction.OpenEnvironment("notifications")) }, modifier = Modifier.fillMaxWidth()) { Text("휴대폰 알림 설정") }
}

@Composable
private fun AlertMethodRow(event: NotificationEvent, key: String, title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("alert_${event.name}_$key").toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(title, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
