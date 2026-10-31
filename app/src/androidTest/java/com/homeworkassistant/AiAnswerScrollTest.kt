package com.homeworkassistant

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.domain.model.Answer
import com.homeworkassistant.domain.model.Question
import com.homeworkassistant.domain.model.QuestionPage
import com.homeworkassistant.domain.model.QuestionType
import androidx.compose.ui.semantics.SemanticsProperties
import com.homeworkassistant.ui.AnswerUiState
import com.homeworkassistant.ui.detail.AiAnswerBottomSheet
import com.homeworkassistant.ui.theme.HomeworkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiAnswerScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedBottomDragsAndProgressUpdatesKeepTheListAndActionsInPlace() {
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        var state by mutableStateOf(AnswerUiState(
            answers = (1..30).map { Answer("q$it", "答案 $it", ("详细解题思路 $it。").repeat(20)) }))
        var dismissed = false
        compose.setContent { HomeworkTheme { Surface(Modifier.fillMaxSize()) {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            AiAnswerBottomSheet(state, { dismissed = true }, { _, _ -> }, {}, {}, {}, {}, listState = list)
        } } }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { list.scrollToItem(30, 100_000) } }
        compose.waitForIdle()
        assertFalse(list.canScrollForward)
        val headerTop = compose.onNodeWithText("AI 作答", substring = false).getUnclippedBoundsInRoot().top
        val lastIndex = list.firstVisibleItemIndex
        val lastOffset = list.firstVisibleItemScrollOffset
        repeat(4) {
            compose.onNodeWithTag("ai-answer-list").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals(lastIndex, list.firstVisibleItemIndex)
            assertEquals(lastOffset, list.firstVisibleItemScrollOffset)
            assertEquals(headerTop, compose.onNodeWithText("AI 作答", substring = false).getUnclippedBoundsInRoot().top)
            assertFalse(dismissed)
        }
        compose.runOnIdle { state = state.copy(message = "处理结果已更新") }
        compose.waitForIdle()
        assertFalse(list.canScrollForward)
        compose.onNodeWithText("回填页面").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭 AI 作答").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun draggingTheAnswerListReleasesAnEarlierAnswerFieldFocus() {
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent { HomeworkTheme { Surface(Modifier.fillMaxSize()) {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            AiAnswerBottomSheet(AnswerUiState(answers = (1..20).map { Answer("q$it", "答案 $it", "解析") }),
                {}, { _, _ -> }, {}, {}, {}, {}, listState = list)
        } } }
        compose.onAllNodes(hasSetTextAction())[0].performClick().assertIsFocused()
        compose.onNodeWithTag("ai-answer-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { scope.launch { list.scrollToItem(20, 100_000) } }
        compose.waitForIdle()
        val bottom = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        compose.onNodeWithTag("ai-answer-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(bottom, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        compose.onNodeWithText("回填页面").assertIsDisplayed()
    }
    @Test fun fillStepsAreShownSeparatelyFromTheFinalAnswerField() {
        val steps = "1. 根据 x + 1 = 3 列式。\n2. 两边减 1，得到 x = 2。\n3. 将 2 代回核对。"
        val question = Question("q1", 1, QuestionType.FILL, "已知 x + 1 = 3，x = ____。", emptyList())
        compose.setContent { HomeworkTheme { Surface(Modifier.fillMaxSize()) {
            AiAnswerBottomSheet(AnswerUiState(page = QuestionPage("https://mooc1.chaoxing.com/work", "fixture", listOf(question)),
                answers = listOf(Answer("q1", "2", steps))), {}, { _, _ -> }, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("解题步骤").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(steps).performScrollTo().assertIsDisplayed()
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("2", field.config[SemanticsProperties.EditableText].text)
    }

}
