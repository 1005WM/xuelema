package com.homeworkassistant.data.web

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

sealed interface LoginNavigation {
    data object Allow : LoginNavigation
    data class Upgrade(val url: String) : LoginNavigation
    data class Ask(val origin: String) : LoginNavigation
    data object OpenApp : LoginNavigation
    data class Block(val message: String) : LoginNavigation
}

/** Approval is scoped to this visible browser, never to the scraper or AI client. */
class LoginNavigationPolicy(private val externalLogin: Boolean) {
    private val official = TrustedOriginPolicy()
    private val approved = ConcurrentHashMap.newKeySet<String>()
    fun httpsOrigin(url: String): String? = runCatching {
        val u = URI(url)
        val host = u.host?.lowercase() ?: return null
        if (!u.scheme.equals("https", true) || u.rawUserInfo != null || u.port !in -1..65535 || u.port == 0) return null
        "https://$host${if (u.port != -1 && u.port != 443) ":${u.port}" else ""}"
    }.getOrNull()
    fun approve(origin: String) {
        require(httpsOrigin(origin) == origin) { "无效登录来源" }
        approved.add(origin)
    }
    fun decide(url: String, mainFrame: Boolean = true, method: String = "GET", popupBlank: Boolean = false): LoginNavigation {
        if (popupBlank && url == "about:blank") return LoginNavigation.Allow
        if (!mainFrame && (url == "about:blank" || url == "about:srcdoc" || url.startsWith("data:text/html"))) return LoginNavigation.Allow
        if (official.allows(url)) return LoginNavigation.Allow
        official.upgradeToHttps(url)?.let {
            return if (mainFrame && method.equals("GET", true)) LoginNavigation.Upgrade(it)
            else LoginNavigation.Block("网页尝试使用 HTTP 表单或框架，请返回 HTTPS 登录页重试")
        }
        val origin = httpsOrigin(url)
        if (origin != null) {
            // HTTPS resources/iframes retain normal browser same-origin restrictions. No app bridge is installed.
            if (!mainFrame || approved.contains(origin)) return LoginNavigation.Allow
            return if (externalLogin) LoginNavigation.Ask(origin)
            else LoginNavigation.Block("此页面需要学校统一认证，请返回登录入口完成认证：$origin")
        }
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (mainFrame && externalLogin && scheme != null && scheme !in setOf(
                "http", "https", "about", "data", "javascript", "file", "content", "blob", "chrome", "android.resource"))
            return LoginNavigation.OpenApp
        return LoginNavigation.Block(if (scheme == "http") "该来源使用未加密 HTTP，已停止加载；请使用学校提供的 HTTPS 登录地址" else "网页请求了不支持的跳转，请返回官方首页重试")
    }
}
