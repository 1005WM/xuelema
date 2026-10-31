package com.homeworkassistant

import android.webkit.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.web.*
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView and Room, with every HTTP resource intercepted locally. */
class RenderedHomeworkDeadlineTest {
    private val url = "https://mooc1.chaoxing.com/work/task?workId=1&courseId=11&classId=22"
    private val item = Homework("11:22:1", Course("11:22", "数学"), "练习", url,
        status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")

    private fun withPage(top: String = url, html: String, frameHtml: String = "", block: suspend (WebView) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                configureOfficialWebView(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(
                            (if (request.url.path == "/work/task") html else frameHtml).toByteArray(Charsets.UTF_8)))
                    override fun onPageFinished(v: WebView, url: String) { ready.countDown() }
                }
                loadUrl(top)
            }
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            block(web)
        } finally { instrumentation.runOnMainSync { web.destroy() } }
    }

    @Test fun renderedAjaxDeadlineUpdatesRoomWithoutChangingSubmissionState() = withPage(html = """
        <div id=metadata></div><script>setTimeout(() => document.querySelector('#metadata').innerHTML = '<p>作答时间：10-27 11：50 至11-01 23：59</p>', 250);</script>
        <div class=TiMu>截止时间：2099-12-31 23:59</div>
    """) { web ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = HomeworkRepositoryImpl(db)
            repo.publish(HomeworkSnapshot("one", 1, listOf(item), Instant.now(), SnapshotCoverage.COMPLETE, 1, 1))
            val update = withTimeout(5_000) {
                var result: Homework? = null
                while (result == null) { result = RenderedHomeworkMetadataReader().read(web, item); if (result == null) delay(100) }
                result
            }
            assertEquals(1, repo.updateDetails("one", listOf(update)))
            val restored = HomeworkRepositoryImpl(db).observe("one").first()!!.items.single()
            assertEquals("11-01 23:59（年份待确认）", restored.dueText)
            assertEquals(HomeworkStatus.NOT_SUBMITTED, restored.status)
            assertEquals("未提交", restored.rawStatus)
            assertNull(repo.observe("two").first())
        } finally { db.close() }
    }

    @Test fun sameOriginFrameMetadataIsRead() = withPage(html = "<iframe src='/work/view?workId=1'></iframe>",
        frameHtml = "<p>作答<span>时间：</span>10-27 11：50 至11-01 23：59</p>") { web ->
        assertEquals("11-01 23:59（年份待确认）", RenderedHomeworkMetadataReader().read(web, item)?.dueText)
    }

    @Test fun differentTaskAndQuestionDatesAreRejected() = withPage(html = """
        <div class=TiMu>截止时间：2026-11-01 23:59</div><iframe src='/work/view?workId=2'></iframe>
    """, frameHtml = "截止时间：2099-12-31 23:59") { web ->
        assertNull(RenderedHomeworkMetadataReader().read(web, item))
        assertNull(RenderedHomeworkMetadataReader().read(web, item.copy(id = "11:22:2")))
    }
}
