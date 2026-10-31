package com.homeworkassistant

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.homeworkassistant.data.web.OfficialHomeworkReader
import com.homeworkassistant.data.web.configureOfficialWebView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local intercepted documents on distinct origins; no account or external request. */
class OfficialHomeworkFramesTest {
    @Test fun readsPendingFromOfficialChildFrameAndIgnoresExternalFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var web: WebView? = null
        var supported = false
        instrumentation.runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }
        assumeTrue(supported)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                configureOfficialWebView(this)
                OfficialHomeworkReader.attach(this) // Explicit legacy adapter fixture, outside app sync.
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                        val html = when (r.url.host) {
                            "i.chaoxing.com" -> """<iframe src="https://mooc1-api.chaoxing.com/work/stu-work"></iframe><iframe src="https://external.test/"></iframe>"""
                            "mooc1-api.chaoxing.com" -> """<ul class="nav"><li data="https://mooc1-api.chaoxing.com/workHandle/handle?courseId=11&amp;clazzId=22&amp;taskrefId=77"><div role="option"><p>练习</p><span class="status">未提交</span><span>数学</span></div></li></ul>"""
                            else -> """<li class="work-item"><a href="https://mooc1.chaoxing.com/work/do?workId=evil&amp;courseId=11">外部条目</a><span class="status">未提交</span></li>"""
                        }
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                    }
                    override fun onPageFinished(v: WebView, url: String) { ready.countDown() }
                }
                loadUrl("https://i.chaoxing.com/base")
            }
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val page = withContext(Dispatchers.Main) { OfficialHomeworkReader.read(requireNotNull(web), null) }
            assertEquals(1, page.items.size)
            assertEquals("NOT_SUBMITTED", page.items.single().status)
            assertTrue(page.diagnostics.allFrames)
            assertEquals(2, page.diagnostics.parsedDocuments)
        } finally {
            instrumentation.runOnMainSync { web?.let { OfficialHomeworkReader.detach(it); it.destroy() } }
        }
    }
}
