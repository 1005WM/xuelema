package com.homeworkassistant.data.mcp

import com.homeworkassistant.data.web.SessionExpiredException
import com.homeworkassistant.data.web.TrustedOriginPolicy
import com.homeworkassistant.data.session.PlatformReadGate
import com.homeworkassistant.data.session.SignatureExpiredException
import com.homeworkassistant.di.PlatformClient
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

data class OfficialHtml(val url: String, val html: String)
fun interface McpReadTransport {
    suspend fun get(url: String): OfficialHtml
    fun resumeAfterVerification() = Unit
    fun checkPaused() = Unit
}

/** Android implementation of the upstream read-only HTTP session. Cookies stay on this device. */
@Singleton
class CookieMcpReadTransport @Inject constructor(@PlatformClient platform: OkHttpClient,
    private val gate: PlatformReadGate = PlatformReadGate(), private val timing: ReadTimingRecorder = ReadTimingRecorder()) : McpReadTransport {
    private val client = platform.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val policy = TrustedOriginPolicy()
    override fun resumeAfterVerification() = gate.resumeAfterVerification()
    override fun checkPaused() = gate.checkPaused()

    override suspend fun get(url: String): OfficialHtml = withContext(Dispatchers.IO) {
        val queued = System.nanoTime()
        gate.withPermit {
        timing.record("gate_wait", "official_html", queued)
        var target = url
        repeat(9) {
            require(policy.allows(target)) { "读取来源不是官方 HTTPS 页面" }
            if (policy.isLogin(target)) throw SessionExpiredException()
            var retry = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                timing.measure("throttle_wait", "official_html") { beforeRequest() }
                val request = Request.Builder().url(target).header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml").get().build()
                val call = client.newCall(request)
                val networkStarted = System.nanoTime()
                var networkRecorded = false
                var networkSuccess = false
                try {
                val response = call.await()
                val next = response.use { r ->
                    if (r.code == 429) pause("官方请求限流，已暂停请求", r.header("Retry-After"), timed = true)
                    if (r.code in listOf(502, 503, 504) && r.header("Retry-After") != null)
                        pause("官方暂不可用，已暂停请求", r.header("Retry-After"), timed = true)
                    if (r.code in listOf(502, 503, 504) && retry < 2) return@use RETRY
                    if (r.code in listOf(301, 302, 303, 307, 308)) {
                        val redirected = r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
                            ?: throw IOException("官方读取入口跳转缺少地址")
                        if (policy.isLogin(redirected) && policy.allows(redirected)) throw SessionExpiredException()
                        if (!policy.allows(redirected)) throw IOException("官方读取入口需要外部认证，请在官方网页完成认证")
                        return@use redirected
                    }
                    if (r.code in listOf(401, 403)) pause("官方限制访问，已暂停请求")
                    if (!r.isSuccessful) throw IOException("官方读取失败（HTTP ${r.code}），原缓存保留")
                    val body = r.body ?: throw IOException("官方返回空响应")
                    val bytes = call.readBoundedBody(body)
                    timing.record("network_body", "official_html", networkStarted)
                    networkRecorded = true
                    if (bytes.size > MAX_BYTES) throw IOException("官方页面过大，已停止读取")
                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val html = bytes.toString(charset)
                    if (html.contains("passport2.chaoxing.com/login") &&
                        (html.contains("用户登录") || html.contains("type=\"password\""))) throw SessionExpiredException()
                    if (html.contains("没有此页面的访问权限")) pause("官方限制访问，已暂停请求")
                    if (Regex("签名校验失败|(?:stu)?enc(?:校验失败|验证失败|已失效|已过期)", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
                        org.jsoup.Jsoup.parse(html).let { it.text().length < 2000 && it.select(".TiMu,.singleQuesId,.question-item").isEmpty() })
                        throw SignatureExpiredException()
                    if (target.contains("antispider", true) || (html.contains("9010") && html.contains("验证码")))
                        pause("官方要求人机验证，已暂停请求")
                    return@withPermit OfficialHtml(r.request.url.toString(), html)
                }
                networkSuccess = true
                if (next == RETRY) {
                    timing.record("network_body", "official_html", networkStarted); networkRecorded = true
                    retry++; timing.measure("retry_wait", "official_html") { delay(1_000L * retry) }; continue
                }
                target = next
                break
                } finally {
                    if (!networkRecorded) timing.record("network_body", "official_html", networkStarted, networkSuccess)
                }
            }
        }
        throw IOException("官方读取入口跳转次数过多")
    } }

    /** Keep cancellation connected to the call after headers arrive, including a slow response body. */
    private suspend fun Call.readBoundedBody(body: ResponseBody): ByteArray = coroutineScope {
        val bodyCall = this@readBoundedBody
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { bodyCall.cancel() }
        }
        try {
            ensureActive()
            val source = body.source()
            source.request(MAX_BYTES + 1)
            source.readByteArray(minOf(source.buffer.size, MAX_BYTES + 1))
        } finally { cancellation.cancel() }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("官方网络读取失败，请检查网络后重试", e))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val RETRY = "__retry__"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    }
}
