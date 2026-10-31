package com.homeworkassistant.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import com.homeworkassistant.data.web.*
import java.io.ByteArrayInputStream

data class BrowserUiState(
    val origin: String = "", val progress: Int = 0, val loading: Boolean = true,
    val canGoBack: Boolean = false, val popup: Boolean = false, val navigation: Long = 0,
    val error: String? = null,
)
data class LoginRedirectApproval(
    val origin: String, val externalApp: Boolean = false, val formNavigation: Boolean = false,
    val allow: () -> Unit, val decline: () -> Unit,
)

/** Owns real popup WebViews, preserving POST bodies, opener and window.close semantics. */
class OfficialBrowserView(
    context: Context, private val initialUrl: String, allowExternalLogin: Boolean,
    private val onState: (BrowserUiState) -> Unit,
    private val onActive: (WebView?) -> Unit,
    private val onFinished: (WebView) -> Unit,
    private val onApproval: (LoginRedirectApproval?) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
) : FrameLayout(context) {
    private val slowMessage = "页面加载超时或网络较慢，可以重试；登录信息不会因此清除"
    private val gate = LoginNavigationPolicy(allowExternalLogin)
    private val official = TrustedOriginPolicy()
    private val windows = mutableListOf<WebView>()
    private var state = BrowserUiState()
    private var disposed = false
    private var pending: String? = null
    private var desktop = false
    private val active get() = windows.lastOrNull()
    init { setBackgroundColor(Color.WHITE) }
    fun start() { if (windows.isEmpty()) newWindow(); active?.let { navigate(it, initialUrl) } }
    private fun publish(value: BrowserUiState = state) { state = value; if (!disposed) onState(value) }
    private fun failure(message: String) {
        if (disposed) return
        publish(state.copy(error = message, loading = false, progress = 100))
        onFailure(message)
    }
    fun slowLoading() { if (state.loading) failure(slowMessage) }
    private fun label(url: String) = gate.httpsOrigin(url) ?: if (url == "about:blank") "正在打开登录窗口" else "正在跳转"
    private fun newWindow(): WebView {
        val web = WebView(context)
        configureOfficialWebView(web)
        OfficialQuestionReader.attach(web)
        web.setBackgroundColor(Color.WHITE)
        web.settings.apply {
            useWideViewPort = true; loadWithOverviewMode = true
            builtInZoomControls = true; displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                handle(v, request.url.toString(), request.isForMainFrame, request.method)
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (!r.isForMainFrame) return null
                val decision = gate.decide(r.url.toString(), method = r.method, popupBlank = true)
                if (decision == LoginNavigation.Allow) return null
                // POST is not covered by shouldOverrideUrlLoading. Block before network, then ask on the UI thread.
                v.post { if (!disposed && windows.contains(v)) handle(v, r.url.toString(), true, r.method) }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Navigation requires approval",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageStarted(v: WebView, url: String, icon: Bitmap?) {
                OfficialHomeworkReader.navigated(v)
                OfficialQuestionReader.navigated(v)
                if (v !== active) return
                publish(state.copy(origin = label(url), loading = true, progress = 0, error = null,
                    navigation = state.navigation + 1, canGoBack = v.canGoBack(), popup = windows.size > 1))
            }
            override fun onPageCommitVisible(v: WebView, url: String) {
                if (v === active && (state.error == null || state.error == slowMessage)) publish(state.copy(loading = false, error = null))
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (url == "about:blank" && windows.size > 1) return
                if (v === active) publish(state.copy(loading = false, progress = 100, canGoBack = v.canGoBack(),
                    error = state.error.takeUnless { it == slowMessage }))
                if (state.error == null && official.allows(url) && windows.contains(v)) onFinished(v)
                if (v === active && state.error == null && gate.httpsOrigin(url) != null) {
                    v.postDelayed({
                        if (!disposed && v === active && v.url == url && state.error == null) {
                            v.evaluateJavascript("!!document.body && (!!document.body.innerText.trim() || !!document.body.querySelector('iframe, input, img, canvas, video, svg'))") { content ->
                                if (content == "false" && !disposed && v === active && v.url == url)
                                    failure("网站未返回可显示内容。请重试，或切换电脑网页后重新登录")
                            }
                        }
                    }, 2000)
                }
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame || v !== active) return
                val upgrade = official.upgradeToHttps(r.url.toString())
                if (upgrade != null && r.method.equals("GET", true)) { navigate(v, upgrade); return }
                failure("网页加载失败（${e.errorCode}），请检查网络后重试")
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, response: WebResourceResponse) {
                if (r.isForMainFrame && v === active && pending == null && response.statusCode >= 400)
                    failure("网页返回 HTTP ${response.statusCode}，可重试或返回官方首页")
            }
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); if (v === active) failure("网站证书校验失败，已停止加载")
            }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                val wasActive = v === active
                pending = null; onApproval(null)
                windows.remove(v); removeView(v); OfficialQuestionReader.detach(v); OfficialHomeworkReader.detach(v); v.destroy()
                if (wasActive) {
                    active?.visibility = VISIBLE; onActive(active)
                    failure("网页渲染进程已退出，点击重试重新创建页面")
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, progress: Int) {
                if (v === active && state.error == null) publish(state.copy(progress = progress))
            }
            override fun onShowFileChooser(v: WebView, c: ValueCallback<Array<Uri>>, p: FileChooserParams) = onFileChooser(c, p)
            override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                if (!gesture || windows.size >= 3 || disposed) return false
                val popup = newWindow()
                (result.obj as WebView.WebViewTransport).webView = popup
                result.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { close(window) }
        }
        active?.visibility = GONE
        windows.add(web)
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onActive(web)
        publish(state.copy(popup = windows.size > 1, canGoBack = false, loading = true, error = null))
        return web
    }
    private fun handle(v: WebView, url: String, mainFrame: Boolean, method: String): Boolean {
        if (disposed || !windows.contains(v)) return true
        return when (val decision = gate.decide(url, mainFrame, method, popupBlank = windows.size > 1)) {
            LoginNavigation.Allow -> false
            is LoginNavigation.Upgrade -> { v.loadUrl(decision.url); true }
            is LoginNavigation.Block -> { if (mainFrame) failure(decision.message); true }
            is LoginNavigation.Ask -> {
                if (pending != decision.origin) {
                    pending = decision.origin
                    publish(state.copy(loading = false))
                    val form = !method.equals("GET", true)
                    onApproval(LoginRedirectApproval(decision.origin, formNavigation = form, allow = {
                        pending = null; onApproval(null); gate.approve(decision.origin)
                        if (!disposed && windows.contains(v)) {
                            if (form) failure("已允许此登录站点。请返回上一页，重新点击学校登录按钮以继续表单认证")
                            else v.loadUrl(url)
                        }
                    }, decline = {
                        pending = null; onApproval(null); failure("已取消跳转至 ${decision.origin}，可返回官方首页选择其他登录方式")
                    }))
                }
                true
            }
            LoginNavigation.OpenApp -> {
                if (pending == null) {
                    pending = "external-app"
                    publish(state.copy(loading = false))
                    val scheme = Uri.parse(url).scheme.orEmpty()
                    onApproval(LoginRedirectApproval("$scheme://", externalApp = true, allow = {
                        pending = null; onApproval(null); launchExternal(url)
                    }, decline = {
                        pending = null; onApproval(null); failure("已取消打开外部应用，可继续使用网页登录")
                    }))
                }
                true
            }
        }
    }
    private fun launchExternal(url: String) {
        try {
            val parsed = if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val data = parsed.data ?: throw IllegalArgumentException()
            require(data.scheme?.lowercase() !in setOf(null, "javascript", "file", "content", "data", "about", "intent", "http"))
            val safe = Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE)
            // Never forward Intent actions, components, extras, selectors or permission grants from the webpage.
            parsed.`package`?.takeIf { it.matches(Regex("[a-zA-Z0-9_.]+")) }?.let(safe::setPackage)
            context.startActivity(safe)
        } catch (_: Exception) {
            val fallback = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
            if (fallback != null && gate.httpsOrigin(fallback) != null) active?.let { navigate(it, fallback) }
            else failure("未找到可处理该登录跳转的应用，请在官方页面选择账号或验证码登录")
        }
    }
    private fun navigate(v: WebView, url: String) { if (!handle(v, url, true, "GET")) v.loadUrl(url) }
    fun home() { active?.let { navigate(it, "https://i.chaoxing.com/") } ?: start() }
    fun retry() {
        val v = active ?: run { start(); return }
        val url = v.url?.takeIf { gate.httpsOrigin(it) != null } ?: initialUrl
        navigate(v, url)
    }
    fun toggleDesktop() {
        desktop = !desktop
        windows.forEach { web ->
            val ua = WebSettings.getDefaultUserAgent(context)
            web.settings.userAgentString = if (desktop) ua.replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)").replace("Mobile ", "") else ua
        }
        home()
    }
    fun back() {
        val v = active ?: return
        if (v.canGoBack()) v.goBack() else if (windows.size > 1) close(v)
    }
    fun closePopup() { if (windows.size > 1) active?.let(::close) }
    private fun close(web: WebView) {
        if (windows.size <= 1 || !windows.contains(web)) return
        pending = null; onApproval(null)
        windows.remove(web); removeView(web); web.stopLoading(); OfficialQuestionReader.detach(web); OfficialHomeworkReader.detach(web); web.destroy()
        active?.let {
            it.visibility = VISIBLE; onActive(it)
            publish(state.copy(origin = label(it.url.orEmpty()), popup = windows.size > 1, canGoBack = it.canGoBack(), error = null, loading = false))
            if (official.allows(it.url.orEmpty())) onFinished(it)
        }
    }
    fun dispose() {
        disposed = true; pending = null; onApproval(null); onActive(null)
        windows.toList().forEach { removeView(it); it.stopLoading(); it.webChromeClient = null; OfficialQuestionReader.detach(it); OfficialHomeworkReader.detach(it); it.destroy() }
        windows.clear()
    }
}
