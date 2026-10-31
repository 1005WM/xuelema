package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeadlineExamNavigationTest {
    private val parser = McpChaoxingParser()
    private val item = Homework("11:22:1", Course("11:22", "数学"), "练习", "https://mooc1.chaoxing.com/work/task?workId=1", status = HomeworkStatus.NOT_SUBMITTED)
    private fun details(html: String) = parser.details(item, OfficialHtml(item.detailUrl, html))
    private fun exam(link: String): Exam {
        val html = """<table><tr class="dataTr" data-id="one"><td>1</td><td>数学考试</td><td>另行通知</td><td>60</td><td>进行中</td><td>未交卷</td><td>--</td><td>线上</td><td>$link</td></tr></table>"""
        return parser.exams(OfficialHtml(McpChaoxingParser.EXAM_LIST, html)).first.single()
    }

    @Test fun monthDayDeadlinesAreShownWithoutGuessingTheYearOrSubmission() {
        val value = details("<p>作答时间: 12-28 08:00 至 01-03 23:59</p>")
        assertNull(value.dueAt)
        assertEquals("01-03 23:59（年份待确认）", value.dueText)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, value.status)
    }
    @Test fun reportedFullWidthClockColonsAreParsedFromVisibleMetadata() {
        val value = details("<p>作答时间：10-27 11：50 至11-01 23：59</p>")
        assertEquals("11-01 23:59（年份待确认）", value.dueText)
        assertNull(value.dueAt)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, value.status)
        val full = details("<div>截止时间：<span>2026-11-01&nbsp;23：59：30</span></div>")
        assertEquals(Instant.parse("2026-11-01T15:59:30Z"), full.dueAt)
        assertNull(details("<div class=TiMu>作答时间：10-27 11：50 至11-01 23：59</div>").dueText)
    }
    @Test fun fullWidthClockColonsAreAlsoAcceptedInHiddenInputsAndListRows() {
        assertEquals(Instant.parse("2026-11-01T15:59:00Z"), details("<input name=endTime value='2026-11-01 23：59'>").dueAt)
        val html = """<span>0/1</span><li data-workid="1" data="/work/task?workId=1"><h3>练习</h3><p class=status>未提交</p><p>作答时间：10-27 11：50 至11-01 23：59</p></li>"""
        val row = parser.homework(OfficialHtml(McpChaoxingParser.WORK_LIST, html), McpCourse("11", "22", "33", "数学")).items.single()
        assertEquals("11-01 23:59（年份待确认）", row.dueText)
    }
    @Test fun fullDatesSupportSecondsChineseSeparatorsAndMetadataInputs() {
        assertEquals(Instant.parse("2026-10-30T15:59:30Z"), details("截止日期：2026年10月30日 23:59:30").dueAt)
        assertEquals(Instant.parse("2026-10-30T15:59:00Z"), details("<input name=endTime value='2026/10/30 23:59'>").dueAt)
        assertEquals(Instant.parse("2026-10-30T15:59:00Z"), details("<div data-endtime='2026-10-30T23:59'></div>").dueAt)
    }
    @Test fun invalidDatesAndDatesInsideQuestionsAreNotDeadlines() {
        assertNull(details("截止时间：2026-02-30 23:00").dueText)
        assertNull(details("截止时间：02-30 23:00").dueText)
        assertNull(details("<div class=TiMu>【简答题】截止时间：2026-10-30 23:00</div>").dueText)
        assertNull(details("<p>发布于 2026-10-30 23:00</p>").dueAt)
        assertEquals("未设置截止时间", details("截止时间：不限").dueText)
    }
    @Test fun deadlinesAlreadyInListRowsNeedNoExtraDetailRequest() {
        val html = """<span>0/1</span><li data-workid="1" onclick="goTask(this)" data="/work/task?workId=1"><h3>练习</h3><p class=status>未提交</p><p>截止时间：2026-10-30 23:59</p></li>"""
        val value = parser.homework(OfficialHtml(McpChaoxingParser.WORK_LIST, html), McpCourse("11", "22", "33", "数学")).items.single()
        assertEquals(Instant.parse("2026-10-30T15:59:00Z"), value.dueAt)
    }
    @Test fun signedExamLinksAreDecodedOnceAndRemainSpecificToTheExam() {
        val value = exam("<a href='/exam-ans/exam/test?examId=123&amp;enc=a%2Bb'>进入考试</a>")
        assertEquals("123", value.detailUrl!!.toHttpUrl().queryParameter("examId"))
        assertEquals("a+b", value.detailUrl!!.toHttpUrl().queryParameter("enc"))
    }
    @Test fun literalOnclickAndDataUrlsAreObservedWithoutRunningJavascript() {
        assertTrue(exam("""<button onclick="location.href='/exam-ans/exam/test?examId=2'">继续考试</button>""").detailUrl!!.contains("examId=2"))
        assertTrue(exam("<a data-url='/exam-ans/exam/test?examId=3'>查看</a>").detailUrl!!.contains("examId=3"))
        assertNull(exam("<a href=\"javascript:goExam('123')\">进入</a>").detailUrl)
        assertNull(exam("<a href='https://evil.test/exam?examId=4'>进入</a>").detailUrl)
    }
    @Test fun backgroundEnrichmentCoversPendingHomeworkBeyondTheFormerFourItemCap() = runBlocking {
        val requested = mutableListOf<String>()
        val reader = McpChaoxingReader(McpReadTransport { url -> requested += url; OfficialHtml(url, "作答时间：10-28 08:00 至 10-30 23:59") })
        val items = (1..12).map { item.copy(id = "11:22:$it", detailUrl = "${item.detailUrl}&n=$it") }
        val updates = mutableListOf<McpHomeworkRead>()
        val value = reader.enrich(McpHomeworkRead(items, 1, 1, true, 3, 0, emptyList()), onUpdated = { updates += it })
        assertEquals(12, value.attempted)
        assertEquals(12, updates.size)
        assertTrue(value.value.items.all { it.dueText == "10-30 23:59（年份待确认）" && it.status.isPending })
        assertEquals(12, requested.size)
    }
}
