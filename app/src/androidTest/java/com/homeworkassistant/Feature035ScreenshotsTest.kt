package com.homeworkassistant

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.exams.ExamsScreen
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Real emulator screenshots of production composables; all course data is synthetic. */
class Feature035ScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "feature-035").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun homeworkDeadlines() {
        val now = Instant.parse("2026-10-30T02:24:00Z")
        val items = listOf(
            PreviewData.items[0].copy(dueAt = now.plusSeconds(36000)),
            PreviewData.items[2].copy(dueAt = null, dueText = "11-02 23:59（年份待确认）"))
        val snapshot = PreviewData.snapshot.copy(items = items, fetchedAt = now)
        val content = BuildHomeContentUseCase()(snapshot, HomeFilter(), now, ZoneId.of("Asia/Shanghai"))
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            HomeScreen(PreviewData.state.copy(snapshot = snapshot, content = content,
                settings = AppSettings(compact = true), aiConfigured = true), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("截止 11-02 23:59（年份待确认）").performScrollTo().assertIsDisplayed()
        capture("homework-deadlines")
    }

    @Test fun examEntry() {
        val now = Instant.parse("2026-10-30T02:24:00Z")
        val exam = Exam("demo", "高等数学 · 阶段测验", "10月30日 14:00—15:30", now.plusSeconds(12800), null,
            "90 分钟", "未开始", "未交卷", "", "在线考试", HomeworkStatus.NOT_SUBMITTED,
            courseName = "高等数学", detailUrl = "https://mooc1.chaoxing.com/exam-ans/exam/test?examId=demo")
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            ExamsScreen(ExamSnapshot("preview", listOf(exam), now), false, null, {}, {}, {})
        } }
        compose.onNodeWithText("点击进入本场考试 · 支持 AI 作答").assertIsDisplayed()
        capture("exam-entry")
    }

    @Test fun fillSpeed() {
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            SettingsScreen(AppSettings(), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("较快 0.5 秒 — 较慢 5 秒，默认 1.5 秒。按此间隔逐题回填，页面响应较慢时会等待更久。修改对下一次回填生效，可随时停止。")
            .performScrollTo().assertIsDisplayed()
        capture("fill-speed")
    }
}
