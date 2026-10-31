package com.homeworkassistant

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.security.SecretStore
import com.homeworkassistant.data.session.CookieCoordinator
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import com.homeworkassistant.data.web.WebSessionController
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.AppViewModel
import com.homeworkassistant.ui.components.StartupHomeworkRefresh
import androidx.compose.material3.Text
import com.homeworkassistant.ui.home.*
import com.homeworkassistant.ui.theme.HomeworkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrl

class HomeworkStatusUpdateTest {
    @get:Rule val compose = createComposeRule()
    private val a = Homework("11:22:71", Course("11:22", "数学"), "自动完成练习", "https://mooc1.chaoxing.com/work?workId=71",
        status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
    private val b = a.copy(id = "44:55:72", title = "另一门待完成", course = Course("44:55", "英语"))
    private val seed = HomeworkSnapshot("one", 1, listOf(a, b), Instant.EPOCH, SnapshotCoverage.COMPLETE, 2, 2)
    private fun database() = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()

    @Test fun submissionUpdatesHomeTotalsAndCompletedTabWithoutPressingRefresh() {
        val db = database()
        try {
            val repo = HomeworkRepositoryImpl(db)
            runBlocking { repo.publish(seed) }
            var filter by mutableStateOf(HomeFilter())
            var refreshes = 0
            compose.setContent { HomeworkTheme {
                val snapshot by remember { repo.observe("one") }.collectAsState(initial = seed)
                val value = requireNotNull(snapshot)
                HomeScreen(HomeUiState(initialized = true, accountId = "one", snapshot = value, filter = filter,
                    content = BuildHomeContentUseCase()(value, filter, Instant.now(), ZoneId.of("Asia/Shanghai"))),
                    { refreshes++ }, { filter = it }, {}, {}, {}, {})
            } }
            compose.onNodeWithText("已完成 0").assertIsDisplayed()
            runBlocking { assertEquals(1, repo.updateStatuses("one", listOf(a.copy(status = HomeworkStatus.SUBMITTED, rawStatus = "待批阅")))) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已完成 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("已完成 1").assertIsDisplayed()
            compose.onNodeWithText("数学 · 1").assertDoesNotExist()
            compose.onNodeWithText("自动完成练习").assertDoesNotExist()
            compose.onNodeWithText("已完成", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("自动完成练习").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, refreshes) }
            runBlocking {
                val saved = repo.observe("one").first()!!
                assertEquals(1, saved.items.count { it.status.isPending })
                assertEquals(1, saved.items.count { it.status.isCompleted })
                assertEquals(Instant.EPOCH, saved.fetchedAt)
            }
        } finally { db.close() }
    }
    @Test fun atomicStatusUpdateDoesNotTouchAnotherAccountOrRecreateClearedRows() = runBlocking {
        val db = database()
        try {
            val repo = HomeworkRepositoryImpl(db)
            repo.publish(seed)
            repo.publish(seed.copy(accountId = "two"))
            val submitted = a.copy(status = HomeworkStatus.SUBMITTED, rawStatus = "待批阅")
            assertEquals(1, repo.updateStatuses("one", listOf(submitted)))
            assertEquals(HomeworkStatus.NOT_SUBMITTED, repo.observe("two").first()!!.items.first { it.id == a.id }.status)
            assertEquals(b, repo.observe("one").first()!!.items.first { it.id == b.id })
            repo.clear()
            assertEquals(0, repo.updateStatuses("one", listOf(submitted)))
            assertNull(repo.observe("one").first())
        } finally { db.close() }
    }
    @Test fun delayedDetailsCannotUndoAConfirmedSubmissionInTheDatabase() = runBlocking {
        val db = database()
        try {
            val repo = HomeworkRepositoryImpl(db)
            repo.publish(seed)
            repo.updateStatuses("one", listOf(a.copy(status = HomeworkStatus.SUBMITTED, rawStatus = "待批阅")))
            assertEquals(0, repo.updateDetails("one", listOf(a.copy(score = 10.0, dueAt = Instant.now()))))
            val saved = repo.observe("one").first()!!.items.first { it.id == a.id }
            assertEquals(HomeworkStatus.SUBMITTED, saved.status)
            assertNull(saved.score)
        } finally { db.close() }
    }
    @Test fun startupReadsOnceAndOnlyManualRefreshUpdatesSubmissionAfterNavigation() {
        val db = database()
        val store = ViewModelStore()
        lateinit var model: AppViewModel
        val submitted = AtomicBoolean(false)
        val reads = AtomicInteger()
        try {
            val repo = HomeworkRepositoryImpl(db)
            runBlocking { repo.publish(seed.copy(items = listOf(a))) }
            val reader = McpChaoxingReader(McpReadTransport { url ->
                reads.incrementAndGet()
                val html = when (url.toHttpUrl().encodedPath) {
                    "/base" -> "<input id=fid value=123>"
                    "/mooc2-ans/visit/courselistdata" -> "<li><a href='https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=11&amp;clazzid=22&amp;cpi=33'>数学</a><p class=course-name>数学</p></li>"
                    "/visit/stucoursemiddle" -> "<input id=workEnc value=fixture><input id=enc value=fixture>"
                    "/mooc2/work/list" -> "<span>0/1</span><li onclick='goTask(this)' data='https://mooc1.chaoxing.com/work?workId=71'><p class=work-title>自动完成练习</p><p class=status>${if (submitted.get()) "待批阅" else "未提交"}</p><p>截止时间：2026-11-30 23:59</p></li>"
                    else -> if (url.contains("exam", true)) "<table><tr><td>暂无考试</td></tr></table>" else error("Unexpected endpoint: ${url.toHttpUrl().encodedPath}")
                }
                OfficialHtml(url, html)
            })
            val exams = object : ExamRepository {
                override fun observe(accountId: String) = flowOf<ExamSnapshot?>(null)
                override suspend fun publish(snapshot: ExamSnapshot) = Unit
                override suspend fun clear() = Unit
            }
            val settings = object : SettingsRepository {
                override val settings = MutableStateFlow(AppSettings())
                override val accountId = MutableStateFlow<String?>("one")
                override val guideCompleted = MutableStateFlow(true)
                override suspend fun completeGuide() { guideCompleted.value = true }
                override suspend fun save(settings: AppSettings) { this.settings.value = settings }
                override suspend fun saveAppearance(theme: ThemeMode, compact: Boolean, reminderHours: Int) = Unit
                override suspend fun saveFillInterval(intervalMillis: Int) = Unit
                override suspend fun setAccount(id: String?) { accountId.value = id }
            }
            val key = object : SecretStore {
                override fun read() = ""
                override fun write(value: String) = Unit
                override fun clear() = Unit
            }
            val ai = object : AiRepository {
                override suspend fun models(settings: AppSettings) = emptyList<String>()
                override fun generate(questions: List<Question>, settings: AppSettings) = emptyFlow<AiEvent>()
                override suspend fun test(settings: AppSettings) = ""
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                model = AppViewModel(instrumentation.targetContext, repo, exams, settings, key, CookieCoordinator(),
                    HomeworkSyncCoordinator(reader, repo, exams), ai, WebSessionController())
                store.put("status-test", model)
            }
            var route by mutableStateOf("home")
            val startupCalls = AtomicInteger()
            compose.setContent {
                val home by model.home.collectAsState()
                StartupHomeworkRefresh(home.accountId, home.initialized, true) {
                    startupCalls.incrementAndGet()
                    model.autoRefreshOnce()
                }
                Text(route)
            }
            compose.waitUntil(10_000) {
                model.home.value.snapshot?.fetchedAt?.isAfter(Instant.EPOCH) == true && !model.home.value.refreshing
            }
            instrumentation.runOnMainSync { model.stopForegroundSync() }
            android.os.SystemClock.sleep(2_000) // Exceeds the former 1.5-second debounce.
            val afterStartup = reads.get()
            assertTrue(afterStartup > 0)
            assertEquals(1, startupCalls.get())
            submitted.set(true)
            reader.wallTime = { Instant.now().plusSeconds(3_600) } // Cached course lists would now be stale.
            repeat(3) {
                compose.runOnIdle { route = "detail"; model.selectCourse(a.course.id) }
                compose.runOnIdle { route = "home" }
            }
            instrumentation.runOnMainSync { model.autoRefreshOnce() } // Activity recreation must not retry either.
            android.os.SystemClock.sleep(500)
            assertEquals(1, startupCalls.get())
            assertEquals(afterStartup, reads.get())
            assertEquals(HomeworkStatus.NOT_SUBMITTED, runBlocking {
                repo.observe("one").first()!!.items.first { it.id == a.id }.status
            })

            instrumentation.runOnMainSync { model.forceRefresh() }
            compose.waitUntil(10_000) { model.home.value.content?.summary?.completedTotal == 1 && !model.home.value.refreshing }
            assertTrue(reads.get() > afterStartup)
            assertEquals(HomeworkStatus.SUBMITTED, runBlocking {
                repo.observe("one").first()!!.items.first { it.id == a.id }.status
            })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            db.close()
        }
    }
}
