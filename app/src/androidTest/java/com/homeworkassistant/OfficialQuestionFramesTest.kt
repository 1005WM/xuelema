package com.homeworkassistant

import android.webkit.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.homeworkassistant.data.web.*
import com.homeworkassistant.domain.model.Answer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Intercept all resources locally. No login, AI key, platform request or paid model invocation. */
class OfficialQuestionFramesTest {
    private val question = """<div class="TiMu" data-question-id="q"><div class="Zy_TItle">【单选题】2+2</div><label><input type="radio" name="q" value="A">3</label><label><input type="radio" name="q" value="B">4</label></div>"""
    @Test fun readsAndFillsDistinctOfficialFramesWithDuplicateNativeIds() = verifyFrames("https://i.chaoxing.com/work/do", "https://mooc1.chaoxing.com/work/do")
    @Test fun readsAndFillsExamQuestionsAcrossOfficialFrames() = verifyFrames("https://i.chaoxing.com/exam-ans/exam/test", "https://mooc1.chaoxing.com/exam-ans/exam/test")
    private fun verifyFrames(topUrl: String, innerUrl: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var web: WebView? = null
        var supported = false
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }
        assumeTrue(supported)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                configureOfficialWebView(this); OfficialQuestionReader.attach(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse {
                        val html = when (request.url.host) {
                            "i.chaoxing.com" -> question + """<iframe src="$innerUrl"></iframe><iframe src="https://outside.test/work/do"></iframe>"""
                            else -> question
                        }
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                    }
                    override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) { OfficialQuestionReader.navigated(v) }
                    override fun onPageFinished(v: WebView, url: String) { ready.countDown() }
                }
                loadUrl(topUrl)
            }
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val page = OfficialQuestionReader.read(requireNotNull(web))
            assertEquals(2, page.questions.size); assertEquals(2, page.frames.size)
            assertEquals(2, page.questions.map { it.id }.distinct().size)
            assertTrue(page.questions.all { it.canFill })
            val result = OfficialQuestionReader.fillDetailed(requireNotNull(web), page, page.questions.map { Answer(it.id, "B", "fixture") })
            assertTrue(result.message().contains("已回填 2 题"))
            assertEquals(page.questions.map { it.id }.toSet(), result.feedback.orEmpty().keys)
            assertTrue(result.feedback.orEmpty().values.all { it == "已回填" })
            val again = OfficialQuestionReader.read(requireNotNull(web))
            assertEquals(page.fingerprint, again.fingerprint)
            withContext(Dispatchers.Main) { OfficialQuestionReader.navigated(requireNotNull(web)) }
            try { OfficialQuestionReader.fill(requireNotNull(web), again, again.questions.map { Answer(it.id, "A", "fixture") }); fail() }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("页面已刷新")) }
        } finally {
            instrumentation.runOnMainSync { web?.let { OfficialQuestionReader.detach(it); it.destroy() } }
        }
    }
}
