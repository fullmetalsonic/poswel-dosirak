package com.fullmetalsonic.dosirak.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

@Composable
internal fun SettingsScreen(state: UiState, onAction: (UiAction) -> Unit, onDate: (LocalDate) -> Unit,
    onSite: () -> Unit, onCalendar: () -> Unit = {}, entryRequest: Int = 0) {
    var draft by rememberSaveable(stateSaver = SettingsDraftSaver) { mutableStateOf(state.settings) }
    var anchor by rememberSaveable { mutableStateOf(state.settings.patternAnchor.toString()) }
    var quantity by rememberSaveable { mutableStateOf(state.settings.defaultQuantity.toString()) }
    var time by rememberSaveable { mutableStateOf(state.settings.orderTime.format(TimeFormat)) }
    var p by rememberSaveable { mutableStateOf(state.settings.unitLimit?.toString() ?: "") }
    var c by rememberSaveable { mutableStateOf(state.settings.orderLimit?.toString() ?: "") }
    var retries by rememberSaveable { mutableStateOf(state.settings.retryCount.toString()) }
    var interval by rememberSaveable { mutableStateOf(state.settings.retryIntervalSeconds.toString()) }
    var observedGeneration by rememberSaveable { mutableLongStateOf(state.settings.generation) }
    var page by rememberSaveable { mutableStateOf("HOME") }
    var wizardStep by rememberSaveable { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var reviewBlock by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<AppSettings?>(null) }
    var saveRequestCounter by rememberSaveable { mutableLongStateOf(System.nanoTime()) }
    var pendingRequestId by remember { mutableLongStateOf(0) }
    var credentialsDirty by remember { mutableStateOf(false) }
    var pendingSection by remember { mutableStateOf<SettingsSection?>(null) }
    var completingOnboarding by remember { mutableStateOf(false) }
    var saveFailureMessage by remember { mutableStateOf<String?>(null) }
    var savedLabel by remember { mutableStateOf<String?>(null) }
    var reviewState by remember { mutableStateOf<UiState?>(null) }
    var snapshot by remember { mutableStateOf<ActivationSnapshot?>(null) }
    var changedDuringReview by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val wizard = state.onboardingRequired
    val wizardSections = listOf(SettingsSection.ACCOUNT, SettingsSection.WORK, SettingsSection.ORDER, SettingsSection.PHONE)
    val section = if (wizard) wizardSections.getOrNull(wizardStep) else SettingsSection.entries.firstOrNull { it.name == page }
    val candidate = draft.copy(patternAnchor = parseDate(anchor) ?: draft.patternAnchor,
        defaultQuantity = quantity.toIntOrNull() ?: 0, orderTime = parseTime(time) ?: draft.orderTime,
        unitLimit = p.toLongOrNull(), orderLimit = c.toLongOrNull(), retryCount = retries.toIntOrNull() ?: -1,
        retryIntervalSeconds = interval.toIntOrNull() ?: 0)
    val localSettings = section?.let { sectionSettings(state.settings, candidate, it) } ?: candidate
    val completionError = when {
        parseDate(anchor) == null -> "근무 기준일을 확인해 주세요."
        parseTime(time) == null -> "신청 시각을 HH:mm:ss로 입력해 주세요."
        else -> ReservationLimits.validate(candidate.copy(limitEnabled = false))
    }
    val error = when (section) {
        SettingsSection.WORK -> if (parseDate(anchor) == null) "근무 기준일을 확인해 주세요." else null
        SettingsSection.ORDER -> when {
            parseTime(time) == null -> "신청 시각을 HH:mm:ss로 입력해 주세요."
            else -> ReservationLimits.validate(localSettings.copy(limitEnabled = state.settings.masterEnabled && localSettings.limitEnabled))
        }
        null -> if (wizard) completionError else null
        else -> null
    }
    val dirty = (section != null || wizard) && (localSettings.editableOnly() != state.settings.editableOnly() || error != null || (section == SettingsSection.ACCOUNT && credentialsDirty))
    val activationSettings = state.settings.copy(masterEnabled = false, weekdays = state.settings.weekdays.toSet())
    val issues = setupIssues(activationSettings, state)
    fun syncSaved(selected: SettingsSection?) {
        draft = if (selected == null) state.settings else sectionSettings(draft, state.settings, selected)
        if (selected == null || selected == SettingsSection.WORK) anchor = state.settings.patternAnchor.toString()
        if (selected == null || selected == SettingsSection.ORDER) {
            quantity = state.settings.defaultQuantity.toString(); time = state.settings.orderTime.format(TimeFormat)
            p = state.settings.unitLimit?.toString() ?: ""; c = state.settings.orderLimit?.toString() ?: ""
            retries = state.settings.retryCount.toString(); interval = state.settings.retryIntervalSeconds.toString()
        }
        observedGeneration = state.settings.generation
    }
    fun navigateBack() {
        focus.clearFocus(); keyboard?.hide()
        if (dirty) discard = true else page = "HOME"
    }
    fun saveSettings(finish: Boolean = false) {
        focus.clearFocus(); keyboard?.hide()
        (if (finish) completionError else error)?.let { saveFailureMessage = it; return }
        val selected = if (finish) null else section
        val saved = if (selected == null) candidate.copy(masterEnabled = state.settings.masterEnabled) else sectionSettings(state.settings, candidate, selected)
        pendingSave = saved; pendingSection = selected; completingOnboarding = finish
        saveRequestCounter++
        pendingRequestId = saveRequestCounter
        saveFailureMessage = null; savedLabel = "저장 중"
        onAction(UiAction.SaveSettings(saved, state.settings.generation, requestId = pendingRequestId))
    }
    fun openActivation() {
        focus.clearFocus(); keyboard?.hide()
        if (issues.isNotEmpty()) { showIssues = true; return }
        snapshot = ActivationSnapshot(activationSettings, state.settings.generation, state.settings.accountGeneration, state.accountLabel)
        reviewState = state.copy(settings = activationSettings, overrides = state.overrides.toMap(),
            records = state.records.toList(), environment = state.environment.toList())
        changedDuringReview = false
    }
    LaunchedEffect(entryRequest) { if (entryRequest > 0 && !wizard) page = "HOME" }
    LaunchedEffect(state.settings.generation, state.settings.accountGeneration, state.settingsSaveResult, state.busy, pendingRequestId) {
        pendingSave?.let { requested ->
            val result = state.settingsSaveResult?.takeIf { it.requestId == pendingRequestId }
            if (!state.busy && result?.success == true) {
                val currentInput = pendingSection?.let { sectionSettings(requested, candidate, it) } ?: candidate
                if (currentInput.editableOnly() == requested.editableOnly()) { syncSaved(pendingSection); savedLabel = "설정 저장됨" }
                else { observedGeneration = state.settings.generation; savedLabel = "설정 저장됨 · 추가 편집 내용 유지" }
                pendingSave = null
            } else if (!state.busy && result?.success == false) {
                pendingSave = null; completingOnboarding = false; savedLabel = "저장하지 못했습니다. 입력값은 유지됩니다."
                saveFailureMessage = result.message
            }
        }
        if (completingOnboarding && pendingSave == null && savedLabel?.startsWith("설정 저장됨") == true && !state.busy) {
            completingOnboarding = false; page = "HOME"; onAction(UiAction.CompleteOnboarding)
        }
        snapshot?.let { held ->
            if (held.expectedGeneration != state.settings.generation || held.expectedAccountGeneration != state.settings.accountGeneration) {
                snapshot = null; reviewState = null; changedDuringReview = true
            }
        }
        if (state.settings.generation != observedGeneration && pendingSave == null && !dirty) syncSaved(section)
    }
    BackHandler(enabled = !wizard && page != "HOME") { navigateBack() }
    val scroll = rememberScrollState()
    LaunchedEffect(page, wizardStep) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (wizard) {
            Text("처음 설정", style = MaterialTheme.typography.titleLarge)
            Text("${wizardStep + 1} / 5 · ${section?.title ?: "완료"}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { (wizardStep + 1) / 5f }, modifier = Modifier.fillMaxWidth())
            if (wizardStep == 0) {
                OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); onAction(UiAction.ImportBackup) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("onboarding_import_backup")) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("백업 가져오기")
                }
            }
        } else if (section != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { navigateBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "설정 목록으로") }
                Text(section.title, style = MaterialTheme.typography.titleLarge)
            }
        } else {
            ToggleRow("자동주문", state.settings.masterEnabled, { enabled ->
                if (enabled) openActivation() else onAction(UiAction.StopAutomatic)
            }, enabled = state.settings.masterEnabled || !state.busy)
            state.registrationMessage?.let {
                Text(it, color = if (state.registrationProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.settings.liveBlockedReason?.let {
                Text(friendlyBlockReason(it), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { page = SettingsSection.PHONE.name }) { Text("휴대폰 설정에서 확인") }
            }
            if (changedDuringReview) Text("설정이 바뀌었습니다. 다시 확인해 주세요.", color = MaterialTheme.colorScheme.error)
            if (state.busy && !state.settings.masterEnabled) OutlinedButton(onClick = { onAction(UiAction.StopAutomatic) }) { Text("자동주문 중지") }
            SettingsCategoryList(state) { selected -> page = selected.name; savedLabel = null; saveFailureMessage = null }
        }
        if (section != null || wizard) {
            if (dirty) Text("저장하지 않은 변경", style = MaterialTheme.typography.labelMedium)
            savedLabel?.let { Text(it, color = if (it.startsWith("저장하지")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            saveFailureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.settings.generation != observedGeneration && pendingSave == null) {
                Text("저장된 설정이 변경되었습니다. 입력값은 유지됩니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { syncSaved(section); savedLabel = null }) { Text("저장된 설정 다시 불러오기") }
            }
            when (section) {
                SettingsSection.ACCOUNT -> AccountSettings(state, onAction, onSite, draft.useStoredCredentials,
                    { draft = draft.copy(useStoredCredentials = it) }, { credentialsDirty = it })
                SettingsSection.WORK -> WorkSetupScreen(candidate, anchor, { anchor = it; draft = draft.copy(patternConfirmed = false) }, { draft = it })
                SettingsSection.ORDER -> OrderSettings(candidate, quantity, time, p, c, retries, interval,
                    { quantity = it }, { time = it }, { p = it }, { c = it }, { retries = it }, { interval = it }, { draft = it })
                SettingsSection.ALERTS -> AlertSettings(state, candidate, { draft = it }, onAction)
                SettingsSection.AUTOMATION -> BackgroundSettings(state, candidate, { draft = it }, onAction)
                SettingsSection.APP -> AppVersionSettings(state, onAction)
                SettingsSection.PHONE -> EnvironmentSetupScreen(state, candidate, { draft = it }, onAction, { reviewBlock = true })
                SettingsSection.BACKUP -> {
                    OutlinedButton(onClick = { onAction(UiAction.ExportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("설정 백업 내보내기") }
                    OutlinedButton(onClick = { onAction(UiAction.ImportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("백업 가져오기") }
                    Text("근무표·예약만 백업합니다. 계정·주문기록 제외.", style = MaterialTheme.typography.bodySmall)
                }
                null -> if (wizard) {
                    Text("설정 후 자동주문을 켜세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (wizard) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (wizardStep > 0) OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); wizardStep-- }, modifier = Modifier.weight(1f)) { Text("이전") }
                    if (wizardStep < 4) Button(onClick = { focus.clearFocus(); keyboard?.hide(); wizardStep++ }, modifier = Modifier.weight(1f)) { Text("다음") }
                    else Button(onClick = { saveSettings(true) }, enabled = !state.busy && pendingSave == null && completionError == null, modifier = Modifier.weight(1f)) { Text("설정 완료") }
                }
            } else if (section != SettingsSection.BACKUP && section != SettingsSection.APP) {
                Button(onClick = { saveSettings() }, enabled = error == null && !state.busy && pendingSave == null, modifier = Modifier.fillMaxWidth()) { Text("저장") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("변경 내용을 버릴까요?") },
        confirmButton = { TextButton(onClick = { syncSaved(section); credentialsDirty = false; discard = false; page = "HOME" }) { Text("변경 버리기") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
    if (showIssues) AlertDialog(onDismissRequest = { showIssues = false }, title = { Text("자동주문 전에 확인") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            issues.forEach { issue -> TextButton(onClick = {
                showIssues = false
                if (issue.step < 0) onCalendar() else page = listOf(SettingsSection.ACCOUNT, SettingsSection.WORK, SettingsSection.ORDER, SettingsSection.PHONE)[issue.step].name
            }, modifier = Modifier.fillMaxWidth()) { Text(issue.label) } }
        }
    }, confirmButton = { TextButton(onClick = { showIssues = false }) { Text("닫기") } })
    snapshot?.let { held -> reviewState?.let { fixed ->
        val resume = fixed.settings.displayPriceRiskAccepted && fixed.settings.liveScope == LiveScope.RECURRING
        RecurringConsentDialog(held, fixed, onDismiss = { snapshot = null; reviewState = null }, onConfirm = {
            onAction(UiAction.SaveAndArmRecurring(held, acceptedPriceRisk = true)); snapshot = null; reviewState = null
        }, previouslyApproved = resume)
    } }
    if (reviewBlock) state.settings.liveBlockedReason?.let { reason ->
        BlockReviewDialog(reason, !state.busy, { reviewBlock = false }, { onAction(UiAction.ReviewBlock(true)); reviewBlock = false })
    }
}
