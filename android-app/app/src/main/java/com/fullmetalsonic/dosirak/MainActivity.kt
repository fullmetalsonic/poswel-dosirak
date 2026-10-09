package com.fullmetalsonic.dosirak

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fullmetalsonic.dosirak.platform.OrderNotifier
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import com.fullmetalsonic.dosirak.ui.LunchApp
import com.fullmetalsonic.dosirak.ui.UiAction
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && ::controller.isInitialized) controller.exportTo(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::controller.isInitialized) controller.importFrom(uri)
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::controller.isInitialized) {
            if (!granted) controller.notificationPermissionNeeded()
            controller.refreshEnvironment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AppController(this, RuntimeProvider.get(this),
            requestExport = { exportLauncher.launch("dosirak-plan-${LocalDate.now()}.json") },
            requestImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) })
        setContent {
            val state by controller.state.collectAsState()
            LunchApp(state, ::onAction)
        }
        openNotificationHistory(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotificationHistory(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.refreshEnvironment()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (::controller.isInitialized) controller.resumeInterruptedPreparation(this)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }

    private fun openNotificationHistory(intent: Intent?) {
        val date = intent?.getStringExtra(OrderNotifier.EXTRA_HISTORY_DATE) ?: return
        if (runCatching { LocalDate.parse(date) }.isSuccess) {
            controller.onAction(UiAction.OpenSite("/order.list.php"))
        }
        intent.removeExtra(OrderNotifier.EXTRA_HISTORY_DATE)
    }

    private fun onAction(action: UiAction) {
        val testing = action is UiAction.TestNotification || action == UiAction.TestSound
        val accessClick = action is UiAction.OpenEnvironment && action.key == "notifications"
        if (!testing && !accessClick) {
            controller.onAction(action)
            return
        }
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsAllowed = permissionGranted && getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (testing && !appNotificationsAllowed) controller.notificationPermissionNeeded()
        if (accessClick || testing && !appNotificationsAllowed) {
            val history = getSharedPreferences("notification_permission_ui", MODE_PRIVATE)
            val route = notificationPermissionRoute(Build.VERSION.SDK_INT >= 33, permissionGranted,
                history.getBoolean("asked", false), Build.VERSION.SDK_INT >= 33 &&
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
            if (route == NotificationPermissionRoute.REQUEST) {
                history.edit().putBoolean("asked", true).apply()
                if (!testing) controller.notificationPermissionNeeded()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else controller.onAction(UiAction.OpenEnvironment("notifications"))
        } else controller.onAction(action)
    }
}
