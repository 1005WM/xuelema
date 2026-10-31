package com.homeworkassistant.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

class SessionExpiredException : IOException("登录已失效，请重新登录")

@SuppressLint("SetJavaScriptEnabled")
fun configureOfficialWebView(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(true)
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    // Homework synchronization uses McpChaoxingReader; no DOM homework bridge is installed here.
    view.setDownloadListener { _, _, _, _, _ -> /* Downloads remain in the official page. */ }
}

suspend fun WebView.evaluate(script: String): String = withContext(Dispatchers.Main.immediate) {
    pageOperation(8000, "网页脚本执行超时，请等待页面加载完成后重试") {
        suspendCancellableCoroutine { c ->
            evaluateJavascript(script) { value -> if (c.isActive) c.resume(value ?: "null") }
        }
    }
}

@Singleton
class WebSessionController @Inject constructor() {
    private var view: WebView? = null
    private var loading: CompletableDeferred<Unit>? = null
    private var loadingStarted = false
    private val mutex = Mutex()
    private val policy = TrustedOriginPolicy()
    private var epoch = 0L
    private var userAgent: String? = null
    fun useUserAgent(value: String) { userAgent = value; view?.settings?.userAgentString = value }
    fun attach(webView: WebView) {
        view = webView
        configureOfficialWebView(webView)
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        userAgent?.let { webView.settings.userAgentString = it }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                loadingStarted = true
                OfficialHomeworkReader.navigated(v)
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (policy.allows(url)) return false
                val https = policy.upgradeToHttps(url)
                if (https != null && request.isForMainFrame && request.method.equals("GET", true)) {
                    v.loadUrl(https); return true
                }
                if (request.isForMainFrame) loading?.completeExceptionally(
                    if (policy.isLogin(url)) SessionExpiredException() else IOException("同步页面需要额外登录跳转，请重新登录后重试"))
                return true
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (loadingStarted && v.url == url) loading?.complete(Unit)
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                val https = policy.upgradeToHttps(r.url.toString())
                if (r.isForMainFrame && https != null && r.method.equals("GET", true)) { v.loadUrl(https); return }
                if (r.isForMainFrame) loading?.completeExceptionally(IOException("网页加载失败：${e.errorCode}"))
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, response: WebResourceResponse) {
                if (r.isForMainFrame && response.statusCode >= 400) loading?.completeExceptionally(
                    if (response.statusCode == 401) SessionExpiredException() else IOException("网页 HTTP ${response.statusCode}"))
            }
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); loading?.completeExceptionally(IOException("网页证书校验失败"))
            }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                loading?.completeExceptionally(IOException("网页渲染进程退出，请重新打开页面后重试"))
                if (view === v) view = null
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                OfficialHomeworkReader.detach(v)
                v.destroy()
                return true
            }
        }
    }
    fun detach(webView: WebView) {
        if (view === webView) { cancel(); view = null }
    }
    fun cancel() {
        epoch++
        loading?.cancel()
        view?.stopLoading()
    }
    suspend fun clearStorage(): Boolean = withContext(Dispatchers.Main.immediate) {
        cancel()
        view?.apply { clearCache(true); clearHistory(); loadUrl("about:blank") }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            withTimeout(15000) {
                suspendCancellableCoroutine { c ->
                    WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { if (c.isActive) c.resume(Unit) }
                }
            }
            true
        } else {
            WebStorage.getInstance().deleteAllData()
            false
        }
    }
    suspend fun page(url: String, course: CourseDto?): HomeworkPageDto = mutex.withLock {
        require(policy.allows(url)) { "不允许加载非官方或非 HTTPS 地址" }
        val token = epoch
        withContext(Dispatchers.Main.immediate) {
            val web = view ?: throw IOException("网页宿主尚未就绪，请重试")
            val loaded = CompletableDeferred<Unit>()
            web.stopLoading()
            loading = loaded
            loadingStarted = false
            try {
                web.loadUrl(url)
                pageOperation(30000, "网页加载超过 30 秒，请检查网络或从官方页面同步当前页") { loaded.await() }
                if (policy.isLogin(web.url.orEmpty())) throw SessionExpiredException()
                var result: HomeworkPageDto? = null
                pageOperation(18000, "页面解析超时：尚未发现可识别的课程或作业入口，请打开官方作业列表后点击同步当前页") {
                    while (result == null) {
                        ensureActive()
                        check(token == epoch) { "会话已切换" }
                        if (!policy.allows(web.url.orEmpty())) throw IOException("页面跳转到不受信任来源")
                        if (policy.isLogin(web.url.orEmpty())) throw SessionExpiredException()
                        val candidate = OfficialHomeworkReader.read(web, course)
                        if (candidate.recognized) result = candidate
                        if (result == null) delay(500)
                    }
                    true
                }
                result ?: throw IOException("页面结构无法识别")
            } finally { if (loading === loaded) loading = null; web.stopLoading() }
        }
    }
}
