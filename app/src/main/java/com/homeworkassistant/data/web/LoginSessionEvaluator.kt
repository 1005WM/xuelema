package com.homeworkassistant.data.web

import java.net.URI

class LoginSessionEvaluator {
    private val official = TrustedOriginPolicy()
    private fun cookieMap(header: String) = header.split(';').mapNotNull {
        val pair = it.trim().split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1].trim('"') else null
    }.toMap()
    fun hasSessionCookie(header: String): Boolean = cookieMap(header).any { (key, value) ->
        key in setOf("_d", "vc3", "uf") && value.isNotBlank() && value != "null"
    }
    fun authenticatedUid(url: String, page: SessionDto, cookieHeader: String): String? {
        if (!official.allows(url) || official.isLogin(url) || !page.ready || page.loginForm) return null
        val cookies = cookieMap(cookieHeader)
        val uid = (listOf(page.uid) + listOf("_uid", "uid", "UID").map(cookies::get))
            .firstOrNull { it != null && it.matches(Regex("[0-9]{1,30}")) } ?: return null
        val uri = URI(url)
        val personalHost = uri.host.equals("i.chaoxing.com", true)
        val homePath = uri.path.isNullOrEmpty() || uri.path == "/" || uri.path == "/base" || uri.path.startsWith("/base/")
        val personalDocument = page.hasPersonalContent || (homePath && page.hasPageContent)
        return if (page.verified || (personalHost && personalDocument && hasSessionCookie(cookieHeader))) uid else null
    }
}
