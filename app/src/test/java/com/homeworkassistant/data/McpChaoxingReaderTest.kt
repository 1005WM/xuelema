package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.web.SessionExpiredException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class McpChaoxingReaderTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun transport(failEnglish: Boolean = false, handler: ((String) -> Unit)? = null): McpReadTransport = McpReadTransport { url ->
        handler?.invoke(url)
        val parsed = url.toHttpUrl()
        val html = when (parsed.encodedPath) {
            "/base" -> "<script>var fid = '123';</script>"
            "/mooc2-ans/visit/courselistdata" -> fixture("mcp_courses.html")
            "/visit/stucoursemiddle" -> {
                if (failEnglish && parsed.queryParameter("courseid") == "44") throw IOException("fixture failure")
                "<input id='workEnc' value='work-signature'><input id='enc' value='page-signature'><input id='openc' value='open-signature'>"
            }
            "/mooc2/work/list" -> if (parsed.queryParameter("courseId") == "44") "<p>暂无作业</p>" else fixture("mcp_homework.html")
            "/mooc-ans/mooc2/work/task" -> "作答时间：2026-10-27 08:00 至 2026-10-28 09:00"
            "/exam-ans/exam/test/examcode/examlist" -> fixture("mcp_exams.html")
            else -> throw AssertionError("Unexpected endpoint")
        }
        OfficialHtml(url, html)
    }
    @Test fun signedCourseSequenceReadsBothStatusesWithoutWebView() = runTest {
        val requested = mutableListOf<String>()
        val result = McpChaoxingReader(transport(handler = { requested += it })).homework {}
        assertEquals(4, result.items.size)
        assertEquals(2, result.items.count { it.status.isPending })
        assertTrue(result.complete)
        assertEquals(2, result.completedCourses)
        val work = requested.first { it.toHttpUrl().encodedPath == "/mooc2/work/list" }.toHttpUrl()
        assertEquals("work-signature", work.queryParameter("enc"))
        assertEquals("page-signature", work.queryParameter("stuenc"))
    }
    @Test fun openedHomeworkChecksOnlyItsCourseAndReadsFreshSubmissionState() = runTest {
        var submitted = false
        val requests = mutableListOf<String>()
        val original = transport()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            requests += url
            val page = original.get(url)
            if (submitted && url.toHttpUrl().encodedPath == "/mooc2/work/list")
                page.copy(html = page.html.replace("<p class=\"status fl\">未提交</p>", "<p class=\"status fl\">待批阅</p>")) else page
        })
        reader.homework("account") {}
        requests.clear(); submitted = true
        val rows = reader.homeworkStatuses("account", "11:22")
        assertEquals(1, requests.size)
        assertEquals("11", requests.single().toHttpUrl().queryParameter("courseId"))
        assertEquals("22", requests.single().toHttpUrl().queryParameter("classId"))
        assertTrue(rows.first { it.id == "11:22:71" }.status.isCompleted)
        assertTrue(rows.all { it.course.id == "11:22" })
        assertFalse(requests.any { it.contains("exam") || it.contains("/work/task") })
    }
    @Test fun statusCheckWithoutEntryCacheCanRecoverAfterAppRestart() = runTest {
        val rows = McpChaoxingReader(transport()).homeworkStatuses("account", "11:22")
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.course.id == "11:22" })
    }
    @Test fun missingSignatureRestoresOnlyItsCourseUsingRetainedEntryMetadata() = runTest {
        val entries = MemoryCourseReadCache()
        McpChaoxingReader(transport(), entries).homework("account") {}
        val requests = mutableListOf<String>()
        val restarted = McpChaoxingReader(transport(handler = { requests += it }), entries)
        assertEquals(4, restarted.homeworkStatuses("account", "11:22").size)
        assertEquals(2, requests.size)
        assertEquals("33", requests.first().toHttpUrl().queryParameter("cpi"))
        assertTrue(requests.all { it.toHttpUrl().encodedPath in setOf("/visit/stucoursemiddle", "/mooc2/work/list") })
    }
    @Test fun invalidSignatureRenewsOnlyTheOpenedCourseAndThenReusesIt() = runTest {
        val requests = mutableListOf<String>()
        var failOnce = false
        val original = transport()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            requests += url
            if (failOnce && url.contains("/mooc2/work/list")) { failOnce = false; throw com.homeworkassistant.data.session.SignatureExpiredException() }
            original.get(url)
        })
        reader.homework("account") {}
        requests.clear(); failOnce = true
        reader.homeworkStatuses("account", "11:22")
        assertEquals(3, requests.size)
        assertTrue(requests.none { it.contains("courselistdata") || it.endsWith("/base") || it.contains("exam") })
        assertTrue(requests.all { val p = it.toHttpUrl(); p.queryParameter("courseid") == "11" || p.queryParameter("courseId") == "11" })
        requests.clear()
        reader.homeworkStatuses("account", "11:22")
        assertEquals(1, requests.size)
    }
    @Test fun mostRecentlyOpenedCourseIsReadAndPublishedBeforeOtherCourses() = runTest {
        val requests = mutableListOf<String>()
        val partials = mutableListOf<McpHomeworkRead>()
        McpChaoxingReader(transport(handler = { requests += it })).homework("account", onPartial = { partials += it }, priorityCourseId = "44:55") {}
        assertEquals("44", requests.first { it.contains("stucoursemiddle") }.toHttpUrl().queryParameter("courseid"))
        assertTrue(partials.first().items.isEmpty()) // Confirmed empty first course is also published.
        assertEquals(1, partials.first().completedCourses)
    }
    @Test fun cachedSchoolIdentifierSurvivesReaderRecreationWithoutAHomepageRequest() = runTest {
        val entries = MemoryCourseReadCache()
        McpChaoxingReader(transport(), entries).homework("account") {}
        val requests = mutableListOf<String>()
        McpChaoxingReader(transport(handler = { requests += it }), entries).homework("account") {}
        assertTrue(requests.none { it.endsWith("/base") })
        assertTrue(requests.none { it.contains("courselistdata") })
        assertTrue(requests.first().contains("stucoursemiddle"))
    }
    @Test fun statusChecksNeverReuseAnotherAccountsSignature() = runTest {
        val requests = mutableListOf<String>()
        val reader = McpChaoxingReader(transport(handler = { requests += it }))
        reader.homework("one") {}
        requests.clear()
        reader.homeworkStatuses("two", "11:22")
        assertTrue(requests.any { it.contains("stucoursemiddle") })
        assertTrue(requests.none { it.contains("courselistdata") || it.contains("/base") })
    }
    @Test fun failedCoursePreservesPartialCoverageRatherThanZeroPending() = runTest {
        val result = McpChaoxingReader(transport(failEnglish = true)).homework {}
        assertFalse(result.complete)
        assertEquals(1, result.completedCourses)
        assertEquals(4, result.items.size)
    }
    @Test fun detailFailurePreservesSubmissionStates() = runTest {
        val reader = McpChaoxingReader(transport(handler = { if (it.contains("/work/task")) throw IOException("detail failed") }))
        val base = reader.homework {}
        val result = reader.enrich(base).value
        assertEquals(4, result.detailsUnavailable)
        assertEquals(2, result.items.count { it.status.isPending })
        assertTrue(result.complete)
    }
    @Test fun loginExpiryIsNotSwallowedAsAnEmptyCourse() = runTest {
        try {
            McpChaoxingReader(transport(handler = { if (it.contains("stucoursemiddle")) throw SessionExpiredException() })).homework {}
            fail("Expected session expiry")
        } catch (_: SessionExpiredException) { }
    }
    @Test fun cancellationInterruptsWithoutReturningPartialData() = runTest {
        val started = CompletableDeferred<Unit>()
        val reader = McpChaoxingReader(McpReadTransport { started.complete(Unit); awaitCancellation() })
        val job = async { reader.homework {} }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun examsUseDedicatedReadEndpoint() = runTest {
        val requested = mutableListOf<String>()
        val scope = CourseScanScope(McpChaoxingParser().courses(OfficialHtml(McpChaoxingParser.COURSE_LIST, fixture("mcp_courses.html"))).courses)
        val rows = McpChaoxingReader(transport(handler = { requested += it })).exams(scope) {}.items
        assertEquals(listOf(McpChaoxingParser.EXAM_LIST), requested)
        assertEquals(3, rows.size)
    }
    @Test fun listReturnsAndEmitsPartialStatesWithoutRequestingAnyDetail() = runBlocking {
        val requested = mutableListOf<String>()
        val partials = mutableListOf<McpHomeworkRead>()
        val reader = McpChaoxingReader(transport(handler = { requested += it }))
        val value = reader.homework(onPartial = { partials += it }) {}
        assertEquals(4, value.items.size)
        assertTrue(requested.none { it.contains("/work/task") })
        assertTrue(partials.isNotEmpty())
        assertTrue(partials.none { it.complete })
    }
    @Test fun slowDetailHasPerItemTimeoutAndReturnsConfirmedStates() = runBlocking {
        val original = transport()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("/work/task")) awaitCancellation() else original.get(url)
        })
        val base = reader.homework {}
        val result = withTimeout(2_000) { reader.enrich(base, EnrichmentLimits(2, 30, 500)) }
        assertEquals(2, result.attempted)
        assertFalse(result.budgetExpired)
        assertEquals(base.items, result.value.items)
        assertEquals(4, result.value.detailsUnavailable)
    }
    @Test fun totalBudgetKeepsEarlierSuccessAndStopsRemainingDetails() = runBlocking {
        val original = transport()
        var count = 0
        val published = mutableListOf<McpHomeworkRead>()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("/work/task") && ++count > 1) awaitCancellation() else original.get(url)
        })
        val base = reader.homework {}
        val result = reader.enrich(base, EnrichmentLimits(8, 1_000, 500), onUpdated = { published += it })
        assertTrue(result.budgetExpired)
        assertEquals(2, result.attempted)
        assertEquals(1, published.size)
        assertNotNull(published.first().items.first().dueAt)
        assertEquals(published.last().items, result.value.items)
    }
    @Test fun cancellingEnrichmentDoesNotDiscardEarlierPublishedDetail() = runBlocking {
        val original = transport()
        val waiting = CompletableDeferred<Unit>()
        var count = 0
        val published = mutableListOf<McpHomeworkRead>()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("/work/task") && ++count > 1) { waiting.complete(Unit); awaitCancellation() }
            else original.get(url)
        })
        val base = reader.homework {}
        val job = launch { reader.enrich(base, onUpdated = { published += it }) }
        waiting.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, published.size)
    }
    @Test fun signatureReuseReducesRequestsButAlwaysRechecksSubmissionStatesAndIsAccountScoped() = runBlocking {
        val requests = mutableListOf<String>()
        val reader = McpChaoxingReader(transport(handler = { requests += it }))
        reader.homework("one") {}
        val first = requests.size
        val middles = requests.count { it.contains("/visit/stucoursemiddle") }
        reader.homework("one") {}
        assertEquals(middles, requests.count { it.contains("/visit/stucoursemiddle") })
        assertTrue(requests.size - first < first)
        assertEquals(4, requests.count { it.contains("/mooc2/work/list") })
        reader.homework("two") {}
        assertEquals(middles * 2, requests.count { it.contains("/visit/stucoursemiddle") })
        reader.clearCaches(); reader.homework("two") {}
        assertEquals(middles * 3, requests.count { it.contains("/visit/stucoursemiddle") })
    }
    @Test fun staleSignatureRefreshesOnceThenReusesFreshSignature() = runBlocking {
        val original = transport()
        var middleCount = 0
        var rejectNext = false
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("/visit/stucoursemiddle")) middleCount++
            if (url.toHttpUrl().queryParameter("courseId") == "11" && rejectNext) {
                rejectNext = false; throw com.homeworkassistant.data.session.SignatureExpiredException()
            }
            original.get(url)
        })
        reader.homework("one") {}
        rejectNext = true
        assertEquals(4, reader.homework("one") {}.items.size)
        assertEquals(3, middleCount)
        reader.homework("one") {}
        assertEquals(3, middleCount)
    }
    @Test fun recentlyReadDetailsAreSkippedUntilStatusChangesOrCacheIsCleared() = runBlocking {
        val requests = mutableListOf<String>()
        val reader = McpChaoxingReader(transport(handler = { requests += it }))
        val base = reader.homework {}
        reader.enrich(base, accountId = "one")
        val count = requests.size
        assertEquals(0, reader.enrich(base, accountId = "one").attempted)
        assertEquals(count, requests.size)
        val changed = base.copy(items = base.items.map { it.copy(rawStatus = "退回重做", status = com.homeworkassistant.domain.model.HomeworkStatus.RETURNED) })
        assertEquals(4, reader.enrich(changed, accountId = "one").attempted)
        reader.clearCaches()
        assertEquals(4, reader.enrich(changed, accountId = "one").attempted)
    }
    @Test fun itemCapAndUnparseableDatePreserveExistingDeadline() = runBlocking {
        val original = transport()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("/work/task")) OfficialHtml(url, "作答时间：10-27 08:00 至 10-28 09:00") else original.get(url)
        })
        val due = java.time.Instant.parse("2026-10-29T00:00:00Z")
        val base = reader.homework {}.let { it.copy(items = it.items.map { item -> item.copy(dueAt = due) }) }
        val result = reader.enrich(base, EnrichmentLimits(maxItems = 1))
        assertEquals(1, result.attempted)
        assertEquals(3, result.value.detailsUnavailable)
        assertEquals(3, result.value.items.count { it.dueAt == due })
        assertEquals("10-28 09:00（年份待确认）", result.value.items.single { it.dueAt == null }.dueText)
    }
    @Test fun warmReaderReusesCatalogButRechecksListsWithoutRoomCache() = runBlocking {
        val requested = mutableListOf<String>()
        val reader = McpChaoxingReader(transport(handler = { requested += it }))
        reader.homework("one") {}
        assertEquals(6, requested.size)
        requested.clear()
        val value = reader.homework("one") {}
        assertTrue(value.complete)
        assertEquals(2, requested.size)
        assertEquals(0, requested.count { it.contains("courselistdata") })
        assertEquals(2, requested.count { it.contains("/mooc2/work/list") })
        assertTrue(requested.none { it.endsWith("/base") || it.contains("stucoursemiddle") })
    }
    @Test fun schoolExpiresAtTwoMinutesButSignaturesLastTenMinutes() = runBlocking {
        val requested = mutableListOf<String>()
        var now = 0L
        val reader = McpChaoxingReader(transport(handler = { requested += it })).apply { nanoTime = { now } }
        reader.homework("one") {}
        now = 180_000_000_000L; requested.clear()
        reader.homework("one", force = true) {}
        assertEquals(4, requested.size)
        assertTrue(requested.any { it.endsWith("/base") })
        assertTrue(requested.none { it.contains("stucoursemiddle") })
        now = 601_000_000_000L; requested.clear()
        reader.homework("one", force = true) {}
        assertEquals(6, requested.size)
    }
    @Test fun failedDetailHasCooldownButChangedStatusRetriesImmediately() = runBlocking {
        var now = 0L
        val reader = McpChaoxingReader(transport(handler = { if (it.contains("/work/task")) throw IOException("fixture") }))
            .apply { nanoTime = { now } }
        val base = reader.homework("one") {}
        assertEquals(4, reader.enrich(base, accountId = "one").attempted)
        val skipped = reader.enrich(base, accountId = "one")
        assertEquals(0, skipped.attempted)
        assertEquals(4, skipped.value.detailsUnavailable)
        val changed = base.copy(items = base.items.map { it.copy(rawStatus = "退回重做") })
        assertEquals(4, reader.enrich(changed, accountId = "one").attempted)
        now = 61_000_000_000L
        assertEquals(4, reader.enrich(changed, accountId = "one").attempted)
    }
    @Test fun failedDetailCooldownIsAccountScopedAndClearedWithCache() = runBlocking {
        val reader = McpChaoxingReader(transport(handler = { if (it.contains("/work/task")) throw IOException("fixture") }))
        val base = reader.homework("one") {}
        reader.enrich(base, accountId = "one")
        assertEquals(4, reader.enrich(base, accountId = "two").attempted)
        assertEquals(0, reader.enrich(base, accountId = "two").attempted)
        reader.clearCaches()
        assertEquals(4, reader.enrich(base, accountId = "two").attempted)
    }
    @Test fun aPageWithNoDeadlineIsNotCachedAsSuccessfulForTenMinutes() = runBlocking {
        var now = 0L
        var html = "<p>作业详情正在加载</p>"
        val reader = McpChaoxingReader(McpReadTransport { OfficialHtml(it, html) }).apply { nanoTime = { now } }
        val h = com.homeworkassistant.domain.model.Homework("11:22:1", com.homeworkassistant.domain.model.Course("11:22", "数学"),
            "练习", "https://mooc1.chaoxing.com/work/task?workId=1", status = com.homeworkassistant.domain.model.HomeworkStatus.NOT_SUBMITTED)
        val seed = McpHomeworkRead(listOf(h), 1, 1, true, 0, 0, emptyList())
        val missing = reader.enrich(seed, accountId = "one")
        assertEquals(1, missing.value.detailsUnavailable)
        assertEquals(0, reader.enrich(seed, accountId = "one").attempted)
        now = 61_000_000_000L
        html = "<p>作答时间：10-27 11：50 至11-01 23：59</p>"
        val recovered = reader.enrich(seed, accountId = "one")
        assertEquals(1, recovered.attempted)
        assertEquals(0, recovered.value.detailsUnavailable)
        assertEquals("11-01 23:59（年份待确认）", recovered.value.items.single().dueText)
        assertEquals(0, reader.enrich(recovered.value, accountId = "one").attempted)
    }
    @Test fun invalidCachedSchoolEntryRecoversOnceWithoutReportingAnEmptyList() = runBlocking {
        val original = transport()
        var failOnce = false
        var baseReads = 0
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.endsWith("/base")) baseReads++
            if (failOnce && url.contains("courselistdata")) {
                failOnce = false; OfficialHtml(url, "<p>入口失效</p>")
            } else original.get(url)
        })
        reader.homework("one") {}
        failOnce = true
        val result = reader.homework("one", force = true) {}
        assertTrue(result.complete)
        assertEquals(4, result.items.size)
        assertEquals(2, baseReads)
    }
}
