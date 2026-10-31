package com.homeworkassistant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.AnswerUiState
import com.homeworkassistant.ui.detail.AiAnswerBottomSheet
import com.homeworkassistant.ui.exams.ExamsScreen
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Actual Android rendering with synthetic data; never logs in or calls an AI service. */
class DesignReviewTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // UiAutomation reads the display, which can lag Compose semantics by a frame.
        android.os.SystemClock.sleep(200)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun homeAndFilters() {
        var filter by mutableStateOf(HomeFilter())
        var settingsOpened = false
        var examsOpened = false
        var officialOpened = false
        var homeworkOpened = false
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            val content = BuildHomeContentUseCase()(PreviewData.snapshot, filter, PreviewData.now, ZoneId.of("Asia/Shanghai"))
            HomeScreen(PreviewData.state.copy(content = content, filter = filter), {}, { filter = it }, { homeworkOpened = true },
                { settingsOpened = true }, {}, { officialOpened = true }, onExams = { examsOpened = true })
        } }
        capture("home-light")
        compose.onNodeWithText("高等数学 · 2").performClick()
        compose.runOnIdle { assertEquals("math", filter.courseId) }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithContentDescription("打开官方页面").performClick()
        compose.onNodeWithText("考试").performClick()
        compose.runOnIdle { assertTrue(settingsOpened && examsOpened && officialOpened) }
        compose.onNodeWithText("第二章课后练习").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(homeworkOpened) }
        compose.onNodeWithText("已完成", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(filter.completed) }
        capture("home-filtered-empty")
    }

    @Test fun darkHome() {
        compose.setContent { HomeworkTheme(ThemeMode.DARK) { HomeScreen(PreviewData.state, {}, {}, {}, {}, {}, {}) } }
        capture("home-dark")
    }

    @Test fun lightHome() {
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) { HomeScreen(PreviewData.state, {}, {}, {}, {}, {}, {}) } }
        capture("home-light")
    }

    @Test fun settingsAndAppearance() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { HomeworkTheme(theme) {
            SettingsScreen(AppSettings(theme = theme), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {},
                onAppearanceChange = { theme = it.theme })
        } }
        capture("settings-appearance")
        compose.onNodeWithText("深色").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, theme) }
        compose.onNodeWithText("浅色").performClick()
        compose.onNodeWithText("模型（下拉选择或手动填写）").performScrollTo().assertIsDisplayed()
        capture("settings-ai")
    }

    @Test fun exams() {
        val exam = Exam("demo", "高等数学 · 阶段测验", "10月30日 14:00—15:30", PreviewData.now.plusSeconds(172800), null,
            "90 分钟", "未开始", "未交卷", "", "在线考试", HomeworkStatus.NOT_SUBMITTED, courseName = "高等数学")
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) { ExamsScreen(ExamSnapshot("preview", listOf(exam), PreviewData.now), false, null, {}, {}, {}) } }
        capture("exams")
        compose.onNodeWithText("已完成").performClick()
        compose.onNodeWithText("此分类暂无考试").assertExists()
    }

    @Test fun answerSheet() {
        val questions = listOf(
            Question("q1", 1, QuestionType.SINGLE, "下列哪一项是函数在某点可导的必要条件？", listOf("A. 在该点连续", "B. 在该点取得极值")),
            Question("q2", 2, QuestionType.SHORT, "简述导数的几何意义。", emptyList()))
        val answers = listOf(Answer("q1", "A", "函数在某点可导，则函数在该点一定连续。"), Answer("q2", "导数表示曲线在该点切线的斜率。", "从割线的极限理解切线。"))
        var filled = false
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) { Surface(Modifier.fillMaxSize()) {
            AiAnswerBottomSheet(AnswerUiState(page = QuestionPage("https://mooc1.chaoxing.com", "preview", questions), answers = answers,
                fillFeedback = mapOf("q1" to "已回填", "q2" to "未回填：答案框未就绪，请稍后重试")), {}, { _, _ -> }, { filled = true }, {}, {}, {})
        } } }
        compose.onNodeWithText("回填页面").assertIsDisplayed()
        capture("ai-answers")
        compose.onNodeWithText("回填页面").performClick()
        compose.runOnIdle { assertTrue(filled) }
        compose.onNodeWithText("已确认 1 / 2 题").assertIsDisplayed()
        compose.onNodeWithContentDescription("查看待处理题目").performClick()
        compose.onNodeWithText("未回填：答案框未就绪，请稍后重试").assertIsDisplayed()
        capture("ai-feedback")
    }
}
