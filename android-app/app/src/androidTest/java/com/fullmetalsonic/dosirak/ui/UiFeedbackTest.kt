package com.fullmetalsonic.dosirak.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class UiFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulReservationUsesNonBlockingNotice() {
        compose.setContent {
            LunchApp(UiState(message = "예약을 저장했습니다.", transientMessage = true), {})
        }
        compose.onNodeWithText("처리 결과").assertDoesNotExist()
        compose.onNodeWithText("예약을 저장했습니다.").assertExists()
        compose.onNodeWithContentDescription("설정", useUnmergedTree = true).assertExists()
    }

    @Test fun importantOrderErrorRemainsProminent() {
        compose.setContent {
            LunchApp(UiState(message = "주문 결과를 확인해야 합니다."), {})
        }
        compose.onNodeWithText("처리 결과").assertExists()
        compose.onNodeWithText("주문 결과를 확인해야 합니다.").assertExists()
    }
}
