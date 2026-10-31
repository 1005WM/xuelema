package com.homeworkassistant.data.web

import java.net.URI

class TrustedOriginPolicy {
    fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))
    }.getOrDefault(false)
    /** Preserve escaped paths and tickets while upgrading legacy official HTTP links. */
    fun upgradeToHttps(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("http", true) || uri.userInfo != null || uri.port !in listOf(-1, 80) ||
            !(host == "chaoxing.com" || host.endsWith(".chaoxing.com"))) return null
        "https://$host${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}${uri.rawFragment?.let { "#$it" }.orEmpty()}"
    }.getOrNull()
    fun isLogin(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host?.startsWith("passport") == true || uri.path.orEmpty().contains("/login", true)
    }.getOrDefault(false)
}
