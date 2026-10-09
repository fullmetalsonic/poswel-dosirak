package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppVersionSettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun render(state: UiState, onAction: (UiAction) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppVersionSettings(state, onAction)
                }
            }
        }
    }

    @Test fun showsInstalledAndLatestVersionAndDispatchesOnlyUserActions() {
        val actions = mutableListOf<UiAction>()
        render(UiState(currentVersion = "0.1.6-test", updateStatus = UpdateStatus(latestVersion = "0.1.7",
            available = true, message = "새 버전이 있습니다. 배포 페이지에서 확인하세요.")), actions::add)
        compose.onNodeWithTag("app_current_version").assertTextEquals("0.1.6-test")
        compose.onNodeWithTag("app_latest_version").assertTextEquals("0.1.7")
        compose.onNodeWithTag("app_update_status").assertTextContains("새 버전이 있습니다.", substring = true)
        compose.onNodeWithTag("app_check_update").performClick()
        compose.onNodeWithTag("app_open_release").performClick()
        assertEquals(listOf(UiAction.CheckUpdate, UiAction.OpenRelease), actions)
    }

    @Test fun checkingDisablesDuplicateChecksButKeepsReleasePageAvailable() {
        render(UiState(currentVersion = "0.1.6-test", updateStatus = UpdateStatus(checking = true)))
        compose.onNodeWithTag("app_check_update").assertIsNotEnabled()
        compose.onNodeWithTag("app_open_release").assertIsEnabled()
        compose.onNodeWithTag("app_update_status").assertTextEquals("새 버전을 확인하고 있습니다.")
    }

    @Test fun privateRepositoryMessageStillAllowsManualRetry() {
        render(UiState(currentVersion = "0.1.6-test", updateStatus = UpdateStatus(
            message = "저장소가 비공개이거나 공개된 정식 배포가 없어 확인할 수 없습니다.")))
        compose.onNodeWithTag("app_latest_version").assertTextEquals("확인 전")
        compose.onNodeWithTag("app_check_update").assertIsEnabled()
        compose.onNodeWithTag("app_open_release").assertIsEnabled()
    }

    @Test fun bundledPrivacyAndNoticesOpenWithoutNetworkActions() {
        val actions = mutableListOf<UiAction>()
        render(UiState(currentVersion = "0.1.10-test"), actions::add)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("app_privacy" to "privacy.txt", "app_notices" to "third_party_notices.txt").forEach { (tag, file) ->
            val expected = context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue(expected.isNotBlank())
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag("app_document_body").assertTextEquals(expected)
            compose.onNodeWithText("닫기").performClick()
            compose.onNodeWithTag("app_document_body").assertDoesNotExist()
        }
        assertTrue(actions.isEmpty())
    }
}
