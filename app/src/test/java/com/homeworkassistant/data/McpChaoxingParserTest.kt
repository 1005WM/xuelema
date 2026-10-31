package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class McpChaoxingParserTest {
    private val parser = McpChaoxingParser()
    private val course = McpCourse("11", "22", "33", "高等数学")
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun page(html: String) = OfficialHtml(McpChaoxingParser.WORK_LIST, html)

    @Test fun courseIdsAndNamesFollowUpstreamCourselist() {
        val result = parser.courses(OfficialHtml(McpChaoxingParser.COURSE_LIST, fixture("mcp_courses.html")))
        assertTrue(result.recognized)
        assertEquals(listOf("高等数学", "大学英语"), result.courses.map { it.name })
        assertEquals("33", result.courses.first().cpi)
        assertEquals("11:22", result.courses.first().domain.id)
    }
    @Test fun workEncAndStuencAreDifferentAndEscapedExactlyOnce() {
        val html = "<input value='work+signature' id='workEnc'><input id='enc' value='page+signature'><input id='openc' value='open-signature'>"
        val url = parser.workListUrl(course, page(html), Instant.EPOCH).toHttpUrl()
        assertEquals("work+signature", url.queryParameter("enc"))
        assertEquals("page+signature", url.queryParameter("stuenc"))
        assertEquals("open-signature", url.queryParameter("openc"))
        assertEquals("22", url.queryParameter("classId"))
        assertEquals("2", url.queryParameter("isdisplaytable"))
    }
    @Test(expected = IOException::class) fun missingWorkEncDoesNotBecomeEmptyHomework() {
        parser.workListUrl(course, page("<input id=enc value=wrong>"), Instant.EPOCH)
    }
    @Test fun upstreamDoneMarksAndNegativeStatesAreMapped() {
        listOf("已完成", "已提交", "待批阅", "已批阅", "已批改").forEach { assertTrue(it, parser.status(it).isCompleted) }
        listOf("未提交", "未交", "进行中", "已截止", "未开始").forEach { assertTrue(it, parser.status(it).isPending) }
        assertEquals(HomeworkStatus.RETURNED, parser.status("已提交后被退回"))
        assertEquals(HomeworkStatus.UNKNOWN, parser.status(" "))
    }
    @Test fun listReadsPendingAndCompletedAndNeverUsesTitleAsStatus() {
        val result = parser.homework(page(fixture("mcp_homework.html")), course)
        assertEquals(4, result.items.size)
        assertEquals(2, result.items.count { it.status.isPending })
        assertEquals(2, result.items.count { it.status.isCompleted })
        assertTrue(result.complete)
        assertEquals("fixture+signature", result.items.first().detailUrl.toHttpUrl().queryParameter("enc"))
        assertEquals("fixture+signature", result.items[1].detailUrl.toHttpUrl().queryParameter("enc"))
        assertEquals(HomeworkStatus.NOT_SUBMITTED, result.items.last().status)
    }
    @Test fun examsSeparateEndedFromSubmittedAndPreserveUnknownDates() {
        val (items, _) = parser.exams(page(fixture("mcp_exams.html")))
        assertEquals(3, items.size)
        assertTrue(items[0].submissionStatus.isPending)
        assertTrue(items[1].submissionStatus.isCompleted)
        assertEquals(HomeworkStatus.UNKNOWN, items[2].submissionStatus)
        assertNull(items[2].startsAt)
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), items[0].startsAt)
    }
    @Test fun unknownHtmlNeverOverwritesExamsOrWorkWithEmpty() {
        assertThrows(IOException::class.java) { parser.exams(page("<h1>请刷新页面</h1>")) }
        assertThrows(IOException::class.java) { parser.homework(page("<h1>请刷新页面</h1>"), course) }
        assertTrue(parser.exams(page("<p>暂无考试</p>")).first.isEmpty())
        assertTrue(parser.homework(page("<p>暂无作业</p>"), course).complete)
    }
    @Test fun examsUseColumnHeadersWhenRowsHaveNoLegacyClassOrColumnOrder() {
        val html = """<table><thead><tr><th>课程名称</th><th>考试名称</th><th>答卷状态</th><th>考试时间</th><th>考试状态</th><th>考试时长</th><th>得分</th><th>考试方式</th></tr></thead>
            <tbody><tr data-id="new-exam"><td>高等数学</td><td>期末测试</td><td>未交卷</td><td>2026-10-29 09:00 至 2026-10-29 10:00</td><td>已结束</td><td>60分钟</td><td>--</td><td>线上</td></tr></tbody></table>"""
        val value = parser.exams(page(html)).first.single()
        assertEquals("期末测试", value.title)
        assertEquals("高等数学", value.courseName)
        assertEquals("60分钟", value.duration)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, value.submissionStatus)
        assertEquals(Instant.parse("2026-10-29T01:00:00Z"), value.startsAt)
    }
    @Test fun hiddenEmptyPlaceholderCannotEraseUnrecognizedExamRows() {
        val html = "<table><tr class=dataTr><td>不完整考试</td></tr></table><p style='display:none'>暂无考试</p>"
        assertThrows(IOException::class.java) { parser.exams(page(html)) }
    }
    @Test fun blankSubmissionStateIsNotClaimedDoneOrPending() {
        val html = fixture("mcp_homework.html").replace("<p class=\"status fl\">未提交</p>", "<p class=\"status fl\"> </p>")
        assertEquals(HomeworkStatus.UNKNOWN, parser.homework(page(html), course).items.first().status)
    }
    @Test fun officialLinksPreserveSignedQueriesAndRejectExternalSchemes() {
        assertNull(parser.officialLink("javascript:goTask()", McpChaoxingParser.WORK_LIST))
        assertNull(parser.officialLink("https://evil.example/work?workId=1", McpChaoxingParser.WORK_LIST))
        assertEquals("https://mooc1.chaoxing.com/work?enc=a%2Bb", parser.officialLink("http://mooc1.chaoxing.com/work?enc=a%2Bb", McpChaoxingParser.WORK_LIST))
    }
    @Test fun detailDeadlineUsesExplicitYearAndDoesNotFlipPendingToCompleted() {
        val item = parser.homework(page(fixture("mcp_homework.html")), course).items.first()
        val enriched = parser.details(item, page("作答时间：2026-10-27 08:00 至 2026-10-28 09:00 满分：100 得分：60 分"))
        assertEquals(Instant.parse("2026-10-28T01:00:00Z"), enriched.dueAt)
        assertTrue(enriched.status.isPending)
        assertNull(parser.details(item, page("作答时间: 10-27 08:00 至 10-28 09:00")).dueAt)
    }
    @Test fun incompleteProgressDoesNotClaimCompleteAndPaginationIsObservedOnly() {
        val html = fixture("mcp_homework.html").replace("2/4", "2/5") + "<a rel=next href='?page=2'>下一页</a>"
        val result = parser.homework(page(html), course)
        assertFalse(result.complete)
        assertEquals(listOf(McpChaoxingParser.WORK_LIST + "?page=2"), result.next)
    }
    @Test fun malformedWorkRowOrDuplicateIdStopsPublication() {
        val html = fixture("mcp_homework.html")
        assertThrows(IOException::class.java) { parser.homework(page(html.replace("workId=74", "workId=73")), course) }
        assertThrows(IOException::class.java) { parser.homework(page(html.replace("workId=74", "missing=74")), course) }
    }
}
