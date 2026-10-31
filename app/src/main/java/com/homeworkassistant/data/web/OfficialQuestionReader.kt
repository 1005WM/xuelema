package com.homeworkassistant.data.web

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.homeworkassistant.R
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.*
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

private data class QuestionEnvelope(val nonce: String = "", val frameId: String = "", val url: String = "",
    val page: QuestionPage? = null, val error: String? = null, val result: QuestionFillResult? = null)
data class QuestionFillResult(val filled: List<String> = emptyList(), val already: List<String> = emptyList(),
    val unverified: List<String> = emptyList(), val failed: List<String> = emptyList(), val skipped: Int = 0,
    val feedback: Map<String, String>? = null) {
    fun message() = "已回填 ${filled.size} 题，已有相同答案 ${already.size} 题，跳过 $skipped 题，待检查 ${unverified.size} 题" +
        if (failed.isEmpty()) "" else "\n" + failed.joinToString("\n")
}
private data class QuestionDocument(val envelope: QuestionEnvelope, val proxy: JavaScriptReplyProxy?)
private class QuestionRead(val nonce: String, val topUrl: String, val epoch: Long) {
    val documents = linkedMapOf<String, QuestionDocument>()
    var changedAt = System.nanoTime()
}

/** Origin-scoped bridge: each official frame parses its own document; no API credentials enter JS. */
class OfficialQuestionReader private constructor(private val web: WebView) {
    private val gson = Gson()
    private val policy = TrustedOriginPolicy()
    private val engine: String by lazy {
        fun asset(name: String) = web.context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
        asset("question_engine.js").replace("/*__UPSTREAM__*/", listOf("question-context.js", "question-parser.js", "question-actions.js")
            .joinToString("\n") { asset("chaoxing_ai/$it") }).replace("/*__TEXT_ADAPTER__*/", asset("question_text_adapter.js"))
    }
    private var handler: ScriptHandler? = null
    private var installed = false
    private var epoch = 0L
    private var reading: QuestionRead? = null
    private var lastRead: QuestionRead? = null
    private var lastPage: QuestionPage? = null
    private var fillNonce: String? = null
    private var fillFrame: String? = null
    private var fillReply: CompletableDeferred<QuestionEnvelope>? = null

