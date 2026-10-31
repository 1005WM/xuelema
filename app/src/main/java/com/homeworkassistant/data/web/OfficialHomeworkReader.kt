package com.homeworkassistant.data.web

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.homeworkassistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID
import kotlin.coroutines.coroutineContext

private data class FrameEnvelopeDto(val nonce: String = "", val round: Int = 0, val frameId: String = "", val url: String = "", val page: HomeworkPageDto? = null)
private data class FrameDocument(val mainFrame: Boolean, val url: String, val page: HomeworkPageDto)
private data class FrameRead(val nonce: String, val topUrl: String, var round: Int = 0, val pages: MutableMap<String, FrameDocument> = linkedMapOf())

/** Origin-scoped document-start listeners let each official frame read its own DOM. */
class OfficialHomeworkReader private constructor(private val web: WebView) {
    private val gson = Gson()
    private val policy = TrustedOriginPolicy()
    private val parser = web.context.assets.open("scripts/extract_homework.js").bufferedReader().use { it.readText() }
    private var handler: ScriptHandler? = null
    private var current: FrameRead? = null
    private var installed = false

    @SuppressLint("RequiresFeature")
    private fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origins = setOf("https://chaoxing.com", "https://*.chaoxing.com")
        WebViewCompat.addWebMessageListener(web, "HomeworkDomBridge", origins) { _, message, source, mainFrame, _ ->
            val session = current
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val data = message.data
            if (session != null && web.url == session.topUrl && data != null && data.length <= 2_000_000 && policy.allows(source.toString())) {
                runCatching {
                    val envelope = gson.fromJson(data, FrameEnvelopeDto::class.java)
                    if (envelope.nonce == session.nonce && envelope.round == session.round && envelope.frameId.isNotBlank() && envelope.page != null &&
                        policy.allows(envelope.url) && URI(envelope.url).host.equals(source.host, ignoreCase = true)) {
                        session.pages[envelope.frameId] = FrameDocument(mainFrame, envelope.url, envelope.page)
                    }
                }
            }
        }
        installed = true
        val bootstrap = web.context.assets.open("scripts/frame_reader.js").bufferedReader().use { it.readText() }
            .replace("/*__HOMEWORK_EXTRACTOR__*/", parser.trim())
        handler = WebViewCompat.addDocumentStartJavaScript(web, bootstrap, origins)
    }

    suspend fun read(course: CourseDto?): HomeworkPageDto = withContext(Dispatchers.Main.immediate) {
        check(current == null) { "当前网页正在读取，请稍后重试" }
        val url = web.url.orEmpty()
        require(policy.allows(url)) { "请先打开官方作业列表" }
        val session = FrameRead(UUID.randomUUID().toString(), url)
        current = session
        try {
            var root: HomeworkPageDto? = null
            // Repeat the request while AJAX renders and newly created frames become ready.
            repeat(5) {
                coroutineContext.ensureActive()
                check(current === session && web.url == url) { "页面已跳转或重新加载，请等待加载完成后重试" }
                session.round++
                session.pages.clear()
                val command = gson.toJson(mapOf("type" to "HOMEWORK_READ", "nonce" to session.nonce, "round" to session.round, "course" to course))
                val dispatched = installed && web.evaluate("typeof window.__homeworkReadFrames === 'function' && (window.__homeworkReadFrames($command),true)") == "true"
                if (!dispatched) {
                    val raw = gson.fromJson(web.evaluate("window.__homeworkContext=${gson.toJson(course)};\n$parser"), String::class.java)
                    root = gson.fromJson(raw, HomeworkPageDto::class.java)
                }
                delay(600)
            }
            check(current === session && web.url == url) { "页面已变化，本次读取已取消" }
            val documents = session.pages.values.toList()
            val top = documents.firstOrNull { it.mainFrame }?.page ?: root
                ?: throw PageOperationException("网页尚未返回读取结果，请等待内容显示后重试")
            val pages = documents.map { it.page }.ifEmpty { listOf(top) }
            val readUrls = documents.map { it.url.substringBefore('#') }.toSet()
            val unread = pages.flatMap { it.frames }.distinct().count { it.substringBefore('#') !in readUrls }
            HomeworkPageMerger().merge(top, pages, documents.any { it.mainFrame }, unread)
        } finally { if (current === session) current = null }
    }

    fun navigated() { current = null }
    @SuppressLint("RequiresFeature")
    private fun release() {
        current = null
        handler?.remove(); handler = null
        if (installed) WebViewCompat.removeWebMessageListener(web, "HomeworkDomBridge")
        installed = false
    }

    companion object {
        fun attach(web: WebView) {
            if (web.getTag(R.id.homework_page_reader) == null) {
                val reader = OfficialHomeworkReader(web)
                web.setTag(R.id.homework_page_reader, reader)
                reader.install()
            }
        }
        suspend fun read(web: WebView, course: CourseDto?): HomeworkPageDto {
            attach(web)
            return (web.getTag(R.id.homework_page_reader) as OfficialHomeworkReader).read(course)
        }
        fun navigated(web: WebView) { (web.getTag(R.id.homework_page_reader) as? OfficialHomeworkReader)?.navigated() }
        fun detach(web: WebView) {
            (web.getTag(R.id.homework_page_reader) as? OfficialHomeworkReader)?.release()
            web.setTag(R.id.homework_page_reader, null)
        }
    }
}
