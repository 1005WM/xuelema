package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.*
import com.homeworkassistant.domain.usecase.MergePartialSnapshotUseCase
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class CourseScanScopeTest {
    private val parser = McpChaoxingParser()
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun catalog(html: String = fixture("mcp_course_lifecycle.html")) =
        parser.courses(OfficialHtml(McpChaoxingParser.COURSE_LIST, html))
    private val active = McpCourse("11", "22", "33", "高等数学", CourseLifecycle.ACTIVE)
    private val ended = McpCourse("44", "55", "66", "大学英语", CourseLifecycle.ENDED)
    private fun exam(courseId: String? = null, classId: String? = null, name: String? = null) =
        Exam("exam", "高等数学期末", "另行通知", null, null, "60", "已结束", "未交卷", "--", "线上",
            HomeworkStatus.NOT_SUBMITTED, courseId, classId, name)

    @Test fun courseCardsUseOnlyTheirOwnVisibleLifecycleAndClosedCardsNeedNoSignature() {
        val value = catalog()
        assertTrue(value.recognized)
        assertEquals(0, value.unresolved)
        assertEquals(listOf(CourseLifecycle.ACTIVE, CourseLifecycle.ENDED, CourseLifecycle.UNAVAILABLE, CourseLifecycle.UNKNOWN), value.courses.map { it.lifecycle })
        assertEquals("", value.courses[1].cpi)
        assertEquals(setOf("44:55", "77:88"), CourseScanScope(value.courses).excludedCourseIds)
    }
    @Test fun titleAndHomeworkCompletionNeverDecideWhetherCourseHasEnded() {
        val html = "<li><a href='https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=11&amp;clazzid=22&amp;cpi=33'>进入</a><span class='course-name' title='已结课案例分析'></span><p>全部作业已完成</p></li>"
        assertEquals(CourseLifecycle.ACTIVE, catalog(html).courses.single().lifecycle)
    }
    @Test fun missingCpiOnAnActiveCardIsUnknownAndNotScanned() {
        val html = fixture("mcp_course_lifecycle.html").replace("<input class=\"cpi\" value=\"33\">", "")
        assertEquals(CourseLifecycle.UNKNOWN, catalog(html).courses.first().lifecycle)
    }
    @Test fun conflictingDuplicateCourseStatesBecomeUnknown() {
        val html = "<div class=course><input class=courseId value=11><input class=clazzId value=22><input class=cpi value=33><span class=course-name>数学</span>%s</div>"
        val value = catalog(html.format("") + html.format("<a class=not-open-tip>课程已结束</a>"))
        assertEquals(CourseLifecycle.UNKNOWN, value.courses.single().lifecycle)
    }
    @Test fun explicitLabelsDoNotConfuseNotEndedWithEnded() {
        listOf("课程已结束", "已结课", "已结束").forEach { assertEquals(CourseLifecycle.ENDED, parser.courseLifecycle(it)) }
        assertEquals(CourseLifecycle.ACTIVE, parser.courseLifecycle("未结课"))
        assertEquals(CourseLifecycle.UNKNOWN, parser.courseLifecycle("未知状态"))
    }
    @Test fun examAssociationRequiresUnambiguousIdsOrUniqueExplicitCourseName() {
        val scope = CourseScanScope(listOf(active, ended))
        assertTrue(scope.includes(exam("11", "22")))
        assertFalse(scope.includes(exam("44", "55")))
        assertFalse(scope.includes(exam())) // Exam title is never a course association.
        assertTrue(scope.includes(exam(name = "高等数学")))
        assertFalse(CourseScanScope(listOf(active, active.copy(classId = "23", lifecycle = CourseLifecycle.ENDED)))
            .includes(exam("11")))
        assertFalse(CourseScanScope(listOf(active, ended.copy(name = "高等数学"))).includes(exam(name = "高等数学")))
    }
    @Test fun conflictingExamLinkIdsCannotFallBackToTheCourseName() {
        val html = fixture("mcp_exams.html").replace("data-courseid=\"11\"", "data-courseid=\"11\" data-course-name=\"高等数学\"")
            .replace("<td>查看</td>", "<td><a href='https://mooc1.chaoxing.com/exam?courseId=44'>查看</a></td>")
        val row = parser.exams(OfficialHtml(McpChaoxingParser.EXAM_LIST, html)).first.first()
        assertEquals("", row.courseId)
        assertFalse(CourseScanScope(listOf(active, ended)).includes(row))
    }
    private fun reader(courses: () -> String, requests: MutableList<String>, failWork: Boolean = false,
        extraExams: String = "", failExams: Boolean = false, onCourseRequest: () -> Unit = {}) = McpChaoxingReader(McpReadTransport { url ->
        requests += url
        val parsed = url.toHttpUrl()
        val html = when (parsed.encodedPath) {
            "/base" -> "<input id=fid value=123>"
            "/mooc2-ans/visit/courselistdata" -> courses()
            "/visit/stucoursemiddle" -> {
                onCourseRequest()
                assertEquals("11", parsed.queryParameter("courseid"))
                if (failWork) throw IOException("active course unavailable")
                "<input id=workEnc value=test><input id=enc value=page>"
            }
            "/mooc2/work/list" -> {
                onCourseRequest(); assertEquals("11", parsed.queryParameter("courseId")); fixture("mcp_homework.html")
            }
            "/mooc-ans/mooc2/work/task" -> "截止时间：2026-10-29 09:00"
            "/exam-ans/exam/test/examcode/examlist" -> {
                if (failExams) throw IOException("模拟考试读取失败")
                fixture("mcp_exams.html") + extraExams
            }
            else -> throw AssertionError("Unexpected endpoint: ${parsed.encodedPath}")
        }
        OfficialHtml(url, html)
    })
    @Test fun scopeIsPublishedBeforeAnyCourseRequestAndOnlyActiveCoursesAreScanned() = runBlocking {
        val requests = mutableListOf<String>()
        var identified = false
        val value = reader({ fixture("mcp_course_lifecycle.html") }, requests, onCourseRequest = { assertTrue(identified) })
            .homework(onCoursesIdentified = { identified = true }) {}
        assertEquals(1, value.courses)
        assertEquals(1, value.completedCourses)
        assertFalse(value.complete) // Unknown fourth course prevents a claim of full coverage.
        assertEquals(4, value.items.size)
        assertEquals(4, requests.size)
        assertTrue(value.issues.any { it.contains("状态或标识未识别") })
    }
    @Test fun newlyEndedCourseDoesNotReuseCachedWorkSignature() = runBlocking {
        val requests = mutableListOf<String>()
        var html = "<div class=course><input class=courseId value=11><input class=clazzId value=22><input class=cpi value=33><span class=course-name>数学</span></div>"
        val reader = reader({ html }, requests)
        reader.homework("account") {}
        requests.clear()
        html = html.replace("</div>", "<a class=not-open-tip>课程已结束</a></div>")
        val value = reader.homework("account", force = true) {}
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("courselistdata")) // Lifecycle is freshly checked even with entry caches.
        assertTrue(value.items.isEmpty()); assertTrue(value.complete)
        assertEquals(0, value.courses)
        val exams = reader.exams(value.scope) {}
        assertTrue(exams.items.isEmpty()); assertEquals(1, requests.size)
    }
    @Test fun examsExcludeClosedButKeepUnassociatedRowsForConfirmation() = runBlocking {
        val requests = mutableListOf<String>()
        val unknownRow = "<table><tr class=dataTr data-id=unassociated><td>4</td><td>高等数学期末</td><td>另行通知</td><td>60</td><td>待开始</td><td>未交卷</td><td>--</td><td>线上</td><td>查看</td></tr></table>"
        val value = reader({ fixture("mcp_course_lifecycle.html") }, requests, extraExams = unknownRow)
            .exams(CourseScanScope(listOf(active, ended))) {}
        assertEquals(3, value.items.size); assertEquals(1, value.excluded); assertEquals(1, value.unmatched)
        assertTrue(value.items.filter { it.id != "unassociated" }.all { it.courseId == "11" && it.classId == "22" && it.courseName == "高等数学" })
        assertNull(value.items.single { it.id == "unassociated" }.courseName)
    }
    @Test fun examsWithoutCourseMetadataAreNotPublishedAsEmpty() = runBlocking {
        val html = fixture("mcp_exams.html").replace(Regex(" data-courseid=\"\\d+\" data-classid=\"\\d+\""), "")
        val reader = McpChaoxingReader(McpReadTransport { OfficialHtml(it, html) })
        val value = reader.exams(CourseScanScope(listOf(active, ended))) {}
        assertEquals(3, value.items.size)
        assertEquals(3, value.unmatched)
        assertEquals(0, value.excluded)
        assertTrue(value.items.all { it.courseId == null && it.courseName == null })
    }
    @Test fun noCourseScopeOrUnknownOnlyScopeNeverRequestsExams() = runBlocking {
        val requests = mutableListOf<String>()
        val reader = reader({ "" }, requests)
        for (scope in listOf(null, CourseScanScope(listOf(active.copy(lifecycle = CourseLifecycle.UNKNOWN))))) {
            try { reader.exams(scope) {}; fail("Expected scope error") } catch (_: IOException) { }
        }
        assertTrue(requests.isEmpty())
    }
    private class HomeworkCache(initial: HomeworkSnapshot?) : HomeworkRepository {
        val state = MutableStateFlow(initial)
        val values = mutableListOf<HomeworkSnapshot>()
        override fun observe(accountId: String) = state
        override suspend fun publish(snapshot: HomeworkSnapshot, preserveUnseen: Boolean) {
            state.value = if (preserveUnseen) MergePartialSnapshotUseCase()(state.value, snapshot) else snapshot
            values += state.value!!
        }
        override suspend fun clear() { state.value = null }
        override suspend fun updateStatuses(accountId: String, updates: List<Homework>): Int = error("Not used in scope tests")
        override suspend fun updateDetails(accountId: String, updates: List<Homework>): Int {
            val old = state.value ?: return 0
            val updated = com.homeworkassistant.domain.usecase.ApplyHomeworkDetailsUseCase()(old, updates)
            if (old == updated) return 0
            state.value = updated; values += updated
            return old.items.zip(updated.items).count { it.first != it.second }
        }
    }
    private class ExamCache(initial: ExamSnapshot?) : ExamRepository {
        val state = MutableStateFlow(initial)
        override fun observe(accountId: String) = state
        override suspend fun publish(snapshot: ExamSnapshot) { state.value = snapshot }
        override suspend fun clear() { state.value = null }
    }
    @Test fun failedExamRefreshKeepsUnassociatedCacheAndReportsItsOwnError() = runBlocking {
        val unknown = exam().copy(id = "unknown")
        val exams = ExamCache(ExamSnapshot("account", listOf(unknown, exam("44", "55")), Instant.EPOCH))
        val result = HomeworkSyncCoordinator(reader({ fixture("mcp_course_lifecycle.html") }, mutableListOf(), failExams = true),
            HomeworkCache(null), exams).sync("account", includeDetails = false)
        assertEquals(listOf(unknown), exams.state.value!!.items)
        assertEquals(Instant.EPOCH, exams.state.value!!.fetchedAt)
        assertTrue(result.examMessage.orEmpty().contains("模拟考试读取失败"))
    }
    @Test fun endedHomeworkIsPrunedBeforePartialMergeAndCannotReappearDuringEnrichment() = runBlocking {
        val old = Homework("old-ended", ended.domain, "旧作业", McpChaoxingParser.WORK_LIST, status = HomeworkStatus.NOT_SUBMITTED)
        val cache = HomeworkCache(HomeworkSnapshot("account", 1, listOf(old), Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1))
        val exams = ExamCache(ExamSnapshot("account", listOf(exam("44", "55")), Instant.EPOCH))
        val result = HomeworkSyncCoordinator(reader({ fixture("mcp_course_lifecycle.html") }, mutableListOf()), cache, exams).sync("account")
        assertTrue(result.partial)
        assertTrue(cache.values.size > 2)
        assertTrue(cache.values.all { snapshot -> snapshot.items.none { it.course.id == ended.domain.id } })
        assertEquals(4, cache.state.value!!.items.size)
        val content = BuildHomeContentUseCase()(cache.state.value!!, HomeFilter(completed = true),
            Instant.parse("2026-10-28T00:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"))
        assertEquals(2, content.summary.pendingTotal)
        assertEquals(2, content.summary.completedTotal)
        assertEquals(2, content.visibleItems.size)
        assertTrue(content.visibleItems.all { it.course.id == active.domain.id })
        assertTrue(exams.state.value!!.items.all { it.courseId == "11" })
    }
    @Test fun endedCacheIsRemovedEvenIfActiveCourseFailsButItsExamsStillRead() = runBlocking {
        val old = Homework("ended", ended.domain, "旧作业", McpChaoxingParser.WORK_LIST, status = HomeworkStatus.NOT_SUBMITTED)
        val cache = HomeworkCache(HomeworkSnapshot("account", 1, listOf(old), Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1))
        val exams = ExamCache(null)
        val result = HomeworkSyncCoordinator(reader({ fixture("mcp_course_lifecycle.html") }, mutableListOf(), failWork = true), cache, exams).sync("account")
        assertTrue(result.partial)
        assertTrue(cache.state.value!!.items.isEmpty())
        assertEquals(Instant.EPOCH, cache.state.value!!.fetchedAt)
        assertEquals(2, exams.state.value!!.items.size)
    }
    @Test fun failedCourseCatalogNeverTriggersExamFetchOrClearsCachedData() = runBlocking {
        val requests = mutableListOf<String>()
        val old = exam("44", "55")
        val exams = ExamCache(ExamSnapshot("account", listOf(old), Instant.EPOCH))
        try {
            HomeworkSyncCoordinator(reader({ "<h1>网页改版</h1>" }, requests), HomeworkCache(null), exams).sync("account")
            fail("Expected catalog failure")
        } catch (_: IOException) { }
        assertTrue(requests.none { it.contains("examlist") })
        assertEquals(listOf(old), exams.state.value!!.items)
    }
}
