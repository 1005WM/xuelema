package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.session.*
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections

class IncrementalCourseReadTest {
    private val courses = listOf(McpCourse("11", "22", "33", "数学", CourseLifecycle.ACTIVE),
        McpCourse("44", "55", "66", "英语", CourseLifecycle.ACTIVE), McpCourse("77", "88", "99", "已结课", CourseLifecycle.ENDED))
    private var now = Instant.parse("2026-10-30T02:00:00Z")
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var failure: IOException? = null
    private var status = "未提交"
    private var extraHomework = false
    private fun transport(delayMillis: Long = 0) = McpReadTransport { url ->
        requests += url
        if (delayMillis > 0) delay(delayMillis)
        val parsed = url.toHttpUrl()
        val html = when (parsed.encodedPath) {
            "/base" -> "<script>var fid = '123';</script>"
            "/mooc2-ans/visit/courselistdata" -> courses.joinToString("") { c -> "<div class=course><input class=courseId value=${c.courseId}><input class=clazzId value=${c.classId}><input class=cpi value=${c.cpi}><span class=course-name>${c.name}</span>${if (c.lifecycle == CourseLifecycle.ENDED) "<span class=course-status>已结课</span>" else ""}</div>" }
            "/visit/stucoursemiddle" -> "<input id=workEnc value=signature><input id=enc value=stuenc>"
            "/mooc2/work/list" -> {
                failure?.let { throw it }
                "<span>0/${if (extraHomework) 2 else 1}</span><li data-workid=1 data='/work/task?workId=1'><h3>练习</h3><p class=status>$status</p><p>截止时间：2026-11-01 23：59</p></li>" +
                    if (extraHomework) "<li data-workid=2 data='/work/task?workId=2'><h3>新作业</h3><p class=status>未提交</p></li>" else ""
            }
            else -> throw AssertionError("Unexpected endpoint: ${parsed.encodedPath}")
        }
        OfficialHtml(url, html)
    }
    private fun reader(cache: CourseReadCache = MemoryCourseReadCache(), delay: Long = 0) = McpChaoxingReader(transport(delay), cache).apply {
        wallTime = { now }; nanoTime = { now.toEpochMilli() * 1_000_000 }
    }
    private fun McpHomeworkRead.cache() = updates.associate { it.course.id to CachedCourseHomework(it.items, CourseCacheInfo(it.successfulAt)) }

