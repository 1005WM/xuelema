package com.homeworkassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.guide.GuideScreen
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.home.HomeUiState
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GuideScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nextPreviousAndSkipNeverFinishEarly() {
        var exits = 0
        compose.setContent { HomeworkTheme { GuideScreen({ exits++ }) } }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一页").performClick()
        compose.onNodeWithText("还有什么没写，\n一眼看清").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithText("跳过").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }
    @Test fun completionIsOnlyAvailableOnTheLastPage() {
        var exits = 0
        compose.setContent { HomeworkTheme { GuideScreen({ exits++ }) } }
        compose.onNodeWithText("开始使用").assertDoesNotExist()
        repeat(2) { compose.onNodeWithText("下一步").performClick() }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithText("核对完成，再提交").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithText("开始使用").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }
    @Test fun smallScreenLargeTextAndDarkModeKeepActionsVisibleAndCopyScrollable() {
        compose.setContent { HomeworkTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.requiredSize(320.dp, 568.dp)) { GuideScreen({}, initialPage = 1) }
            }
        } }
        compose.onNodeWithText("下一步").assertIsDisplayed()
        compose.onNodeWithText("跳过").assertIsDisplayed()
        compose.onNodeWithText("AI 答案仅供参考，请结合课程内容核对。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一页").assertIsDisplayed()
    }
    @Test fun saveFailureKeepsTheGuideAndSavingPreventsDuplicateFinishes() {
        compose.setContent { HomeworkTheme { GuideScreen({}, saving = true, error = "保存失败，请重试", initialPage = 2) } }
        compose.onNodeWithText("跳过").assertIsNotEnabled()
        compose.onNodeWithText("正在保存…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        compose.onNodeWithText("保存失败，请重试").performScrollTo().assertIsDisplayed()
    }
    @Test fun settingsOffersGuideAndGuestHomeOffersLoginWithoutRequiringAi() {
        var guides = 0
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, onGuide = { guides++ })
        } }
        compose.onNodeWithText("使用指南").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, guides) }
    }
    @Test fun guestHomeHasUsefulLoginAndOptionalSettingsEntrances() {
        var logins = 0; var settings = 0
        compose.setContent { HomeworkTheme {
            HomeScreen(HomeUiState(initialized = true), {}, {}, {}, { settings++ }, { logins++ }, {})
        } }
        compose.onNodeWithText("登录学习通").performScrollTo().performClick()
        compose.onNodeWithText("查看设置与使用指南").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, logins); assertEquals(1, settings) }
    }
    @Test fun aiSetupCanBeDeferredWithoutBlockingTheHomeworkList() {
        var settings = 0
        compose.setContent { HomeworkTheme {
            HomeScreen(PreviewData.state.copy(aiConfigured = false), {}, {}, {}, { settings++ }, {}, {})
        } }
        compose.onNodeWithText("稍后").performScrollTo().performClick()
        compose.onNodeWithText("配置 AI").assertDoesNotExist()
        compose.onNodeWithText("第二章课后练习").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settings) }
    }
}
