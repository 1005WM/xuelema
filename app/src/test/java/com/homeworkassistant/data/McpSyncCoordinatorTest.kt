package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import com.homeworkassistant.data.web.SessionExpiredException
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class McpSyncCoordinatorTest {
    private class HomeworkCache(val initial: HomeworkSnapshot? = null) : HomeworkRepository {
        val values = mutableListOf<Pair<HomeworkSnapshot, Boolean>>()
        override fun observe(accountId: String) = flowOf(values.lastOrNull()?.first ?: initial)
        override suspend fun publish(snapshot: HomeworkSnapshot, preserveUnseen: Boolean) { values += snapshot to preserveUnseen }
        override suspend fun clear() = Unit
        override suspend fun updateStatuses(accountId: String, updates: List<Homework>): Int {
            val old = observe(accountId).first() ?: return 0
            val value = com.homeworkassistant.domain.usecase.ApplyHomeworkStatusesUseCase()(old, updates)
            if (old == value) return 0
            publish(value, false)
            return old.items.zip(value.items).count { it.first != it.second }
        }
        override suspend fun updateDetails(accountId: String, updates: List<Homework>): Int {
            val old = observe(accountId).first() ?: return 0
            val updated = com.homeworkassistant.domain.usecase.ApplyHomeworkDetailsUseCase()(old, updates)
            if (old == updated) return 0
            publish(updated, false)
            return old.items.zip(updated.items).count { it.first != it.second }
        }
    }
    private class ExamCache : ExamRepository {
        val values = mutableListOf<ExamSnapshot>()
        override fun observe(accountId: String) = flowOf<ExamSnapshot?>(null)
        override suspend fun publish(snapshot: ExamSnapshot) { values += snapshot }
        override suspend fun clear() = Unit
    }
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun reader(failHomework: Boolean = false, failExams: Boolean = false,
        detailHtml: String = "作答时间：2026-10-27 08:00 至 2026-10-28 09:00",
        homeworkHtml: () -> String = { fixture("mcp_homework.html") }, beforeExam: suspend () -> Unit = {}) = McpChaoxingReader(McpReadTransport { url ->
        val path = url.toHttpUrl().encodedPath
        if (failHomework && path == "/visit/stucoursemiddle") throw IOException("homework fixture failed")
        if (failExams && path.contains("examlist")) throw IOException("exams fixture failed")
        val html = when (path) {
            "/base" -> "<script>var fid = '123';</script>"
            "/mooc2-ans/visit/courselistdata" -> fixture("mcp_courses.html")
            "/visit/stucoursemiddle" -> "<input id=workEnc value=test><input id=enc value=page>"
            "/mooc2/work/list" -> homeworkHtml()
            "/mooc-ans/mooc2/work/task" -> detailHtml
            "/exam-ans/exam/test/examcode/examlist" -> { beforeExam(); fixture("mcp_exams.html") }
            else -> throw AssertionError("Unexpected endpoint")
        }
        OfficialHtml(url, html)
    })
    @Test fun successfulSyncPublishesBothCachesAndUnfilteredHomeworkSnapshot() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val result = HomeworkSyncCoordinator(reader(), homework, exams).sync("account")
        assertFalse(result.partial)
        assertTrue(result.homeworkUpdated)
        assertEquals(8, homework.values.last().first.items.size)
        assertFalse(homework.values.last().second)
        assertEquals(3, exams.values.single().items.size)
    }
    @Test fun repeatedForcedRefreshesShareTheSameRunningSweep() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var lists = 0
        val sync = HomeworkSyncCoordinator(reader(homeworkHtml = { lists++; fixture("mcp_homework.html") },
            beforeExam = { entered.complete(Unit); release.await() }), HomeworkCache(), ExamCache())
        val first = async { sync.sync("account", includeDetails = false, force = true) }
        entered.await()
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { sync.sync("account", includeDetails = false, force = true) }
        delay(20); release.complete(Unit)
        assertEquals(first.await(), duplicate.await())
        assertEquals(2, lists)
    }
    @Test fun examFailureKeepsExamCacheAndStillPublishesHomework() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val result = HomeworkSyncCoordinator(reader(failExams = true), homework, exams).sync("account")
        assertTrue(result.partial)
        assertTrue(result.homeworkUpdated)
        assertTrue(homework.values.isNotEmpty())
        assertTrue(exams.values.isEmpty())
    }
    @Test fun homeworkFailureKeepsHomeworkCacheAndStillPublishesExams() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val result = HomeworkSyncCoordinator(reader(failHomework = true), homework, exams).sync("account")
        assertFalse(result.homeworkUpdated)
        assertTrue(homework.values.isEmpty())
        assertEquals(1, exams.values.size)
    }
    @Test fun bothFailuresReportErrorAndDoNotPublishEmptyData() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        try {
            HomeworkSyncCoordinator(reader(true, true), homework, exams).sync("account")
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(homework.values.isEmpty()); assertTrue(exams.values.isEmpty())
    }
    @Test fun accountInvalidationPreventsPublicationAfterNetworkRead() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        lateinit var sync: HomeworkSyncCoordinator
        val original = reader()
        var invalidated = false
        sync = HomeworkSyncCoordinator(original, homework, exams)
        try {
            sync.sync("account") {
                if (!invalidated) { invalidated = true; sync.invalidate() }
            }
            fail("Expected invalidated session")
        } catch (_: IllegalStateException) { }
        assertTrue(homework.values.isEmpty()); assertTrue(exams.values.isEmpty())
    }
    @Test fun sessionExpiryStopsBothPublishers() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val reader = McpChaoxingReader(McpReadTransport { throw SessionExpiredException() })
        try {
            HomeworkSyncCoordinator(reader, homework, exams).sync("account")
            fail("Expected expiry")
        } catch (_: SessionExpiredException) { }
        assertTrue(homework.values.isEmpty()); assertTrue(exams.values.isEmpty())
    }
    @Test fun confirmedRowsArePublishedBeforeSecondCourseExamAndDetails() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val original = reader()
        // Progress callbacks run before the next course and optional details requests.
        var sawSecondCourse = false
        var sawDetails = false
        HomeworkSyncCoordinator(original, homework, exams).sync("account") { progress ->
            if (progress.contains("2/2")) {
                sawSecondCourse = true
                assertEquals(4, homework.values.first().first.items.size)
                assertEquals(SnapshotCoverage.PARTIAL, homework.values.first().first.coverage)
            }
            if (progress.contains("补充详情")) {
                sawDetails = true
                assertEquals(8, homework.values.last().first.items.size)
                assertEquals(1, exams.values.size)
            }
        }
        assertTrue(sawSecondCourse); assertTrue(sawDetails)
    }
    @Test fun cancellingAtDetailStageLeavesAllConfirmedStatusesInCache() = runBlocking {
        val homework = HomeworkCache(); val exams = ExamCache()
        val entered = CompletableDeferred<Unit>()
        val coordinator = HomeworkSyncCoordinator(reader(), homework, exams)
        val job = launch {
            coordinator.sync("account") { progress ->
                if (progress.contains("补充详情")) { entered.complete(Unit); throw CancellationException("stop details") }
            }
        }
        entered.await(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(8, homework.values.last().first.items.size)
        assertEquals(4, homework.values.last().first.items.count { it.status.isPending })
        assertEquals(1, exams.values.size)
    }
    @Test fun basicPublishRetainsKnownDatesButFullPublishRemovesUnseenOldRows() = runBlocking {
        val due = java.time.Instant.parse("2026-10-29T00:00:00Z")
        val old = Homework("11:22:71", Course("11:22", "高等数学"), "旧标题", McpChaoxingParser.WORK_LIST,
            dueAt = due, status = HomeworkStatus.GRADED, score = 90.0, rawStatus = "已批阅")
        val previous = HomeworkSnapshot("account", 1, listOf(old, old.copy(id = "unseen")), java.time.Instant.EPOCH,
            SnapshotCoverage.COMPLETE, 2, 2)
        val homework = HomeworkCache(previous)
        HomeworkSyncCoordinator(reader(), homework, ExamCache()).sync("account")
        val basic = homework.values.first().first.items.first { it.id == old.id }
        assertEquals(due, basic.dueAt)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, basic.status)
        assertNull(basic.score)
        assertTrue(homework.values.last().first.items.none { it.id == "unseen" })
        assertEquals(SnapshotCoverage.COMPLETE, homework.values.last().first.coverage)
    }
    @Test fun foregroundRefreshFinishesWithoutWaitingForAnyDetailRequest() = runBlocking {
        val requested = mutableListOf<String>()
        val enteringDetail = CompletableDeferred<Unit>()
        val cache = HomeworkCache(); val exam = ExamCache()
        // List responses are immediate. A detail response would never finish.
        val fixtureReader = McpChaoxingReader(McpReadTransport { url ->
            requested += url
            if (url.contains("/work/task")) { enteringDetail.complete(Unit); awaitCancellation() }
            val path = url.toHttpUrl().encodedPath
            OfficialHtml(url, when (path) {
                "/base" -> "<script>var fid = '123';</script>"
                "/mooc2-ans/visit/courselistdata" -> fixture("mcp_courses.html")
                "/visit/stucoursemiddle" -> "<input id=workEnc value=test><input id=enc value=page>"
                "/mooc2/work/list" -> fixture("mcp_homework.html")
                "/exam-ans/exam/test/examcode/examlist" -> fixture("mcp_exams.html")
                else -> error("Unexpected endpoint")
            })
        })
        val sync = HomeworkSyncCoordinator(fixtureReader, cache, exam)
        val result = withTimeout(2_000) { sync.sync("account", includeDetails = false) }
        assertNotNull(result.pendingDetails)
        assertEquals(8, cache.values.last().first.items.size)
        assertEquals(1, exam.values.size)
        assertTrue(requested.none { it.contains("/work/task") })
        val before = cache.values.size
        val background = launch { sync.enrichDetails(result.pendingDetails!!) }
        enteringDetail.await(); background.cancelAndJoin()
        assertEquals(before, cache.values.size)
        assertEquals(SnapshotCoverage.COMPLETE, cache.values.last().first.coverage)
    }
    @Test fun nextRefreshMakesAnOldDetailSeedInert() = runBlocking {
        val cache = HomeworkCache(); val sync = HomeworkSyncCoordinator(reader(), cache, ExamCache())
        val first = sync.sync("account", includeDetails = false).pendingDetails!!
        val second = sync.sync("account", includeDetails = false).pendingDetails!!
        val before = cache.values.size
        assertNull(sync.enrichDetails(first))
        assertEquals(before, cache.values.size)
        assertNotNull(sync.enrichDetails(second))
        assertTrue(cache.values.last().first.items.any { it.dueAt != null })
        assertEquals(8, cache.values.last().first.items.size)
        assertEquals(SnapshotCoverage.COMPLETE, cache.values.last().first.coverage)
    }
    @Test fun logoutInvalidationDisablesPendingBackgroundWork() = runBlocking {
        val cache = HomeworkCache(); val sync = HomeworkSyncCoordinator(reader(), cache, ExamCache())
        val seed = sync.sync("account", includeDetails = false).pendingDetails!!
        val before = cache.values.size
        sync.invalidate()
        assertNull(sync.enrichDetails(seed))
        assertEquals(before, cache.values.size)
    }
    @Test fun changingAccountDisablesThePreviousAccountsDetailSeed() = runBlocking {
        val cache = HomeworkCache(); val sync = HomeworkSyncCoordinator(reader(), cache, ExamCache())
        val seed = sync.sync("account", includeDetails = false).pendingDetails!!
        // Separate caches on the device are account scoped; no initial snapshot for the new account.
        cache.values.clear()
        sync.sync("other", includeDetails = false)
        val before = cache.values.size
        assertNull(sync.enrichDetails(seed))
        assertEquals(before, cache.values.size)
        assertTrue(cache.values.all { it.first.accountId == "other" })
    }
    @Test fun openedHomeworkStatusIsReconciledWithoutAnExplicitFullSync() = runBlocking {
        val h = Homework("11:22:72", Course("11:22", "数学"), "练习", McpChaoxingParser.WORK_LIST,
            status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
        val other = h.copy(id = "other", course = Course("77:88", "另一门课"))
        val initial = HomeworkSnapshot("account", 1, listOf(h, other), java.time.Instant.EPOCH, SnapshotCoverage.COMPLETE, 2, 2)
        val cache = HomeworkCache(initial)
        val exams = ExamCache()
        val sync = HomeworkSyncCoordinator(reader(), cache, exams)
        assertEquals(1, sync.checkHomeworkStatus("account", h.id))
        val saved = cache.observe("account").first()!!
        assertTrue(saved.items.first { it.id == h.id }.status.isCompleted)
        assertEquals(other, saved.items.first { it.id == other.id })
        assertEquals(initial.fetchedAt, saved.fetchedAt)
        assertEquals(5, saved.items.size)
        assertTrue(exams.values.isEmpty())
        assertEquals(0, sync.checkHomeworkStatus("account", h.id))
    }
    @Test fun duplicatePageReturnAndTimerTriggersShareOneCourseRequestAndShortFreshnessWindow() = runBlocking {
        val h = Homework("11:22:72", Course("11:22", "数学"), "练习", McpChaoxingParser.WORK_LIST,
            status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
        val cache = HomeworkCache(HomeworkSnapshot("account", 1, listOf(h), java.time.Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var listReads = 0
        val sync = HomeworkSyncCoordinator(reader(homeworkHtml = {
            listReads++; fixture("mcp_homework.html")
        }), cache, ExamCache())
        var now = 0L; sync.nanoTime = { now }
        coroutineScope {
            val calls = (1..3).map { async { started.complete(Unit); release.await(); sync.checkHomeworkStatus("account", h.id) } }
            started.await(); release.complete(Unit); calls.awaitAll()
        }
        assertEquals(1, listReads)
        sync.checkHomeworkStatus("account", h.id)
        assertEquals(1, listReads)
        now = 2_000_000_000L
        sync.checkHomeworkStatus("account", h.id)
        assertEquals(2, listReads)
    }
    @Test fun cancellingOneSubscriberDoesNotCancelTheSharedReadForReturningHome() = runBlocking {
        val h = Homework("11:22:72", Course("11:22", "数学"), "练习", McpChaoxingParser.WORK_LIST, status = HomeworkStatus.NOT_SUBMITTED)
        val cache = HomeworkCache(HomeworkSnapshot("account", 1, listOf(h), java.time.Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var requests = 0
        val reader = McpChaoxingReader(McpReadTransport { url ->
            requests++
            if (url.contains("stucoursemiddle")) { started.complete(Unit); release.await() }
            OfficialHtml(url, if (url.contains("stucoursemiddle")) "<input id=workEnc value=fixture><input id=enc value=fixture>" else fixture("mcp_homework.html"))
        })
        val sync = HomeworkSyncCoordinator(reader, cache, ExamCache())
        val page = async { sync.checkHomeworkStatus("account", h.id) }
        started.await(); page.cancelAndJoin()
        val returning = async { sync.checkHomeworkStatus("account", h.id) }
        release.complete(Unit); returning.await()
        assertEquals(2, requests)
        assertTrue(cache.observe("account").first()!!.items.first { it.id == h.id }.status.isCompleted)
    }
    @Test fun invalidationCancelsTheSharedReadAndKeepsTheOldSnapshot() = runBlocking {
        val h = Homework("11:22:72", Course("11:22", "数学"), "练习", McpChaoxingParser.WORK_LIST, status = HomeworkStatus.NOT_SUBMITTED)
        val initial = HomeworkSnapshot("account", 1, listOf(h), java.time.Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1)
        val cache = HomeworkCache(initial)
        val started = CompletableDeferred<Unit>()
        val sync = HomeworkSyncCoordinator(McpChaoxingReader(McpReadTransport { started.complete(Unit); awaitCancellation() }), cache, ExamCache())
        val read = async { sync.checkHomeworkStatus("account", h.id) }
        started.await(); sync.invalidate(); read.join()
        assertTrue(read.isCancelled)
        assertEquals(initial, cache.observe("account").first())
    }
    @Test fun failedHomeworkPhaseCanStillReturnASeparateExamTask() = runBlocking {
        val cache = HomeworkCache(); val exams = ExamCache()
        val sync = HomeworkSyncCoordinator(reader(failHomework = true), cache, exams)
        val result = sync.sync("account", includeDetails = false, includeExams = false)
        assertFalse(result.homeworkUpdated)
        assertTrue(result.partial)
        assertTrue(exams.values.isEmpty())
        assertNotNull(sync.syncExams(result.pendingExams!!))
        assertEquals(3, exams.values.single().items.size)
    }
    @Test fun examSyncIsDeferredAndDoesNotHoldTheHomeworkStatusCoordinatorLock() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var submitted = false
        val cache = HomeworkCache(); val exams = ExamCache()
        val sync = HomeworkSyncCoordinator(reader(homeworkHtml = {
            fixture("mcp_homework.html").let { if (submitted) it.replace("<p class=\"status fl\">未提交</p>", "<p class=\"status fl\">待批阅</p>") else it }
        }, beforeExam = { started.complete(Unit); release.await() }), cache, exams)
        val result = sync.sync("account", includeDetails = false, includeExams = false)
        assertNotNull(result.pendingDetails); assertNotNull(result.pendingExams)
        assertTrue(exams.values.isEmpty())
        val examJob = async { sync.syncExams(result.pendingExams!!) }
        started.await(); submitted = true
        withTimeout(1_000) { sync.checkHomeworkStatus("account", "11:22:71") }
        assertTrue(cache.observe("account").first()!!.items.first { it.id == "11:22:71" }.status.isCompleted)
        release.complete(Unit); assertNotNull(examJob.await())
    }
    @Test fun failedStatusCheckKeepsExistingSubmissionAndOtherCourses() = runBlocking {
        val h = Homework("11:22:71", Course("11:22", "数学"), "练习", McpChaoxingParser.WORK_LIST,
            status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
        val initial = HomeworkSnapshot("account", 1, listOf(h), java.time.Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1)
        val cache = HomeworkCache(initial)
        val sync = HomeworkSyncCoordinator(reader(failHomework = true), cache, ExamCache())
        try { sync.checkHomeworkStatus("account", h.id); fail("Expected read failure") } catch (_: IOException) { }
        assertEquals(initial, cache.observe("account").first())
    }
    @Test fun backgroundScoreIsSavedWithoutReplacingTheListSubmissionState() = runBlocking {
        val cache = HomeworkCache()
        val sync = HomeworkSyncCoordinator(reader(detailHtml = "截止时间：2026-10-29 09:00 得分：88分"), cache, ExamCache())
        val seed = sync.sync("account", includeDetails = false).pendingDetails!!
        val submitted = cache.values.last().first.items.first { it.status == HomeworkStatus.SUBMITTED }
        sync.enrichDetails(seed.copy(value = seed.value.copy(items = listOf(submitted))))
        val saved = cache.values.last().first.items.first { it.id == submitted.id }
        assertEquals(HomeworkStatus.SUBMITTED, saved.status)
        assertEquals(submitted.rawStatus, saved.rawStatus)
        assertEquals(88.0, saved.score!!, 0.001)
        assertNotNull(saved.dueAt)
        assertEquals(8, cache.values.last().first.items.size)
    }
}
