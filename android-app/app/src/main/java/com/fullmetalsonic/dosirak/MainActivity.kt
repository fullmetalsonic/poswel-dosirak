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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.fullmetalsonic.dosirak.platform.OrderNotifier
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import com.fullmetalsonic.dosirak.ui.LunchApp
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.ui.UiState
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
            NotificationSetupPermissionEffect(state) {
                if (!appNotificationsAllowed()) requestNotificationAccess()
            }
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
        if (accessClick || testing && !appNotificationsAllowed()) requestNotificationAccess()
        else controller.onAction(action)
    }

    private fun runtimeNotificationPermissionGranted() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun appNotificationsAllowed() = runtimeNotificationPermissionGranted() &&
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun requestNotificationAccess() {
        if (!appNotificationsAllowed()) controller.notificationPermissionNeeded()
        val history = getSharedPreferences("notification_permission_ui", MODE_PRIVATE)
        val route = notificationPermissionRoute(Build.VERSION.SDK_INT >= 33, runtimeNotificationPermissionGranted(),
            history.getBoolean("asked", false), Build.VERSION.SDK_INT >= 33 &&
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (route == NotificationPermissionRoute.REQUEST) {
            history.edit().putBoolean("asked", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else controller.onAction(UiAction.OpenEnvironment("notifications"))
    }
}

@Composable
internal fun NotificationSetupPermissionEffect(state: UiState, onPrompt: () -> Unit) {
    var previousAutomaticEnabled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var previousOnboardingRequired by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val prompt by rememberUpdatedState(onPrompt)
    LaunchedEffect(state.busy, state.settings.masterEnabled, state.onboardingRequired) {
        if (!state.busy) {
            val completed = notificationPermissionNeededAfterSetup(previousAutomaticEnabled, previousOnboardingRequired,
                state.settings.masterEnabled, state.onboardingRequired)
            previousAutomaticEnabled = state.settings.masterEnabled
            previousOnboardingRequired = state.onboardingRequired
            if (completed) prompt()
        }
    }
}
