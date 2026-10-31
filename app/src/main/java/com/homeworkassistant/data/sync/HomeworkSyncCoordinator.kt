package com.homeworkassistant.data.sync

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.web.SessionExpiredException
import com.homeworkassistant.data.session.OfficialReadPausedException
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.homeworkassistant.domain.usecase.MergePartialSnapshotUseCase

data class SyncResult(val partial: Boolean, val pages: Int, val message: String? = null,
    val homeworkUpdated: Boolean = true, val pendingDetails: PendingHomeworkDetails? = null,
    val examMessage: String? = null, val pendingExams: PendingExamSync? = null, val networkUpdated: Boolean = true)

/** A seed is valid only until the next sync/account/cache generation. */
data class PendingHomeworkDetails(val accountId: String, val generation: Long, val value: McpHomeworkRead)
data class PendingExamSync(val accountId: String, val generation: Long, val scope: CourseScanScope)
data class ExamSyncResult(val message: String, val partial: Boolean)

/** Primary refresh exclusively uses the xuexitong-cli reader, never WebView DOM traversal. */
@Singleton
class HomeworkSyncCoordinator @Inject constructor(
    private val reader: McpChaoxingReader,
    private val repository: HomeworkRepository,
    private val exams: ExamRepository,
    private val timing: ReadTimingRecorder = ReadTimingRecorder(),
) {
    private val gate = Mutex()
    private val syncFlights = SharedCourseRead<SyncResult> { timing.record("coalesced", "overview_refresh", System.nanoTime()) }
    private val detailGate = Mutex()
    private val examGate = Mutex()
    private val statusGate = Mutex()
    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statusJobs = ConcurrentHashMap<String, Deferred<Int>>()
    private val statusFresh = ConcurrentHashMap<String, Pair<Long, Long>>()
    @Volatile private var preferredCourse: Pair<String, String>? = null
    internal var nanoTime: () -> Long = System::nanoTime
    private val generation = AtomicLong()
    fun invalidate() {
        generation.incrementAndGet()
        statusJobs.values.forEach { it.cancel() }; statusJobs.clear(); statusFresh.clear()
    }
    fun clearReadCache() = reader.clearCaches()
    fun resumeAfterVerification() = reader.resumeAfterVerification()
    fun preferCourse(accountId: String, courseId: String) { preferredCourse = accountId to courseId }
    fun preferredCourseId(accountId: String?) = preferredCourse?.takeIf { it.first == accountId }?.second

    suspend fun checkHomeworkStatus(accountId: String, homeworkId: String): Int {
        val existing = repository.observe(accountId).first()?.items?.firstOrNull { it.id == homeworkId }
            ?: return 0
        preferCourse(accountId, existing.course.id)
        val key = "$accountId|${existing.course.id}"
        val task = statusGate.withLock {
            statusJobs[key]?.takeIf { !it.isCompleted }?.also {
                timing.record("coalesced", "status_inflight", System.nanoTime())
            } ?: run {
                val current = generation.get()
                val fresh = statusFresh[key]
                if (fresh?.first == current && nanoTime() - fresh.second < 1_500_000_000L) {
                    timing.record("cache_hit", "status_freshness", System.nanoTime())
                    return@withLock CompletableDeferred(0)
                }
                val job = statusScope.async(start = CoroutineStart.LAZY) {
                    run statusRead@ {
                        if (current != generation.get()) return@statusRead 0
                        val update = try { withTimeoutOrNull(20_000) { reader.courseUpdate(accountId, existing.course.id) } }
                            catch (e: SessionExpiredException) {
                                reader.invalidateSession(accountId)
                                repository.courseReadFailed(accountId, existing.course.id, e.message ?: "登录失效")
                                throw e
                            }
                            catch (e: IOException) { repository.courseReadFailed(accountId, existing.course.id, e.message ?: "状态读取失败"); throw e }
                            ?: if (!reader.allowsCourse(accountId, existing.course.id)) return@statusRead 0
                                else throw IOException("作业状态核对超时，现有数据已保留")
                        currentCoroutineContext().ensureActive()
                        if (current != generation.get()) return@statusRead 0
                        if (!reader.allowsCourse(accountId, existing.course.id)) return@statusRead 0
                        val old = repository.observe(accountId).first() ?: return@statusRead 0
                        val merged = old.items.filterNot { it.course.id == existing.course.id && update.complete }.associateByTo(linkedMapOf()) { it.id }
                        update.items.forEach { merged[it.id] = it }
                        repository.publishRead(old.copy(items = merged.values.toList(), revision = old.revision + 1), listOf(update), true)
                        statusFresh[key] = current to nanoTime()
                        update.items.count { row -> old.items.firstOrNull { it.id == row.id }?.let { it.status != row.status } == true }
                    }
                }
                statusJobs[key] = job
                job.invokeOnCompletion { statusJobs.remove(key, job) }
                job.start(); job
            }
        }
        return task.await()
    }

    suspend fun sync(accountId: String, onHomeworkPublished: () -> Unit = {}, includeDetails: Boolean = true,
        includeExams: Boolean = true,
        force: Boolean = false, targetCourseId: String? = null,
        onProgress: (String) -> Unit = {}): SyncResult = syncFlights.run("$accountId|$force|$targetCourseId|$includeDetails|$includeExams") { gate.withLock {
        val current = generation.incrementAndGet()
        fun checkSession() { check(current == generation.get()) { "会话已切换，本次读取已取消" } }
        val messages = mutableListOf<String>()
        var pages = 0
        var partial = false
        var homeworkSucceeded = false
        var examsSucceeded = false
        var examMessage: String? = null
        var base: McpHomeworkRead? = null
        var scope: CourseScanScope? = null
        val publishedUpdates = mutableSetOf<Pair<String, Instant>>()
        suspend fun publish(value: McpHomeworkRead) {
            currentCoroutineContext().ensureActive(); checkSession()
            val now = Instant.now()
            val updates = value.updates.filter { publishedUpdates.add(it.course.id to it.successfulAt) }
            val previous = if (targetCourseId != null) repository.observe(accountId).first() else null
            repository.publishRead(HomeworkSnapshot(accountId, now.toEpochMilli(), value.items, now,
                previous?.coverage ?: if (value.complete) SnapshotCoverage.COMPLETE else SnapshotCoverage.PARTIAL,
                value.courses, previous?.completedCourses ?: value.completedCourses), updates, preserveUnseen = !value.complete)
        }
        try {
            var previous = repository.observe(accountId).first()
            var previousById = previous?.items?.associateBy { it.id }.orEmpty()
            fun retainDetails(value: McpHomeworkRead): McpHomeworkRead {
                val snapshot = HomeworkSnapshot(accountId, 0, value.items, Instant.now(), SnapshotCoverage.PARTIAL,
                    value.courses, value.completedCourses)
                val known = MergePartialSnapshotUseCase()(previous, snapshot).items.associateBy { it.id }
                return value.copy(items = value.items.map { fresh ->
                    val old = previousById[fresh.id]
                    known.getValue(fresh.id).copy(status = fresh.status, rawStatus = fresh.rawStatus,
                        score = fresh.score ?: old?.score?.takeIf { fresh.status.isCompleted && old.status.isCompleted && old.rawStatus == fresh.rawStatus })
                })
            }
            val cachedWork = repository.courseCache(accountId)
            val value = withTimeoutOrNull(240_000) { reader.homework(accountId, onPartial = {
                val ready = retainDetails(it)
                publish(ready)
                base = ready
                homeworkSucceeded = true
                onHomeworkPublished()
            }, onCoursesIdentified = { identified ->
                currentCoroutineContext().ensureActive(); checkSession()
                scope = identified
                val excluded = identified.excludedCourseIds
                previous?.let { old ->
                    val activeIds = identified.active.mapTo(hashSetOf()) { it.domain.id }
                    val kept = old.items.filterNot { it.course.id in excluded ||
                        (identified.catalogComplete && identified.unknown.isEmpty() && it.course.id !in activeIds) }
                    if (kept.size != old.items.size) {
                        val pruned = old.copy(items = kept, coverage = SnapshotCoverage.PARTIAL,
                            discoveredCourses = identified.active.size, completedCourses = 0)
                        repository.publish(pruned, preserveUnseen = false)
                        previous = pruned
                        previousById = pruned.items.associateBy { it.id }
                        onHomeworkPublished()
                    }
                }
                exams.observe(accountId).first()?.let { old ->
                    val kept = old.items.filterNot(identified::excludes)
                    if (kept.size != old.items.size) {
                        currentCoroutineContext().ensureActive(); checkSession()
                        exams.publish(old.copy(items = kept))
                    }
                }
                messages += "课程核对：未结课 ${identified.active.size} 门，已跳过已结课 ${identified.ended.size} 门、未开放 ${identified.courses.count { it.lifecycle == CourseLifecycle.UNAVAILABLE }} 门"
            }, priorityCourseId = preferredCourse?.takeIf { it.first == accountId }?.second,
                force = force, targetCourseId = targetCourseId, cachedWork = cachedWork,
                onCourseFailure = { id, message -> repository.courseReadFailed(accountId, id, message) },
                onProgress = onProgress) } ?: throw IOException("MCP 作业读取超时，已读作业和其他缓存均保留")
            val ready = retainDetails(value)
            publish(ready)
            base = ready
            onHomeworkPublished()
            onProgress(if (includeExams) "作业完成状态已同步，正在读取考试…" else "作业完成状态已同步")
            pages = value.requests
            partial = !value.complete
            homeworkSucceeded = true
            messages += "MCP 读取作业：未完成 ${value.items.count { it.status.isPending }} 项、已完成 ${value.items.count { it.status.isCompleted }} 项、状态待识别 ${value.items.count { it.status == HomeworkStatus.UNKNOWN }} 项"
            messages += "目录${if (value.catalogFromCache) "使用缓存" else "已更新"}；${value.cacheHits} 门课程复用缓存，${value.updates.size} 门已重新读取"
            if (!value.complete) messages += "部分课程或分页尚未确认，其他作业缓存保留"
            messages += value.issues
        } catch (e: CancellationException) { throw e }
          catch (e: SessionExpiredException) {
              reader.invalidateSession(accountId)
              repository.courseCache(accountId).keys.forEach { repository.courseReadFailed(accountId, it, e.message ?: "登录失效") }
              throw e
          }
          catch (e: OfficialReadPausedException) { throw e }
          catch (e: IOException) { partial = true; messages += e.message.orEmpty() }
        if (includeExams) try {
            val value = withTimeoutOrNull(120_000) { reader.exams(scope, onProgress) }
                ?: throw IOException("MCP 考试读取超时，原考试缓存保留")
            currentCoroutineContext().ensureActive(); checkSession()
            exams.publish(ExamSnapshot(accountId, value.items, Instant.now()))
            examsSucceeded = true
            messages += "读取考试 ${value.items.size} 项，已排除结课或未开放课程考试 ${value.excluded} 项"
            if (value.unmatched > 0) {
                partial = true
                examMessage = "${value.unmatched} 项考试的课程关联待确认，已保留展示，请在官方页面核对课程状态"
                messages += examMessage
            }
        } catch (e: CancellationException) { throw e }
          catch (e: SessionExpiredException) { throw e }
          catch (e: IOException) {
              partial = true
              examMessage = "考试读取未完成：${e.message.orEmpty()}"
              messages += examMessage
          }
        val pending = base?.let { PendingHomeworkDetails(accountId, current, it) }
        if (includeDetails && pending != null) {
            val enriched = enrichLocked(pending, onProgress)
            if (enriched != null) {
                pages = enriched.value.requests
                if (enriched.value.detailsUnavailable > 0) messages +=
                    "作业状态已保存，${enriched.value.detailsUnavailable} 项详情未补齐；截止时间或得分沿用缓存或显示未知"
                if (enriched.budgetExpired) messages += "详情补充已达到 ${EnrichmentLimits().totalMillis / 1000} 秒上限，已结束"
            }
        }
        if (!homeworkSucceeded && !examsSucceeded && (includeExams || scope == null)) throw IOException(messages.joinToString("；"))
        SyncResult(partial, pages, messages.joinToString("；"), homeworkSucceeded, pending.takeUnless { includeDetails }, examMessage,
            scope?.let { PendingExamSync(accountId, current, it) }?.takeUnless { includeExams || targetCourseId != null }, base?.updates?.isNotEmpty() == true)
    } }

    suspend fun syncExams(pending: PendingExamSync, onProgress: (String) -> Unit = {}): ExamSyncResult? = examGate.withLock {
        if (generation.get() != pending.generation) return@withLock null
        val value = withTimeoutOrNull(120_000) { reader.exams(pending.scope, onProgress) }
            ?: throw IOException("考试读取超时，原考试缓存保留")
        currentCoroutineContext().ensureActive()
        if (generation.get() != pending.generation) return@withLock null
        exams.publish(ExamSnapshot(pending.accountId, value.items, Instant.now()))
        ExamSyncResult(if (value.unmatched > 0) "${value.unmatched} 项考试的课程关联待确认，已保留展示，请在官方页面核对课程状态"
            else "考试已同步 ${value.items.size} 项", value.unmatched > 0)
    }

    /** Optional work is separate so a finished list refresh never waits for a detail page. */
    suspend fun enrichDetails(pending: PendingHomeworkDetails, onProgress: (String) -> Unit = {}): McpEnrichmentRead? =
        detailGate.withLock { enrichLocked(pending, onProgress) }

    private suspend fun enrichLocked(pending: PendingHomeworkDetails, onProgress: (String) -> Unit): McpEnrichmentRead? {
        if (generation.get() != pending.generation) return null
        repository.observe(pending.accountId).first() ?: return null
        suspend fun checkSession() {
            currentCoroutineContext().ensureActive()
            check(generation.get() == pending.generation) { "会话已切换，本次详情补充已取消" }
        }
        return reader.enrich(pending.value, accountId = pending.accountId, onUpdated = { updated ->
            checkSession()
            repository.updateDetails(pending.accountId, updated.items)
        }, onProgress = { progress ->
            check(generation.get() == pending.generation) { "详情补充已取消" }
            onProgress(progress)
        })
    }
}
