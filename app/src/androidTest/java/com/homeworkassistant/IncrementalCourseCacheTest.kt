package com.homeworkassistant

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class IncrementalCourseCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("incremental-test-$name", mode)
    }
    private var now = Instant.parse("2026-10-30T11:10:00Z")
    private val requests = mutableListOf<String>()
    private var fail = false
    private var status = "未提交"
    private var hideMathCourse = false
    private fun reader(): McpChaoxingReader = McpChaoxingReader(McpReadTransport { url ->
        requests += url
        val query = url.toHttpUrl()
        val html = when (query.encodedPath) {
            "/base" -> "<input id=fid value=123>"
            "/mooc2-ans/visit/courselistdata" -> """<div class=course><input class=courseId value=11><input class=clazzId value=22><input class=cpi value=33><span class=course-name>数学</span></div><div class=course><input class=courseId value=44><input class=clazzId value=55><input class=cpi value=66><span class=course-name>英语</span></div><div class=course><input class=courseId value=77><input class=clazzId value=88><input class=cpi value=99><span class=course-status>已结课</span></div>"""
            "/visit/stucoursemiddle" -> "<input id=workEnc value=signature><input id=enc value=stuenc>"
            "/mooc2/work/list" -> {
                if (fail) throw IOException("测试网络失败")
                if (query.queryParameter("courseId") == "44") "<p>暂无作业</p>"
                else "<span>0/1</span><li data-workid=1 data='/work/task?workId=1'><h3>数学练习</h3><p class=status>$status</p><p>作答时间：10-27 11：50 至11-01 23：59</p></li>"
            }
            else -> throw AssertionError(query.encodedPath)
        }
        OfficialHtml(url, if (hideMathCourse && query.encodedPath.contains("courselistdata")) html.replace(Regex("<div class=course><input class=courseId value=11>.*?</div>"), "") else html)
    }, PersistentCourseReadCache(cacheContext)).apply { wallTime = { now }; nanoTime = { now.toEpochMilli() * 1_000_000 } }
    private fun coordinator(db: AppDatabase, reader: McpChaoxingReader = reader()) = HomeworkSyncCoordinator(reader, HomeworkRepositoryImpl(db), ExamRepositoryImpl(db))
    private fun memory() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun clearEntries() = PersistentCourseReadCache(cacheContext).clear()

    @Test fun aCourseRemovedFromACompleteCatalogDoesNotLingerInCachedTotals() = runBlocking {
        clearEntries(); val db = memory()
        try {
            val sync = coordinator(db)
            sync.sync("one", includeDetails = false, includeExams = false)
            assertEquals(1, HomeworkRepositoryImpl(db).observe("one").first()!!.items.size)
            hideMathCourse = true; requests.clear()
            sync.sync("one", includeDetails = false, includeExams = false, force = true)
            assertTrue(HomeworkRepositoryImpl(db).observe("one").first()!!.items.isEmpty())
            assertTrue(requests.none { it.contains("courseId=11") || it.contains("courseid=11") })
            requests.clear(); sync.checkHomeworkStatus("one", "11:22:1")
            assertTrue(requests.isEmpty())
        } finally { db.close(); clearEntries() }
    }

    @Test fun retryAfterPauseSurvivesRecreationAndCannotBeClearedByManualRetry() {
        val store = com.homeworkassistant.data.session.PersistentReadPauseStore(cacheContext)
        try {
            store.write(com.homeworkassistant.data.session.ReadPause(now.plusSeconds(120).toEpochMilli(), "429，已暂停请求"))
            val gate = com.homeworkassistant.data.session.PlatformReadGate(com.homeworkassistant.data.session.PersistentReadPauseStore(cacheContext)).apply { this.now = { this@IncrementalCourseCacheTest.now } }
            gate.resumeAfterVerification()
            try { gate.checkPaused(); fail("Must honor server waiting time") } catch (_: com.homeworkassistant.data.session.OfficialReadPausedException) { }
            now = now.plusSeconds(121); gate.checkPaused()
            assertNull(store.read())
        } finally { store.write(null) }
    }

    @Test fun persistedCatalogListsAndEmptyCourseSurviveDatabaseAndReaderRecreation() = runBlocking {
        clearEntries()
        val name = "course-cache-restart-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            coordinator(db).sync("one", includeDetails = false, includeExams = false)
            assertEquals(6, requests.size)
            val repo = HomeworkRepositoryImpl(db)
            val snapshot = repo.observe("one").first()!!
            val cached = repo.courseCache("one")
            assertEquals(SnapshotCoverage.COMPLETE, snapshot.coverage)
            assertEquals(2, cached.size)
            assertTrue(cached.getValue("44:55").items.isEmpty())
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            requests.clear()
            val result = coordinator(db).sync("one", includeDetails = false, includeExams = false)
            assertTrue(requests.isEmpty())
            assertFalse(result.networkUpdated)
            assertEquals(snapshot.fetchedAt, HomeworkRepositoryImpl(db).observe("one").first()!!.fetchedAt)
            assertNull(PersistentCourseReadCache(cacheContext).catalog("two"))
            assertTrue(HomeworkRepositoryImpl(db).courseCache("two").isEmpty())
        } finally { db.close(); context.deleteDatabase(name); clearEntries() }
    }

    @Test fun failedForcedCourseReadKeepsDataAndSuccessTimeThenNormalRefreshRetriesDirtyCourse() = runBlocking {
        clearEntries(); val db = memory()
        try {
            val sync = coordinator(db)
            sync.sync("one", includeDetails = false, includeExams = false)
            val repo = HomeworkRepositoryImpl(db)
            val before = repo.observe("one").first()!!
            val successful = repo.courseCache("one").getValue("11:22").info.successfulAt
            fail = true; requests.clear()
            sync.sync("one", includeDetails = false, includeExams = false, force = true, targetCourseId = "11:22")
            assertEquals(1, requests.size)
            assertEquals(before.items, repo.observe("one").first()!!.items)
            val failed = repo.courseCache("one").getValue("11:22").info
            assertEquals(successful, failed.successfulAt)
            assertTrue(failed.dirty); assertNotNull(failed.lastError)
            fail = false; status = "退回重做"; requests.clear(); now = now.plusSeconds(10)
            sync.sync("one", includeDetails = false, includeExams = false)
            assertEquals(1, requests.size)
            assertEquals(HomeworkStatus.RETURNED, repo.observe("one").first()!!.items.single().status)
            assertNull(repo.courseCache("one").getValue("11:22").info.lastError)
        } finally { db.close(); clearEntries() }
    }

    @Test fun submittedCourseBypassesTtlAndUpdatesTotalsAndCompletedClassification() = runBlocking {
        clearEntries(); val db = memory()
        try {
            val sync = coordinator(db)
            sync.sync("one", includeDetails = false, includeExams = false)
            requests.clear(); status = "已提交"; now = now.plusSeconds(10)
            sync.checkHomeworkStatus("one", "11:22:1")
            assertEquals(1, requests.size)
            val snapshot = HomeworkRepositoryImpl(db).observe("one").first()!!
            val content = BuildHomeContentUseCase()(snapshot, HomeFilter(completed = true), now, ZoneId.of("Asia/Shanghai"))
            assertEquals(0, content.summary.pendingTotal)
            assertEquals(1, content.summary.completedTotal)
            assertEquals("11:22:1", content.visibleItems.single().id)
            assertEquals(now, HomeworkRepositoryImpl(db).courseCache("one").getValue("11:22").info.successfulAt)
        } finally { db.close(); clearEntries() }
    }

    @Test fun olderFullRefreshCannotOverwriteANewerCourseResultAndClearingRemovesFreshness() = runBlocking {
        val db = memory()
        try {
            val repo = HomeworkRepositoryImpl(db)
            val course = Course("11:22", "数学")
            val item = Homework("11:22:1", course, "练习", "https://mooc1.chaoxing.com/work/task?workId=1", status = HomeworkStatus.NOT_SUBMITTED)
            val snapshot = HomeworkSnapshot("one", 1, listOf(item), now, SnapshotCoverage.COMPLETE, 1, 1)
            repo.publishRead(snapshot, listOf(CourseHomeworkUpdate(course, listOf(item.copy(status = HomeworkStatus.SUBMITTED)), true, now.plusSeconds(10))), false)
            repo.publishRead(snapshot, listOf(CourseHomeworkUpdate(course, listOf(item), true, now)), false)
            assertTrue(repo.observe("one").first()!!.items.single().status.isCompleted)
            repo.clear()
            assertNull(repo.observe("one").first()); assertTrue(repo.courseCache("one").isEmpty())
        } finally { db.close() }
    }

    @Test fun expiryRefreshesEmptyCoursesTooAndFindsReturnedHomework() = runBlocking {
        clearEntries(); val db = memory()
        try {
            val sync = coordinator(db)
            sync.sync("one", includeDetails = false, includeExams = false)
            now = now.plusSeconds(301); requests.clear(); status = "退回重做"
            sync.sync("one", includeDetails = false, includeExams = false)
            assertEquals(2, requests.size)
            assertTrue(requests.none { it.contains("courselistdata") || it.contains("stucoursemiddle") })
            assertEquals(HomeworkStatus.RETURNED, HomeworkRepositoryImpl(db).observe("one").first()!!.items.single().status)
        } finally { db.close(); clearEntries() }
    }
}
