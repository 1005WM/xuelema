package com.homeworkassistant

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.AnswerUiState
import com.homeworkassistant.ui.detail.AiAnswerBottomSheet
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UiBugRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun numericBudgetCanBeClearedAndReplacedWithoutSavingAnOldValue() {
        var saved: AppSettings? = null
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(), { "" }, { settings, _ -> saved = settings }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("每批输入字符预算（保守估算）").performScrollTo().performTextClearance()
        compose.onNodeWithText("保存 AI 设置").assertIsNotEnabled()
        compose.onNodeWithText("每批输入字符预算（保守估算）").performTextInput("25000")
        compose.onNodeWithText("保存 AI 设置").performClick()
        compose.runOnIdle { assertEquals(25000, saved?.inputBudgetChars) }
    }

    @Test fun outOfRangeTokensCannotBeSilentlySaved() {
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("每批输出 Token 上限").performScrollTo().performTextReplacement("1")
        compose.onNodeWithText("保存 AI 设置").assertIsNotEnabled()
        compose.onNodeWithText("请输入 256–16384 的整数").performScrollTo().assertIsDisplayed()
    }

    @Test fun answersCannotChangeDuringAnInFlightFill() {
        compose.setContent { HomeworkTheme { Surface(Modifier.fillMaxSize()) {
            AiAnswerBottomSheet(AnswerUiState(busy = true, answers = listOf(Answer("q1", "A", ""))), {}, { _, _ -> }, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("可编辑答案").assertIsNotEnabled()
        compose.onNodeWithText("原文分屏对照").assertIsNotEnabled()
    }

    @Test fun fillErrorIsVisibleEvenWhenReadingALaterQuestion() {
        var state by mutableStateOf(AnswerUiState(answers = (1..20).map { Answer("q$it", "答案 $it", "") }))
        compose.setContent { HomeworkTheme { Surface(Modifier.fillMaxSize()) {
            AiAnswerBottomSheet(state, {}, { _, _ -> }, {}, {}, {}, {})
        } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        compose.runOnIdle { state = state.copy(error = "回填超时，请检查已填写的部分") }
        compose.onNodeWithText("回填超时，请检查已填写的部分").assertIsDisplayed()
    }
}
