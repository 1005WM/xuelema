package com.homeworkassistant

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.theme.HomeworkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Exercise HTML parsing -> enrichment -> Room -> rendered home, without pre-seeding deadlines. */
class DeadlineReadFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reportedOfficialTimeReplacesPendingPlaceholderAndSurvivesReload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        try {
            val repo = HomeworkRepositoryImpl(db)
            val now = Instant.parse("2026-10-30T11:10:00Z")
            val items = listOf(
                Homework("11:22:1", Course("11:22", "大学物理（2）"), "第十章思维导图",
                    "https://mooc1.chaoxing.com/work/task?workId=1", status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交"),
                Homework("33:44:2", Course("33:44", "数据库原理与应用"), "作业2—数据库设计",
                    "https://mooc1.chaoxing.com/work/task?workId=2", status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交"))
            val initial = HomeworkSnapshot("one", 1, items, now, SnapshotCoverage.COMPLETE, 2, 2)
            repo.publish(initial)
            compose.setContent { HomeworkTheme(ThemeMode.LIGHT) {
                val snapshot by repo.observe("one").collectAsState(initial = initial)
                HomeScreen(PreviewData.state.copy(accountId = "one", snapshot = snapshot,
                    content = snapshot?.let { BuildHomeContentUseCase()(it, HomeFilter(), now, ZoneId.of("Asia/Shanghai")) },
                    settings = AppSettings(compact = true), aiConfigured = true), {}, {}, {}, {}, {}, {})
            } }
            compose.onAllNodesWithText("截止 待获取").assertCountEquals(2)
            val reader = McpChaoxingReader(McpReadTransport { url ->
                OfficialHtml(url, if (url.contains("/work/task")) "<iframe src='https://mooc1.chaoxing.com/work/view?workId=${url.substringAfter("workId=")}'></iframe>"
                    else "<div>作答 <span>时间：</span>10-27 11：50 至11-01 23：59</div>")
            })
            val result = reader.enrich(McpHomeworkRead(items, 2, 2, true, 0, 0, emptyList()), accountId = "one",
                onUpdated = { repo.updateDetails("one", it.items) })
            assertEquals(0, result.value.detailsUnavailable)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("截止 11-01 23:59（年份待确认）").fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithText("截止 待获取").assertCountEquals(0)
            val reloaded = HomeworkRepositoryImpl(db).observe("one").first()!!
            assertTrue(reloaded.items.all { it.dueText == "11-01 23:59（年份待确认）" && it.dueAt == null && it.status == HomeworkStatus.NOT_SUBMITTED })
            compose.onNodeWithText("作业2—数据库设计").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(250)
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "deadline-039").apply { mkdirs() }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "homework-deadline.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { db.close() }
    }
}
