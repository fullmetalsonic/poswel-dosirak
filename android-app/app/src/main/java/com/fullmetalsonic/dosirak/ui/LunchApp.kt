package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private val LightColors = lightColorScheme(
    primary = Color(0xFF00659E), primaryContainer = Color(0xFFE4F3FF), onPrimaryContainer = Color(0xFF00344F),
    secondary = Color(0xFFB44130), secondaryContainer = Color(0xFFFF745D), onSecondaryContainer = Color(0xFF29110C),
    tertiary = Color(0xFF417452), background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF20272D),
    surfaceVariant = Color(0xFFEDF0F3), onSurfaceVariant = Color(0xFF47515C), surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFE7EBF0), surfaceContainerLow = Color(0xFFF5F7F9),
    surfaceContainerHighest = Color(0xFFDEE4EA), surfaceContainerLowest = Color.White,
    outline = Color(0xFF79828B), outlineVariant = Color(0xFFD8DEE4)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BCDF5), primaryContainer = Color(0xFF003D5D), onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFFFFAD9B), secondaryContainer = Color(0xFF6D291D), onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFF93D5AA), background = Color(0xFF101214), surface = Color(0xFF121416), onSurface = Color(0xFFE2E7EB),
    surfaceVariant = Color(0xFF373D44), onSurfaceVariant = Color(0xFFB9C0C8), surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282C31), surfaceContainerLow = Color(0xFF181B1E),
    surfaceContainerHighest = Color(0xFF30363C), surfaceContainerLowest = Color(0xFF0D0F11),
    outline = Color(0xFF8C969F), outlineVariant = Color(0xFF424951)
)

@Composable
fun LunchApp(state: UiState, onAction: (UiAction) -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsRequest by rememberSaveable { mutableIntStateOf(0) }
        val tabState = rememberSaveableStateHolder()
        LaunchedEffect(state.siteRequest) { if (state.siteRequest > 0) tab = 1 }
        val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        Scaffold(
            topBar = { AppHeader(if (tab == 0) "포스웰도시락예약" else if (tab == 1) "포스웰 사이트" else "설정") },
            bottomBar = {
                if (!imeVisible) NavigationBar {
                    listOf("달력", "포스웰 사이트", "설정").forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(when (index) { 0 -> Icons.Outlined.CalendarMonth; 1 -> Icons.Outlined.Language; else -> Icons.Outlined.Settings }, title) }, label = { Text(title) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> tabState.SaveableStateProvider("calendar") { CalendarScreen(state, onSelectDate = { selectedDate = it.toString() }, onSettings = { settingsRequest++; tab = 2 }) }
                    1 -> tabState.SaveableStateProvider("site") { SiteScreen(state, onAction) }
                    else -> tabState.SaveableStateProvider("settings") { SettingsScreen(state, onAction, onDate = { selectedDate = it.toString() }, onSite = { tab = 1 }, onCalendar = { tab = 0 }, entryRequest = settingsRequest) }
                }
            }
        }
        selectedDate?.let { date ->
            DateEditor(state, LocalDate.parse(date), onAction, onDismiss = { selectedDate = null }, onSite = { path -> onAction(UiAction.OpenSite(path)); tab = 1; selectedDate = null }, onSettings = { selectedDate = null; settingsRequest++; tab = 2 })
        }
        state.message?.let { message ->
            AlertDialog(onDismissRequest = { onAction(UiAction.ClearMessage) }, title = { Text("처리 결과") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { onAction(UiAction.ClearMessage) }) { Text("확인") } })
        }
    }
}

@Composable
private fun AppHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Text(title, modifier = Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
    }
}
