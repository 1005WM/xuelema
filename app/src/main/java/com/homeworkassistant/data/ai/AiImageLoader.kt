package com.homeworkassistant.data.ai

import com.homeworkassistant.data.mcp.CookieMcpReadTransport
import com.homeworkassistant.data.session.CookieCoordinator
import com.homeworkassistant.data.session.PlatformReadGate
import com.homeworkassistant.data.web.TrustedOriginPolicy
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject

fun interface AiImageLoader {
    suspend fun load(url: String): String
    suspend fun load(url: String, referer: String): String = load(url)
}

/** Only predefined diagnostics; no signed URLs, Cookie values or response bodies. */
class ImageReadException(message: String) : IOException(message)

/** Fetch platform images locally; only image bytes, never resource URLs/Cookies, go to the AI. */
class ChaoxingImageLoader internal constructor(client: OkHttpClient,
    private val cookieHeader: (String) -> String, private val generation: () -> Long,
    private val gate: PlatformReadGate = PlatformReadGate()) : AiImageLoader {
    @Inject constructor(cookies: CookieCoordinator, gate: PlatformReadGate) : this(OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(), cookies::header, { cookies.generation }, gate)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val policy = TrustedOriginPolicy()
    override suspend fun load(url: String): String = load(url, "")
    override suspend fun load(url: String, referer: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("data:", true)) {
            if (url.length > MAX_BYTES * 4L / 3 + 1024 ||
                !Regex("^data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=\\r\\n]+$", RegexOption.IGNORE_CASE).matches(url))
                throw ImageReadException("内嵌图片格式不支持或超过 20 MB")
            val bytes = runCatching { Base64.getMimeDecoder().decode(url.substringAfter(',')) }.getOrNull()
                ?: throw ImageReadException("内嵌图片编码无效")
            return@withContext dataUrl(bytes)
        }
        var target = policy.upgradeToHttps(url) ?: url
        require(policy.allows(target)) { "图片来源未适配，已跳过该题，请使用官方原文核对" }
        val sourcePage = (policy.upgradeToHttps(referer) ?: referer).takeIf(policy::allows) ?: "https://i.chaoxing.com/"
        val expectedGeneration = generation()
        val visited = mutableSetOf<String>()
        gate.withPermit {
            repeat(9) {
                if (!policy.allows(target)) throw ImageReadException("图片跳转到未支持的来源，请在官方页面核对")
                if (policy.isLogin(target)) throw ImageReadException("图片需要重新登录，请在官方页面验证登录")
                if (!visited.add(target)) throw ImageReadException("图片地址循环跳转，请刷新官方页面")
                var retry = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (generation() != expectedGeneration) throw ImageReadException("登录状态已变化，请重新读取题目")
                    beforeRequest()
                    val builder = Request.Builder().url(target).header("User-Agent", CookieMcpReadTransport.USER_AGENT)
                        .header("Referer", sourcePage).header("Accept", "image/png,image/jpeg,image/webp,image/gif,*/*;q=0.5")
                    cookieHeader(target).takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                    val call = client.newCall(builder.build())
                    val result = try {
                        coroutineScope {
                            val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                            try {
                                call.execute().use { response ->
                                    if (generation() != expectedGeneration) throw ImageReadException("登录状态已变化，请重新读取题目")
                                    when {
                                        response.code in setOf(301, 302, 303, 307, 308) -> {
                                            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                                                ?: throw ImageReadException("图片跳转缺少目标地址")
                                            ImageResponse(next = policy.upgradeToHttps(next) ?: next)
                                        }
                                        response.code in setOf(429, 502, 503, 504) && retry < 2 -> ImageResponse(retry = true)
                                        !response.isSuccessful -> throw ImageReadException("HTTP ${response.code}，请在官方页面确认登录与图片权限")
                                        else -> {
                                            val body = response.body ?: throw ImageReadException("图片响应为空")
                                            val source = body.source(); source.request(MAX_BYTES + 1L)
                                            if (source.buffer.size > MAX_BYTES) throw ImageReadException("单张图片超过 20 MB")
                                            val bytes = source.readByteArray()
                                            if (generation() != expectedGeneration) throw ImageReadException("账号已切换，请重新读取题目")
                                            ImageResponse(data = dataUrl(bytes))
                                        }
                                    }
                                }
                            } finally { closer.cancelAndJoin() }
                        }
                    } catch (e: CancellationException) { throw e }
                      catch (e: ImageReadException) { throw e }
                      catch (_: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (retry >= 2) throw ImageReadException("网络连接失败或超时，重试后仍未取得图片")
                        ImageResponse(retry = true)
                    }
                    if (result.retry) { retry++; delay(500L * retry); continue }
                    result.data?.let { return@withPermit it }
                    target = requireNotNull(result.next)
                    break
                }
            }
            throw ImageReadException("图片跳转次数过多，请刷新官方页面")
        }
    }
    // Official endpoints may use octet-stream or an incorrect MIME; inspect actual bytes.
    private fun dataUrl(bytes: ByteArray): String {
        if (bytes.size !in 4..MAX_BYTES) throw ImageReadException("图片为空或超过 20 MB")
        val type = when {
            bytes.size >= 8 && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() } -> "image/png"
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.size >= 6 && String(bytes.copyOfRange(0, 6), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 12 && String(bytes.copyOfRange(0,4), Charsets.US_ASCII) == "RIFF" &&
                String(bytes.copyOfRange(8,12), Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> throw ImageReadException("响应不是 PNG/JPEG/WebP/GIF 图片，可能是登录页、失效链接或不支持的格式")
        }
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }
    private data class ImageResponse(val data: String? = null, val next: String? = null, val retry: Boolean = false)
    companion object { private const val MAX_BYTES = 20_000_000 }
}
