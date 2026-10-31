package com.homeworkassistant.data.mcp

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.data.web.SessionExpiredException
import com.homeworkassistant.data.session.SignatureExpiredException
import com.homeworkassistant.data.session.OfficialReadPausedException
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class McpHomeworkRead(val items: List<Homework>, val courses: Int, val completedCourses: Int,
    val complete: Boolean, val requests: Int, val detailsUnavailable: Int, val issues: List<String>,
    val scope: CourseScanScope? = null, val updates: List<CourseHomeworkUpdate> = emptyList(),
    val cacheHits: Int = 0, val catalogFromCache: Boolean = false)
data class McpExamRead(val items: List<Exam>, val excluded: Int, val unmatched: Int)

data class EnrichmentLimits(val maxItems: Int = 160, val perItemMillis: Long = 8_000, val totalMillis: Long = 120_000) {
    init { require(maxItems >= 0 && perItemMillis > 0 && totalMillis > 0) }
}
data class McpEnrichmentRead(val value: McpHomeworkRead, val attempted: Int, val budgetExpired: Boolean)

/** Same sequence as list_courses -> list_homework -> get_homework_detail in xuexitong-cli. */
@Singleton
class McpChaoxingReader @Inject constructor(private val transport: McpReadTransport,
    private val entries: CourseReadCache = MemoryCourseReadCache(), private val timing: ReadTimingRecorder = ReadTimingRecorder()) {
    private val parser = McpChaoxingParser()
    private data class Signature(val url: String, val expires: Long, val course: McpCourse)
    private data class DetailFreshness(val state: String, val expires: Long, val failed: Boolean = false)
    private data class School(val fid: String, val expires: Long)
    private val signatures = ConcurrentHashMap<String, Signature>()
    private val detailFreshness = ConcurrentHashMap<String, DetailFreshness>()
    private val schools = ConcurrentHashMap<String, School>()
    private val cacheGeneration = AtomicLong()
    private val courseFlights = SharedCourseRead<CourseNetworkRead> { timing.record("coalesced", "course_homework", System.nanoTime()) }
    private val catalogFlights = SharedCourseRead<CourseScanScope> { timing.record("coalesced", "course_catalog", System.nanoTime()) }
    internal var wallTime: () -> Instant = Instant::now
    internal var nanoTime: () -> Long = System::nanoTime
    fun clearCaches() { courseFlights.clear(); catalogFlights.clear(); cacheGeneration.incrementAndGet(); signatures.clear(); detailFreshness.clear(); schools.clear(); entries.clear() }

    suspend fun homework(accountId: String? = null, onPartial: suspend (McpHomeworkRead) -> Unit = {},
        onCoursesIdentified: suspend (CourseScanScope) -> Unit = {},
        priorityCourseId: String? = null, force: Boolean = false, targetCourseId: String? = null,
        cachedWork: Map<String, CachedCourseHomework> = emptyMap(),
        onCourseFailure: suspend (String, String) -> Unit = { _, _ -> },
        onProgress: (String) -> Unit): McpHomeworkRead = withContext(Dispatchers.Default) {
        transport.checkPaused()
        val cacheEpoch = cacheGeneration.get()
        var requests = 0
        suspend fun get(url: String): OfficialHtml {
            if (++requests > 160) throw IOException("本次读取已达到请求上限，保留已读数据，稍后可重试")
            return transport.get(url)
        }
        val catalog = accountId?.let(entries::catalog)?.takeIf { !(force && targetCourseId == null) && it.fresh(wallTime().toEpochMilli()) }
        timing.record(if (catalog == null) "cache_miss" else "cache_hit", "course_catalog", System.nanoTime())
        val scope = if (catalog != null) CourseScanScope(catalog.courses, catalog.complete)
        else catalogFlights.run(accountId ?: "anonymous") {
        onProgress("正在使用 MCP 读取流程获取课程…")
        suspend fun schoolId(): String = get(McpChaoxingParser.BASE).let { timing.parse("school") { parser.schoolId(it) } }
            ?: throw IOException("官方主页未返回学校标识，请在官方网页重新验证登录")
        val knownSchool = accountId?.let(schools::get)
        val cachedSchool = knownSchool?.takeIf { it.expires > nanoTime() }
            ?: accountId?.takeIf { knownSchool == null }?.let(entries::school)?.let { School(it, nanoTime() + 120_000_000_000L) }
        var fid = cachedSchool?.fid ?: schoolId()
        fun courseUrl() = McpChaoxingParser.COURSE_LIST.toHttpUrl().newBuilder()
            .addQueryParameter("courseType", "1").addQueryParameter("courseFid", fid)
            .addQueryParameter("courseTeacherid", "0").addQueryParameter("courseOrderBy", "0").build().toString()
        val courseUrls = ArrayDeque(listOf(courseUrl()))
        val seenCourses = mutableSetOf<String>()
        val courses = linkedMapOf<String, McpCourse>()
        var unresolved = 0
        var schoolRetried = false
        while (courseUrls.isNotEmpty()) {
            val url = courseUrls.removeFirst()
            if (!seenCourses.add(url)) throw IOException("课程分页循环，原缓存保留")
            if (seenCourses.size > 20) throw IOException("课程分页超出读取上限，原缓存保留")
            val html = get(url)
            val page = timing.parse("courses") { parser.courses(html) }
            if (!page.recognized) {
                // A school switch can invalidate the short-lived entry cache. Recover once.
                if (cachedSchool != null && !schoolRetried && seenCourses.size == 1) {
                    accountId?.let(schools::remove)
                    fid = schoolId(); schoolRetried = true
                    seenCourses.clear(); courseUrls.clear(); courseUrls.add(courseUrl())
                    continue
                }
                throw IOException("MCP 流程未识别到课程列表，原缓存保留")
            }
            unresolved += page.unresolved
            page.courses.forEach { course ->
                val old = courses[course.domain.id]
                courses[course.domain.id] = if (old != null && old.lifecycle != course.lifecycle)
                    course.copy(lifecycle = CourseLifecycle.UNKNOWN) else course
            }
            page.next.forEach(courseUrls::add)
        }
        if (accountId != null && cacheGeneration.get() == cacheEpoch && (cachedSchool == null || schoolRetried)) {
            schools[accountId] = School(fid, nanoTime() + 120_000_000_000L)
            entries.rememberSchool(accountId, fid)
        }
        val resolved = CourseScanScope(courses.values.toList(), unresolved == 0)
        if (accountId != null && cacheGeneration.get() == cacheEpoch) entries.rememberCatalog(accountId, CachedCourseCatalog(resolved.courses, resolved.catalogComplete, wallTime().toEpochMilli()))
        resolved
        }
        onCoursesIdentified(scope) // Gate all course-specific requests, including cached signatures.
        val active = scope.active.filter { targetCourseId == null || it.domain.id == targetCourseId }.sortedBy { it.domain.id != priorityCourseId }
        onProgress("课程状态已核对：未结课 ${active.size} 门，跳过已结课 ${scope.ended.size} 门")
        val items = linkedMapOf<String, Homework>()
        var completedCourses = 0
        var failedCourses = 0
        val issues = mutableListOf<String>()
        val updates = mutableListOf<CourseHomeworkUpdate>()
        var cacheHits = 0
        if (scope.unknown.isNotEmpty() || !scope.catalogComplete)
            issues += "部分课程状态或标识未识别，已跳过，请在官方页面核对"
        for ((index, course) in active.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress("MCP 读取 ${index + 1}/${active.size} 门未结课课程 · 已发现 ${items.size} 项作业")
            var courseRead = false
            try {
                val known = cachedWork[course.domain.id]
                if (!force && known != null && !known.info.dirty && ReadCacheDefaults.fresh(known.info.successfulAt, wallTime(), ReadCacheDefaults.HOMEWORK_MILLIS)) {
                    known.items.forEach { items[it.id] = it }
                    completedCourses++; cacheHits++
                    timing.record("cache_hit", "course_homework", System.nanoTime())
                } else {
                    if (requests >= 160) throw IOException("本次读取已达到请求上限，保留已读数据，稍后可重试")
                    timing.record("cache_miss", "course_homework", System.nanoTime())
                    val result = courseNetwork(accountId, course)
                    requests += result.requests
                    result.update.items.forEach { items[it.id] = it }
                    updates += result.update
                    if (result.update.complete) completedCourses++
                }
                courseRead = true
            } catch (e: CancellationException) { throw e }
              catch (e: SessionExpiredException) { accountId?.let(::invalidateSession); throw e }
              catch (e: OfficialReadPausedException) { throw e }
              catch (e: IOException) {
                failedCourses++
                onCourseFailure(course.domain.id, e.message ?: "课程读取失败")
                if (issues.size < 3) issues += "第 ${index + 1} 门课程：${e.message.orEmpty().take(100)}"
            }
            if (courseRead) onPartial(McpHomeworkRead(items.values.toList(), scope.active.size,
                completedCourses, false, requests, 0, issues.toList(), scope, updates.toList(), cacheHits, catalog != null))
        }
        if (items.isEmpty() && (failedCourses > 0 || completedCourses != active.size))
            throw IOException("MCP 流程读取 ${active.size} 门未结课课程，${failedCourses} 门读取失败；未确认空作业，原缓存保留。${issues.joinToString("；")}")
        McpHomeworkRead(items.values.toList(), scope.active.size, completedCourses,
            targetCourseId == null && failedCourses == 0 && completedCourses == active.size && scope.catalogComplete && scope.unknown.isEmpty(),
            requests, 0, issues, scope, updates.toList(), cacheHits, catalog != null)
    }

    private data class CourseNetworkRead(val update: CourseHomeworkUpdate, val requests: Int)
    fun resumeAfterVerification() = transport.resumeAfterVerification()
    fun invalidateSession(accountId: String) {
        entries.invalidateCatalog(accountId)
        signatures.keys.removeAll { it.startsWith("$accountId|") }
        schools.remove(accountId)
    }
    fun allowsCourse(accountId: String, courseId: String): Boolean {
        val catalog = entries.catalog(accountId)?.takeIf { it.complete && it.fresh(wallTime().toEpochMilli()) }
        if (catalog != null) return catalog.courses.firstOrNull { it.domain.id == courseId }?.lifecycle == CourseLifecycle.ACTIVE
        return entries.course(accountId, courseId)?.lifecycle?.let { it == CourseLifecycle.ACTIVE } ?: true
    }
    suspend fun courseUpdate(accountId: String, courseId: String): CourseHomeworkUpdate? {
        transport.checkPaused()
        if (!allowsCourse(accountId, courseId)) return null
        val ids = courseId.split(':')
        require(ids.size == 2 && ids.all { it.matches(Regex("\\d+")) }) { "当前课程标识无效" }
        val known = entries.course(accountId, courseId)
            ?: McpCourse(ids[0], ids[1], "", "", CourseLifecycle.ACTIVE)
        if (known.lifecycle != CourseLifecycle.ACTIVE) return null
        return courseNetwork(accountId, known).update
    }
    suspend fun homeworkStatuses(accountId: String, courseId: String): List<Homework> = courseUpdate(accountId, courseId)?.items.orEmpty()

    /** Full refresh, page load and submit reconciliation share this exact request. */
    private suspend fun courseNetwork(accountId: String?, initialCourse: McpCourse): CourseNetworkRead =
        courseFlights.run("${accountId.orEmpty()}|${initialCourse.domain.id}") {
            val cacheEpoch = cacheGeneration.get()
            var course = initialCourse
            var requests = 0
            suspend fun get(url: String): OfficialHtml { requests++; return transport.get(url) }
            val cached = accountId?.let { id -> signatures.entries.firstOrNull { (key, value) ->
                key.startsWith("$id|${course.domain.id}|") && value.expires > nanoTime()
            } }
            suspend fun renew(): String {
                val url = McpChaoxingParser.COURSE_MIDDLE.toHttpUrl().newBuilder()
                    .addQueryParameter("courseid", course.courseId).addQueryParameter("clazzid", course.classId)
                    .apply { if (course.cpi.isNotBlank()) addQueryParameter("cpi", course.cpi) }
                    .addQueryParameter("ismooc2", "1").addQueryParameter("v", "2").build().toString()
                val html = get(url)
                if (course.cpi.isBlank()) {
                    val cpi = timing.parse("course_entry") { org.jsoup.Jsoup.parse(html.html)
                        .selectFirst("input#cpi, input#curPersonId, input[name=cpi]")?.attr("value") }
                    if (cpi?.matches(Regex("\\d+")) == true) course = course.copy(cpi = cpi)
                }
                return timing.parse("signature") { parser.workListUrl(course, html, wallTime()) }
            }
            fun remember(list: String) {
                if (accountId == null || cacheGeneration.get() != cacheEpoch) return
                entries.remember(accountId, listOf(course))
                val key = "$accountId|${course.domain.id}|${course.cpi}"
                signatures.keys.removeAll { it.startsWith("$accountId|${course.domain.id}|") && it != key }
                signatures[key] = Signature(list, nanoTime() + 600_000_000_000L, course)
            }
            suspend fun read(list: String): Pair<List<Homework>, Boolean> {
                val queue = ArrayDeque(listOf(list.toHttpUrl().newBuilder().setQueryParameter("t", wallTime().toEpochMilli().toString()).build().toString()))
                val seen = mutableSetOf<String>()
                val items = linkedMapOf<String, Homework>()
                var complete = true
                while (queue.isNotEmpty()) {
                    val url = queue.removeFirst()
                    if (!seen.add(url) || seen.size > 20) throw IOException("作业分页循环或超出上限")
                    val html = get(url)
                    val page = timing.parse("homework") { parser.homework(html, course) }
                    complete = complete && (page.complete || page.next.isNotEmpty())
                    page.items.forEach { item ->
                        val old = items.putIfAbsent(item.id, item)
                        if (old != null && old.status != item.status) throw IOException("官方作业分页状态冲突")
                    }
                    page.next.forEach(queue::add)
                }
                return items.values.toList() to complete
            }
            val list = cached?.value?.url ?: renew()
            val result = try { read(list).also { if (cached == null) remember(list) } }
                catch (e: SignatureExpiredException) {
                    if (cached == null) throw e
                    signatures.remove(cached.key, cached.value)
                    val renewed = renew()
                    read(renewed).also { remember(renewed) }
                } catch (e: SessionExpiredException) { accountId?.let(::invalidateSession); throw e }
            // A real list refresh also makes later deadline changes eligible for enrichment.
            if (accountId != null) result.first.forEach { detailFreshness.remove("$accountId|${it.id}") }
            CourseNetworkRead(CourseHomeworkUpdate(course.domain, result.first, result.second, wallTime()), requests)
        }

    /** Optional details cannot delay publishing the submission states. Keep successful results on timeout. */
    suspend fun enrich(value: McpHomeworkRead, limits: EnrichmentLimits = EnrichmentLimits(), accountId: String? = null,
        onUpdated: suspend (McpHomeworkRead) -> Unit = {}, onProgress: (String) -> Unit = {}): McpEnrichmentRead =
        withContext(Dispatchers.Default) {
            val cacheEpoch = cacheGeneration.get()
            val items = value.items.associateByTo(linkedMapOf()) { it.id }
            fun state(item: Homework) = "${item.status}|${item.rawStatus.orEmpty()}"
            fun freshness(item: Homework): DetailFreshness? = accountId?.let { detailFreshness["$it|${item.id}"] }
                ?.takeIf { it.expires > nanoTime() && it.state == state(item) }
            val updatedCourses = value.updates.mapTo(hashSetOf()) { it.course.id }
            val usingDataCache = value.cacheHits > 0
            val eligible = value.items.filter { it.status.isPending && (!usingDataCache || it.course.id in updatedCourses || (it.dueAt == null && it.dueText == null)) }
                .sortedBy { it.dueAt != null || it.dueText != null } +
                value.items.filter { it.status.isCompleted && ((it.dueAt == null && it.dueText == null) || it.score == null) }
            val deferredFailures = eligible.count { freshness(it)?.failed == true }
            val candidates = eligible.filter { freshness(it) == null }
            val capacity = minOf(limits.maxItems, (160 - value.requests).coerceAtLeast(0))
            var attempted = 0
            var detailRequests = 0
            var succeeded = 0
            fun current() = value.copy(items = items.values.toList(), requests = value.requests + detailRequests,
                detailsUnavailable = candidates.size - succeeded + deferredFailures)
            val finished = withTimeoutOrNull(limits.totalMillis) {
                for (item in candidates.take(capacity)) {
                    currentCoroutineContext().ensureActive()
                    if (value.requests + detailRequests >= 160) break
                    attempted++
                    onProgress("作业状态已显示 · 补充详情 $attempted/${minOf(candidates.size, capacity)}（最多 ${limits.totalMillis / 1000} 秒，可停止）")
                    val enriched = try {
                        withTimeoutOrNull(limits.perItemMillis) {
                            val queue = ArrayDeque(listOf(item.detailUrl))
                            val seen = hashSetOf<String>()
                            var result: Homework? = null
                            while (queue.isNotEmpty() && seen.size < 3 && value.requests + detailRequests < 160) {
                                val url = queue.removeFirst()
                                if (!seen.add(url)) continue
                                detailRequests++
                                val page = transport.get(url)
                                val parsed = timing.parse("details") { parser.details(item, page) }
                                result = parsed.copy(publishedAt = parsed.publishedAt ?: result?.publishedAt,
                                    score = parsed.score ?: result?.score, maxScore = parsed.maxScore ?: result?.maxScore)
                                if (parsed.dueAt != null || parsed.dueText != null) break
                                parser.detailDocuments(item, page).filterNot { it in seen }.forEach(queue::add)
                            }
                            result
                        }
                    } catch (e: CancellationException) { throw e }
                      catch (e: SessionExpiredException) { accountId?.let(::invalidateSession); throw e }
                      catch (e: OfficialReadPausedException) { throw e }
                      catch (_: IOException) { null }
                    if (enriched != null) {
                        val merged = enriched.copy(status = item.status, rawStatus = item.rawStatus, publishedAt = enriched.publishedAt ?: item.publishedAt,
                            dueAt = if (enriched.dueText != null) enriched.dueAt else enriched.dueAt ?: item.dueAt, dueText = enriched.dueText ?: item.dueText, score = enriched.score ?: item.score,
                            maxScore = enriched.maxScore ?: item.maxScore)
                        items[item.id] = merged
                        val hasDeadline = merged.dueAt != null || merged.dueText != null
                        val useful = hasDeadline || (!item.status.isPending &&
                            (enriched.score != null || enriched.maxScore != null || enriched.publishedAt != null))
                        if (useful) succeeded++
                        onUpdated(current())
                        if (accountId != null && cacheGeneration.get() == cacheEpoch)
                            // An HTTP 200 response without usable task metadata is not a successful detail read.
                            detailFreshness["$accountId|${item.id}"] = DetailFreshness(state(item),
                                nanoTime() + if (useful) 600_000_000_000L else 60_000_000_000L, failed = !useful)
                    } else if (accountId != null && cacheGeneration.get() == cacheEpoch) {
                        // A slow/missing detail must not stall every subsequent pull-to-refresh.
                        detailFreshness["$accountId|${item.id}"] = DetailFreshness(state(item), nanoTime() + 60_000_000_000L, failed = true)
                    }
                }
                true
            } ?: false
            McpEnrichmentRead(current(), attempted, !finished)
    }

    suspend fun exams(scope: CourseScanScope?, onProgress: (String) -> Unit): McpExamRead = withContext(Dispatchers.Default) {
        if (scope == null) throw IOException("尚未取得课程状态，已跳过考试读取，请先重新获取课程")
        if (scope.active.isEmpty()) {
            if (!scope.catalogComplete || scope.unknown.isNotEmpty()) throw IOException("课程状态尚未确认，已跳过考试读取")
            return@withContext McpExamRead(emptyList(), 0, 0)
        }
        onProgress("正在使用 MCP 读取流程获取考试安排…")
        val queue = ArrayDeque(listOf(McpChaoxingParser.EXAM_LIST))
        val seen = mutableSetOf<String>()
        val items = linkedMapOf<String, Exam>()
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (!seen.add(url) || seen.size > 20) throw IOException("考试分页循环或超出上限，原缓存保留")
            val html = transport.get(url)
            val (rows, next) = timing.parse("exams") { parser.exams(html) }
            rows.forEach { items[it.id] = it }
            next.forEach(queue::add)
        }
        var excluded = 0
        var unmatched = 0
        val eligible = items.values.mapNotNull { item ->
            val course = scope.courseFor(item)
            when (course?.lifecycle) {
                CourseLifecycle.ACTIVE -> item.copy(courseId = course.courseId, classId = course.classId, courseName = course.name)
                CourseLifecycle.ENDED, CourseLifecycle.UNAVAILABLE -> { excluded++; null }
                else -> { unmatched++; item.copy(courseName = null) }
            }
        }
        McpExamRead(eligible, excluded, unmatched)
    }
}
