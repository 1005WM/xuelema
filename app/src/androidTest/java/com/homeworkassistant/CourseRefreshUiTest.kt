package com.homeworkassistant

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CourseRefreshUiTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(250)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "course-cache-037").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun refreshMenuUsesDistinctCallbacksAndShowsLastSuccessfulCourseRead() {
        var normal = 0; var current = 0; var force = 0
        val state = PreviewData.state.copy(currentCourseId = "math", aiConfigured = true, cacheLabel = true,
            courseCache = mapOf("math" to CourseCacheInfo(PreviewData.now)), settings = AppSettings(compact = true))
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            HomeScreen(state, { normal++ }, {}, {}, {}, {}, {}, onRefreshCurrent = { current++ }, onForceRefresh = { force++ })
        } }
        compose.onNodeWithText("当前课程 · 本地缓存 · 上次成功 10月28日 10:24").assertExists()
        compose.onNodeWithContentDescription("刷新选项").performClick()
        capture("refresh-options")
        compose.onNodeWithText("刷新当前课程").performClick()
        compose.onNodeWithContentDescription("刷新选项").performClick()
        compose.onNodeWithText("强制全量刷新").performClick()
        compose.onNodeWithContentDescription("刷新选项").performClick()
        compose.onNodeWithText("刷新过期课程").performClick()
        compose.runOnIdle { assertEquals(1, normal); assertEquals(1, current); assertEquals(1, force) }
    }
    @Test fun pauseKeepsCachedCardsVisibleAndProvidesExplicitVerificationResume() {
        var resumed = false
        val state = PreviewData.state.copy(error = "官方要求人机验证，已暂停请求", currentCourseId = "math", aiConfigured = true,
            courseCache = mapOf("math" to CourseCacheInfo(PreviewData.now, "验证暂停", true)), settings = AppSettings(compact = true))
        compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
            HomeScreen(state, {}, {}, {}, {}, {}, {}, onResumeReads = { resumed = true })
        } }
        compose.onNodeWithText("第三章 · 微分与应用").assertExists()
        compose.onNodeWithText("验证后重试").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(resumed) }
        capture("paused-cache")
    }
}
