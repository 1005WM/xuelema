package com.homeworkassistant

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.exams.ExamsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class ExamsRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val exam = Exam("exam", "期末测试", "另行通知", null, null, "60分钟", "已结束", "未交卷", "--", "线上", HomeworkStatus.NOT_SUBMITTED)
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "bugfix-031").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun unassociatedExamsRemainVisibleAndEndedDoesNotMeanSubmitted() {
        compose.setContent { HomeworkTheme {
            ExamsScreen(ExamSnapshot("test", listOf(exam), Instant.EPOCH), false,
                "1 项考试的课程关联待确认，已保留展示，请在官方页面核对课程状态", {}, {}, {})
        } }
        compose.onNodeWithText("期末测试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("课程待确认").assertIsDisplayed()
        compose.onNodeWithText("答卷：未交卷").performScrollTo().assertIsDisplayed()
        capture("exams-unassociated")
        compose.onNodeWithText("已完成", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("期末测试").assertDoesNotExist()
    }
    @Test fun readErrorIsVisibleWithAnExistingEmptySnapshot() {
        compose.setContent { HomeworkTheme {
            ExamsScreen(ExamSnapshot("test", emptyList(), Instant.EPOCH), false,
                "考试读取未完成：登录已失效，请重新登录", {}, {}, {})
        } }
        compose.onNodeWithText("考试读取未完成：登录已失效，请重新登录").assertIsDisplayed()
        compose.onNodeWithText("此分类暂无考试").assertDoesNotExist()
        capture("exams-read-error")
    }
}
