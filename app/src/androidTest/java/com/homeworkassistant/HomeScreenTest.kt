package com.homeworkassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.ui.home.*
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun courseChipSelectsFilterAndOverviewStaysVisible() {
        var selection: String? = null
        compose.setContent { HomeworkTheme {
            OverviewHeader(PreviewData.content.summary, PreviewData.now, false, false, false, null, {}, { selection = it })
        } }
        compose.onNodeWithText("高等数学 · 2", substring = true).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("math", selection) }
        compose.onAllNodesWithText("3", useUnmergedTree = true).onFirst().assertIsDisplayed()
    }
    @Test fun scanDiagnosticIsNotPermanentlyInsertedAboveTheList() {
        compose.setContent { HomeworkTheme {
            HomeScreen(PreviewData.state.copy(error = "扫描结果诊断", needsLogin = true), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("扫描结果诊断").assertDoesNotExist()
        compose.onNodeWithText("登录已失效，重新登录").performScrollTo().assertIsDisplayed()
    }
}
