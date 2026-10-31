package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.session.OfficialReadPausedException
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeadlineDocumentsTest {
    private val parser = McpChaoxingParser()
    private val item = Homework("11:22:1", Course("11:22", "数学"), "练习",
        "https://mooc1.chaoxing.com/work/task?workId=1", status = HomeworkStatus.NOT_SUBMITTED)
    private fun seed(requests: Int = 0) = McpHomeworkRead(listOf(item), 1, 1, true, requests, 0, emptyList(), cacheHits = 1)

    @Test fun inlineLabelsAndClockNodesAreNormalized() {
        val html = "<p>作答 <span>时间：</span><span>10-27 11</span>：<span>50</span> 至 <span>11-01 23</span>：<span>59</span></p>"
        assertEquals("11-01 23:59（年份待确认）", parser.details(item, OfficialHtml(item.detailUrl, html)).dueText)
        assertEquals(Instant.parse("2026-11-01T15:59:00Z"), parser.details(item,
            OfficialHtml(item.detailUrl, "截止 时间：2026 - 11 - 01 23 ： 59")).dueAt)
    }

    @Test fun alternateDataAttributeAndZeroWidthCharactersAreAccepted() {
        val html = "<div data-end-time='2026-11-01 23：59'></div>"
        assertEquals(Instant.parse("2026-11-01T15:59:00Z"), parser.details(item, OfficialHtml(item.detailUrl, html)).dueAt)
        assertEquals("11-01 23:59（年份待确认）", parser.details(item,
            OfficialHtml(item.detailUrl, "作答时间：10-27 11：50 至11-01 23：\u200B59")).dueText)
    }

    @Test fun followsObservedOfficialFrameAndCountsBothRequestsOnCacheHit() = runBlocking {
        val urls = mutableListOf<String>()
        val reader = McpChaoxingReader(McpReadTransport { url ->
            urls += url
            OfficialHtml(url, if (urls.size == 1) "<iframe src='/work/view?workId=1&amp;courseId=11&amp;classId=22&amp;enc=a%2Bb'></iframe>"
                else "<p>作答时间：10-27 11：50 至11-01 23：59</p>")
        })
        val result = reader.enrich(seed(), accountId = "one")
        assertEquals(2, urls.size)
        assertTrue(urls.last().contains("enc=a%2Bb"))
        assertEquals(2, result.value.requests)
        assertEquals(1, result.attempted)
        assertEquals(0, result.value.detailsUnavailable)
        assertEquals("11-01 23:59（年份待确认）", result.value.items.single().dueText)
        assertEquals(HomeworkStatus.NOT_SUBMITTED, result.value.items.single().status)
        assertEquals(0, reader.enrich(result.value, accountId = "one").attempted)
    }

    @Test fun observedMetaRefreshIsReadWithoutExecutingJavascript() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url ->
            count++
            OfficialHtml(url, if (count == 1) "<meta http-equiv='refresh' content=\"0; URL='/work/view?workId=1'\">"
                else "截止时间：2026-11-01 23:59")
        })
        assertEquals(Instant.parse("2026-11-01T15:59:00Z"), reader.enrich(seed()).value.items.single().dueAt)
        assertEquals(2, count)
    }

    @Test fun unrelatedFramesAndMutatingOrExternalUrlsAreIgnored() {
        val paths = listOf("https://evil.test/work/view?workId=1", "/work/view?workId=2", "/work/view?workId=1&courseId=99",
            "/work/view?workId=1&classId=33", "/work/submit?workId=1", "/work/start?workId=1",
            "/work/view?workId=1&action=save", "/exam/test?workId=1")
        val html = paths.joinToString("") { "<iframe src='$it'></iframe>" } +
            "<div class=TiMu><iframe src='/work/view?workId=1'></iframe></div>"
        assertTrue(parser.detailDocuments(item, OfficialHtml(item.detailUrl, html)).isEmpty())
    }

    @Test fun ordinaryAnswerLinkIsNeverAutomaticallyOpened() {
        assertTrue(parser.detailDocuments(item, OfficialHtml(item.detailUrl,
            "<a href='/work/do?workId=1'>开始作答</a><script>location.href='/work/do?workId=1';</script>")).isEmpty())
    }

    @Test fun cyclicOrLargeFrameGraphsStayWithinThreeDocumentsPerTask() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url ->
            count++
            OfficialHtml(url, "<iframe src='/work/task?workId=1'></iframe><iframe src='/work/view?workId=1&n=$count'></iframe>")
        })
        val result = reader.enrich(seed())
        assertEquals(3, count)
        assertEquals(3, result.value.requests)
        assertEquals(1, result.value.detailsUnavailable)
        assertNull(result.value.items.single().dueText)
    }

    @Test fun documentRequestsRespectExistingOverallCap() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url -> count++; OfficialHtml(url, "<iframe src='/work/view?workId=1'></iframe>") })
        val result = reader.enrich(seed(159))
        assertEquals(1, count)
        assertEquals(160, result.value.requests)
    }

    @Test fun exhaustingRequestBudgetDoesNotCountUnstartedTasks() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url -> count++; OfficialHtml(url, "<iframe src='/work/view?workId=1'></iframe>") })
        val result = reader.enrich(seed(158).copy(items = listOf(item, item.copy(id = "11:22:2"))))
        assertEquals(2, count)
        assertEquals(1, result.attempted)
        assertEquals(160, result.value.requests)
    }

    @Test fun metadataAlreadyOnEntryDoesNotFetchFrames() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url -> count++; OfficialHtml(url,
            "截止时间：2026-11-01 23:59<iframe src='/work/view?workId=1'></iframe>") })
        assertNotNull(reader.enrich(seed()).value.items.single().dueAt)
        assertEquals(1, count)
    }

    @Test fun verificationOnInnerDocumentStopsAutomaticRequests() = runBlocking {
        var count = 0
        val reader = McpChaoxingReader(McpReadTransport { url ->
            if (++count > 1) throw OfficialReadPausedException("官方要求人机验证，已暂停请求")
            OfficialHtml(url, "<iframe src='/work/view?workId=1'></iframe>")
        })
        try { reader.enrich(seed()); fail("Expected pause") } catch (e: OfficialReadPausedException) { assertTrue(e.message!!.contains("暂停")) }
        assertEquals(2, count)
    }
}
