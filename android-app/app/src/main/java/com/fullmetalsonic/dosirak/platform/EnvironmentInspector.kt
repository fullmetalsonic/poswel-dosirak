package com.fullmetalsonic.dosirak.platform

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.fullmetalsonic.dosirak.domain.EnvironmentStatus

class EnvironmentInspector(context: Context) {
    private val context = context.applicationContext

    fun inspect(): List<EnvironmentStatus> {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        val notificationAllowed = notifications.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val failureChannel = notifications.getNotificationChannel(OrderNotifier.FAILURE_CHANNEL)
        val channelBlocked = failureChannel?.importance == NotificationManager.IMPORTANCE_NONE
        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted
        val batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val connected = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val dataBlocked = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && connectivity.isActiveNetworkMetered
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        return listOf(
            EnvironmentStatus("exact", "정확 알람", if (exact) "확인됨" else "차단", if (exact) "OS가 지정 시각의 알람 등록을 허용합니다. 서버 접수 시각을 보장하지 않습니다." else "시각 예약을 등록할 수 없습니다. 자동실행 ON 설정은 유지됩니다.", !exact),
            EnvironmentStatus("notifications", "알림 권한", if (notificationAllowed) "확인됨" else "차단", if (notificationAllowed) "앱 알림이 허용되어 있습니다." else "진행·결과 알림이 화면에 보이지 않을 수 있습니다.", false),
            EnvironmentStatus("channel", "실패 알림 채널", when { channelBlocked -> "차단"; failureChannel == null -> "확인하지 못함"; failureChannel.sound == null || failureChannel.importance < NotificationManager.IMPORTANCE_HIGH -> "주의"; else -> "확인됨" }, "소리·진동과 방해금지 정책은 Android 및 사용자의 채널 설정을 따릅니다."),
            EnvironmentStatus("battery", "배터리 최적화", if (batteryExempt) "확인됨" else "주의", if (batteryExempt) "표준 배터리 최적화 면제입니다. 제조사 제한까지 확인한 것은 아닙니다." else "절전 상태에서는 네트워크·작업이 지연될 수 있습니다."),
            EnvironmentStatus("background", "앱 백그라운드 제한", if (backgroundRestricted) "차단" else "확인됨", if (backgroundRestricted) "Android가 앱 백그라운드 작업을 제한하고 있습니다." else "표준 API에서 앱별 백그라운드 제한은 발견되지 않았습니다.", backgroundRestricted),
            EnvironmentStatus("data", "데이터 절약", if (dataBlocked) "차단" else "확인됨", if (dataBlocked) "현재 종량제 네트워크에서 백그라운드 데이터가 제한됩니다." else "현재 네트워크의 데이터 절약 차단은 발견되지 않았습니다.", dataBlocked),
            EnvironmentStatus("power", "전체 절전 모드", if (power.isPowerSaveMode) "주의" else "확인됨", if (power.isPowerSaveMode) "전체 절전 모드가 켜져 있습니다." else "전체 절전 모드가 꺼져 있습니다."),
            EnvironmentStatus("network", "인터넷 연결", if (connected) "확인됨" else "차단", if (connected) "Android가 인터넷 연결을 확인했습니다. 포스웰 로그인·서버 상태는 별도 확인이 필요합니다." else "검증된 인터넷 연결이 없습니다.", !connected),
            EnvironmentStatus("unlock", "재부팅 후 잠금 해제", if (unlocked) "확인됨" else "차단", if (unlocked) "보호된 앱 저장소를 사용할 수 있습니다." else "첫 잠금 해제 전에는 주문을 실행하지 않습니다.", !unlocked),
            EnvironmentStatus("manufacturer", "제조사 절전 목록", "사용자 확인 필요", "${Build.MANUFACTURER}의 절전·초절전 앱 목록은 표준 API로 판정할 수 없습니다. 설정에서 직접 확인한 경우 별도 확인 항목에 기록하세요."),
            EnvironmentStatus("media", "화면 꺼짐 미디어 경보", "미지원", "Android 17 백그라운드 미디어 재생·음량 복원 경로는 검증되지 않았습니다. 결과 알림은 Android 설정을 따릅니다.")
        )
    }

    fun open(key: String): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val target = when (key) {
            "exact" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else appDetails(packageUri)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "channel" -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, OrderNotifier.FAILURE_CHANNEL)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "data" -> Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri)
            "power" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "network" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> appDetails(packageUri)
        }
        return launch(target) || launch(appDetails(packageUri))
    }

    private fun appDetails(uri: Uri) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    private fun launch(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
