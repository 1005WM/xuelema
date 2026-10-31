package com.homeworkassistant.data.session

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.homeworkassistant.data.web.TrustedOriginPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class CookieCoordinator @Inject constructor() {
    private val main = Handler(Looper.getMainLooper())
    private val policy = TrustedOriginPolicy()
    private val epoch = AtomicLong(0)
    val generation: Long get() = epoch.get()
    fun header(url: String): String = if (policy.allows(url)) CookieManager.getInstance().getCookie(url).orEmpty() else ""
    fun acceptResponse(url: String, values: List<String>, expectedGeneration: Long) {
        if (!policy.allows(url) || values.isEmpty()) return
        if (expectedGeneration != generation) return
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cookie 接收必须在网络线程调用" }
        val latch = CountDownLatch(values.size)
        val accepted = AtomicBoolean(true)
        main.post {
            if (expectedGeneration != generation) repeat(values.size) { latch.countDown() }
            else values.forEach { value -> CookieManager.getInstance().setCookie(url, value) {
                if (!it) accepted.set(false)
                latch.countDown()
            } }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IOException("Cookie 同步超时")
        if (!accepted.get()) throw IOException("官方页面 Cookie 被 WebView 拒绝，请重新验证登录")
        CookieManager.getInstance().flush()
    }
    suspend fun clear() = withContext(Dispatchers.Main.immediate) {
        epoch.incrementAndGet()
        suspendCancellableCoroutine { c ->
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                if (c.isActive) c.resume(Unit)
            }
        }
    }
    suspend fun flush() = withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
}

class WebViewCookieJar(private val cookies: CookieCoordinator) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.header(url.toString()).split(';').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) null else runCatching {
            // Transient request objects only: CookieManager already performed URL matching.
            Cookie.Builder().name(pair.substring(0, index).trim()).value(pair.substring(index + 1).trim())
                .hostOnlyDomain(url.host).path("/").secure().build()
        }.getOrNull()
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
}
class RawSetCookieInterceptor(private val cookies: CookieCoordinator) : Interceptor {
    private val policy = TrustedOriginPolicy()
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!policy.allows(chain.request().url.toString())) throw IOException("平台请求来源不受信任")
        val generation = cookies.generation
        val response = chain.proceed(chain.request())
        try { cookies.acceptResponse(response.request.url.toString(), response.headers("Set-Cookie"), generation) }
        catch (e: Exception) { response.close(); throw e }
        return response
    }
}