    @Test fun freshCatalogAndListsSkipAllRequestsAndEndedCoursesStayExcluded() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        assertEquals(6, requests.size)
        assertEquals(2, first.courses)
        requests.clear()
        val hit = reader.homework("one", cachedWork = first.cache()) {}
        assertEquals(0, requests.size)
        assertEquals(2, hit.cacheHits)
        assertTrue(hit.catalogFromCache)
        assertEquals(first.items, hit.items)
        assertTrue(hit.updates.isEmpty())
    }
    @Test fun cacheOnlyRefreshDoesNotRereadKnownPendingDeadlines() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        requests.clear()
        val cached = reader.homework("one", cachedWork = first.cache()) {}
        assertEquals(0, reader.enrich(cached, accountId = "one").attempted)
        assertTrue(requests.isEmpty())
    }
    @Test fun expiredCoursesCanDiscoverNewAssignments() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        now = now.plusSeconds(301); extraHomework = true; requests.clear()
        val updated = reader.homework("one", cachedWork = first.cache()) {}
        assertEquals(2, requests.size)
        assertEquals(2, updated.items.count { it.title == "新作业" })
        assertEquals(4, updated.items.size)
    }
    @Test fun recreatedReaderUsesRetainedCatalogAndSuccessfulListsWithoutSignatures() = runBlocking {
        val entries = MemoryCourseReadCache()
        val first = reader(entries).homework("one") {}
        requests.clear()
        assertEquals(first.items, reader(entries).homework("one", cachedWork = first.cache()) {}.items)
        assertTrue(requests.isEmpty())
    }
    @Test fun expirationFindsNewStatusAndOnlyRefreshesExpiredCourses() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        now = now.plusSeconds(301)
        val cache = first.cache().toMutableMap()
        cache["44:55"] = cache.getValue("44:55").copy(info = CourseCacheInfo(now))
        status = "退回重做"; requests.clear()
        val value = reader.homework("one", cachedWork = cache) {}
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("courseId=11"))
        assertEquals(HomeworkStatus.RETURNED, value.items.first { it.course.id == "11:22" }.status)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, value.items.first { it.course.id == "44:55" }.status)
    }
    @Test fun catalogExpiresAtThirtyMinutesAndForceRefreshBypassesBothTtls() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        requests.clear()
        reader.homework("one", force = true, cachedWork = first.cache()) {}
        assertEquals(3, requests.size) // Catalog and two lists, valid signatures reused.
        assertFalse(requests.any { it.contains("stucoursemiddle") })
        now = now.plusSeconds(1801); requests.clear()
        reader.homework("one", cachedWork = first.cache()) {}
        assertEquals(6, requests.size)
    }
    @Test fun targetedForcedRefreshNeverRescansDirectoryOrOtherCourses() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        requests.clear(); status = "已提交"
        val result = reader.homework("one", targetCourseId = "44:55", force = true, cachedWork = first.cache()) {}
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("courseId=44"))
        assertTrue(result.items.single().status.isCompleted)
    }
    @Test fun accountsAndClearHaveNoCrossAccountCatalogOrDataReuse() = runBlocking {
        val reader = reader()
        reader.homework("one") {}; requests.clear()
        reader.homework("two") {}
        assertEquals(6, requests.size)
        reader.clearCaches(); requests.clear()
        reader.homework("two") {}
        assertEquals(6, requests.size)
    }
    @Test fun networkErrorsDoNotRenewSignaturesAndDoNotClaimSuccessfulUpdates() = runBlocking {
        val reader = reader()
        val first = reader.homework("one") {}
        requests.clear(); failure = IOException("network timeout")
        val failed = mutableListOf<String>()
        try { reader.homework("one", force = true, targetCourseId = "11:22", cachedWork = first.cache(), onCourseFailure = { id, _ -> failed += id }) {}; fail() }
        catch (_: IOException) { }
        assertEquals(listOf("11:22"), failed)
        assertEquals(1, requests.size)
        assertFalse(requests.any { it.contains("stucoursemiddle") })
        failure = null; requests.clear()
        reader.homeworkStatuses("one", "11:22")
        assertEquals(1, requests.size)
    }
    @Test fun fullRefreshAndSubmitReconciliationShareTheirInFlightCourseRead() = runBlocking {
        val entries = MemoryCourseReadCache().apply { rememberCatalog("one", CachedCourseCatalog(courses, true, now.toEpochMilli())) }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delegate = transport()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (url.contains("stucoursemiddle")) { started.complete(Unit); release.await() }
            delegate.get(url)
        }, entries).apply { wallTime = { now } }
        val full = async { reader.homework("one", targetCourseId = "11:22") {} }
        started.await()
        val submit = async(start = CoroutineStart.UNDISPATCHED) { reader.homeworkStatuses("one", "11:22") }
        delay(30); release.complete(Unit)
        assertEquals(full.await().items, submit.await())
        assertEquals(2, requests.size)
    }
    @Test fun pauseStopsTheRemainingCourseSweepWithoutSignatureRecovery() = runBlocking {
        val reader = reader()
        reader.homework("one") {}; requests.clear(); failure = OfficialReadPausedException("官方验证，暂停")
        try { reader.homework("one", force = true) {}; fail() } catch (_: OfficialReadPausedException) { }
        assertEquals(2, requests.size) // Forced catalog plus the first course; no second course or renewal.
    }
    @Test fun simulatedBeforeAfterRequestCountAndElapsedTime() = runBlocking {
        val reader = reader(delay = 30)
        val coldStart = System.nanoTime(); val first = reader.homework("one") {}
        val coldMillis = (System.nanoTime() - coldStart) / 1_000_000
        requests.clear()
        val legacyStart = System.nanoTime()
        // Replay the previous warm directory + list sequence against the same simulated transport.
        val legacy = transport(30)
        val parser = McpChaoxingParser()
        parser.courses(legacy.get(McpChaoxingParser.COURSE_LIST))
        courses.filter { it.lifecycle == CourseLifecycle.ACTIVE }.forEach {
            parser.homework(legacy.get(McpChaoxingParser.WORK_LIST + "?courseId=" + it.courseId), it)
        }
        assertEquals(3, requests.size)
        val legacyMillis = (System.nanoTime() - legacyStart) / 1_000_000
        requests.clear(); val start = System.nanoTime()
        reader.homework("one", cachedWork = first.cache()) {}
        val warmMillis = (System.nanoTime() - start) / 1_000_000
        assertEquals(0, requests.size)
        println("CACHE_BENCH simulated_rtt_ms=30 courses=2 old_warm_requests=3 old_warm_ms=$legacyMillis cold_requests=6 cold_ms=$coldMillis new_warm_requests=0 new_warm_ms=$warmMillis")
    }
}