    @SuppressLint("RequiresFeature")
    private fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origins = setOf("https://chaoxing.com", "https://*.chaoxing.com")
        WebViewCompat.addWebMessageListener(web, "QuestionDomBridge", origins) { _, message, source, _, proxy ->
            if (message.type != WebMessageCompat.TYPE_STRING || !policy.allows(source.toString())) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 1_000_000) return@addWebMessageListener
            runCatching {
                val result = gson.fromJson(raw, QuestionEnvelope::class.java)
                if (result.frameId.isBlank() || !policy.allows(result.url) || URI(result.url).scheme != source.scheme ||
                    !URI(result.url).host.equals(source.host, true)) return@runCatching
                val read = reading
                if (read != null && valid(read) && result.nonce == read.nonce) {
                    read.documents[result.frameId] = QuestionDocument(result, proxy); read.changedAt = System.nanoTime()
                } else if (result.nonce == fillNonce && result.frameId == fillFrame) fillReply?.complete(result)
            }
        }
        handler = WebViewCompat.addDocumentStartJavaScript(web, engine, origins)
        installed = true
    }
    private fun valid(read: QuestionRead) = read.epoch == epoch && web.url == read.topUrl
    private fun send(proxy: JavaScriptReplyProxy, value: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(value)
        else error("当前 WebView 不支持答案回填，请更新 Android System WebView")
    }
    private fun command(nonce: String, op: String, payload: Any? = null) = gson.toJson(mapOf(
        "type" to "HA_QUESTION_COMMAND", "nonce" to nonce, "op" to op, "payload" to payload))

    private suspend fun read(): QuestionPage = withContext(Dispatchers.Main.immediate) {
        check(reading == null && fillReply == null) { "题目页面正在处理，请稍后重试" }
        val url = web.url.orEmpty(); require(policy.allows(url)) { "请先打开官方作业或考试题目页面" }
        val session = QuestionRead(UUID.randomUUID().toString(), url, epoch)
        reading = session; lastRead = null; lastPage = null
        try {
            web.evaluate(engine)
            repeat(3) { round ->
                check(valid(session)) { "页面已跳转，请等待加载完成后重新识别" }
                session.documents.clear()
                if (installed) {
                    web.evaluate("window.__haQuestionEngine.dispatch(${command(session.nonce, "read")})")
                    val started = System.nanoTime()
                    while ((System.nanoTime() - started) / 1_000_000 < 1500) {
                        delay(80); check(valid(session)) { "页面已变化，请重新识别" }
                        if (session.documents.isNotEmpty() && (System.nanoTime() - session.changedAt) / 1_000_000 >= 350) break
                    }
                } else {
                    val raw = gson.fromJson(web.evaluate("JSON.stringify(window.__haQuestionEngine.extract())"), String::class.java)
                    val json = gson.fromJson(raw, com.google.gson.JsonObject::class.java)
                    val id = json.get("frameId").asString
                    session.documents[id] = QuestionDocument(QuestionEnvelope(frameId = id, url = url,
                        page = gson.fromJson(json, QuestionPage::class.java)), null)
                }
                val pages = session.documents.values.filter { it.envelope.page?.questions?.isNotEmpty() == true }.sortedBy { it.envelope.frameId }
                if (pages.isNotEmpty()) {
                    require(pages.sumOf { it.envelope.page!!.questions.size } <= 300) { "单页题目超过 300 题，请按页处理" }
                    val merged = mutableListOf<Question>()
                    val frames = pages.map { entry ->
                        val envelope = entry.envelope; val page = envelope.page!!
                        page.questions.forEach { q -> merged += q.copy(id = "${envelope.frameId}::${q.id}", number = merged.size + 1) }
                        QuestionFrame(envelope.frameId, envelope.url, page.fingerprint)
                    }
                    require(merged.distinctBy { it.id }.size == merged.size) { "题目标识重复，请使用官方页面核对" }
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(gson.toJson(frames).toByteArray())
                        .joinToString("") { b -> "%02x".format(b) }
                    val warnings = if (installed) emptyList() else listOf("当前 WebView 仅能读取主页面；内嵌题目请更新 Android System WebView 后重试")
                    val page = QuestionPage(url, fingerprint, merged, frames, warnings)
                    lastRead = session; lastPage = page; return@withContext page
                }
                if (round < 2) delay(350)
            }
            val reason = session.documents.values.mapNotNull { it.envelope.error }.distinct().take(2).joinToString("；")
            throw PageOperationException(reason.ifBlank { "未识别到题目，请进入具体作业或考试并等待题目显示后重试" })
        } finally { reading = null }
    }

    private suspend fun fill(page: QuestionPage, answers: List<Answer>, intervalMillis: Int): QuestionFillResult = withContext(Dispatchers.Main.immediate) {
        check(reading == null && fillReply == null) { "题目页面正在处理，请稍后重试" }
        val session = lastRead
        require(session != null && valid(session) && page == lastPage) { "页面已刷新或切换，请重新识别题目" }
        val results = mutableListOf<QuestionFillResult>()
        val feedback = linkedMapOf<String, String>()
        val interval = intervalMillis.coerceIn(AppSettings.MIN_FILL_INTERVAL_MILLIS, AppSettings.MAX_FILL_INTERVAL_MILLIS)
        var previousFrameFilled = false
        require(answers.all { answer -> page.frames.any { answer.questionId.startsWith("${it.frameId}::") } }) { "答案与题目页面无法对应，请重新识别题目" }
        try {
            for (frame in page.frames) {
                require(valid(session)) { "页面已变化，已停止回填" }
                val entry = session.documents[frame.frameId] ?: error("题目框架已变化")
                val localAnswers = answers.filter { it.questionId.startsWith("${frame.frameId}::") }
                    .map { it.copy(questionId = it.questionId.removePrefix("${frame.frameId}::")) }
                if (localAnswers.isEmpty()) continue
                if (previousFrameFilled) delay(interval.toLong())
                require(valid(session)) { "页面已变化，回填已停止" }
                val payload = mapOf("page" to mapOf("url" to frame.url, "fingerprint" to frame.fingerprint),
                    "currentFingerprint" to frame.fingerprint, "answers" to localAnswers, "fillIntervalMillis" to interval)
                val timeoutMillis = 60_000L + (localAnswers.size - 1).coerceAtLeast(0).toLong() * interval
                val nonce = UUID.randomUUID().toString()
                if (entry.proxy != null) {
                    fillNonce = nonce; fillFrame = frame.frameId
                    val reply = CompletableDeferred<QuestionEnvelope>(); fillReply = reply
                    send(entry.proxy, command(nonce, "fill", payload))
                    val result = withTimeout(timeoutMillis) { reply.await() }
                    require(valid(session) && result.url == frame.url) { "页面已变化，回填已停止" }
                    result.error?.let { error(it) }; results += result.result ?: error("回填未返回结果，请核对官方页面")
                } else {
                    web.evaluate("window.__haFillResult=null;window.__haQuestionEngine.fill(${gson.toJson(payload)}).then(r=>window.__haFillResult=JSON.stringify({result:r})).catch(()=>window.__haFillResult=JSON.stringify({error:'回填失败，请核对官方页面'}));")
                    val result = withTimeout(timeoutMillis) {
                        var value: QuestionEnvelope? = null
                        while (value == null) {
                            delay(100); require(valid(session)) { "页面已变化，回填已停止" }
                            val raw = gson.fromJson(web.evaluate("window.__haFillResult"), String::class.java)
                            if (raw != null) value = gson.fromJson(raw, QuestionEnvelope::class.java)
                        }; value
                    }
                    result.error?.let { error(it) }; results += result.result ?: error("回填未返回结果")
                }
                val result = results.last()
                previousFrameFilled = result.filled.isNotEmpty()
                localAnswers.forEach { answer ->
                    feedback["${frame.frameId}::${answer.questionId}"] = result.feedback?.get(answer.questionId)
                        ?: result.failed.joinToString("；").ifBlank { "未收到本题回填状态，请检查官方页面" }
                }
                fillReply = null
            }
            QuestionFillResult(results.flatMap { it.filled }, results.flatMap { it.already }, results.flatMap { it.unverified },
                results.flatMap { it.failed }, results.sumOf { it.skipped }, feedback)
        } finally {
            session.documents.values.forEach { entry -> runCatching { entry.proxy?.let { send(it, command(UUID.randomUUID().toString(), "cancel")) } } }
            if (!installed) withContext(NonCancellable) { runCatching { web.evaluate("window.__haQuestionEngine?.cancel()") } }
            fillNonce = null; fillFrame = null; fillReply = null
        }
    }
    private fun navigated() { epoch++; reading = null; lastRead = null; lastPage = null
        fillReply?.completeExceptionally(PageOperationException("页面已跳转，回填已停止")) }
    @SuppressLint("RequiresFeature")
    private fun release() { navigated(); handler?.remove(); handler = null
        if (installed) WebViewCompat.removeWebMessageListener(web, "QuestionDomBridge")
        installed = false
    }
    companion object {
        fun attach(web: WebView) { if (web.getTag(R.id.question_page_reader) == null) {
            val reader = OfficialQuestionReader(web); web.setTag(R.id.question_page_reader, reader); reader.install()
        } }
        suspend fun read(web: WebView): QuestionPage { attach(web); return (web.getTag(R.id.question_page_reader) as OfficialQuestionReader).read() }
        suspend fun fill(web: WebView, page: QuestionPage, answers: List<Answer>): String =
            fillDetailed(web, page, answers).message()
        suspend fun fillDetailed(web: WebView, page: QuestionPage, answers: List<Answer>, intervalMillis: Int = AppSettings.DEFAULT_FILL_INTERVAL_MILLIS): QuestionFillResult =
            (web.getTag(R.id.question_page_reader) as? OfficialQuestionReader)?.fill(page, answers, intervalMillis) ?: error("请先识别当前页面题目")
        fun navigated(web: WebView) { (web.getTag(R.id.question_page_reader) as? OfficialQuestionReader)?.navigated() }
        fun detach(web: WebView) { (web.getTag(R.id.question_page_reader) as? OfficialQuestionReader)?.release(); web.setTag(R.id.question_page_reader, null) }
    }
}
