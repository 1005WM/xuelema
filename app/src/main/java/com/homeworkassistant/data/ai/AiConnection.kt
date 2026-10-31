package com.homeworkassistant.data.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Both Base URLs and copied Chat Completions endpoints resolve to a single API root. */
object AiConnection {
    fun baseUrl(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请填写有效 AI 地址，例如 https://api.deepseek.com")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "AI 地址必须是无账号密码、查询参数和片段的 HTTPS 地址"
        }
        val path = url.encodedPath.trimEnd('/')
        val root = when {
            path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
            path.endsWith("/models") -> path.removeSuffix("/models")
            else -> path
        }
        if (url.host == "api.deepseek.com") require(root in setOf("", "/v1", "/beta")) {
            "DeepSeek 官方地址请填写 https://api.deepseek.com 或 https://api.deepseek.com/v1"
        }
        return url.newBuilder().encodedPath("$root/").build()
    }

    fun key(raw: String): String {
        val key = raw.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
        require(key.isNotBlank()) { "请填写并保存 API Key" }
        require(key.all { it.code in 33..126 }) { "API Key 含空格或非法字符，请重新复制 Key 本身" }
        return key
    }

    fun networkFailure(error: IOException): IOException = IOException(when (error) {
        is UnknownHostException -> "AI 域名无法解析，请检查 URL 和手机网络"
        is SocketTimeoutException -> "AI 连接超时，请稍后重试或检查手机网络"
        is SSLException -> "AI HTTPS 证书校验失败，请检查服务地址、设备时间及网络"
        else -> "AI 网络连接失败，请检查手机网络或稍后重试"
    })
}

/** Never put response bodies, authorization headers or signed URLs in user-facing errors. */
class AiHttpException(val status: Int) : IOException("AI 请求失败：HTTP $status。" + when (status) {
    400 -> "请求格式或模型参数不兼容，请核对模型名称和服务类型"
    401 -> "Key 无效或已失效，请重新保存该服务的 API Key"
    402 -> "API 账户余额不足，请在服务商平台检查余额"
    403 -> "该 Key 没有访问权限，请检查服务商权限设置"
    404 -> "接口路径或模型不存在；请获取模型列表，并核对地址是否为 API 服务地址"
    405 -> "该服务不支持此接口方法，请检查其兼容接口说明"
    422 -> "模型不支持当前参数，请检查模型及输出设置"
    429 -> "请求过于频繁或额度受限，请稍后重试"
    in 300..399 -> "服务地址发生跳转，请填写最终 HTTPS API 地址"
    in 500..599 -> "服务端暂时不可用，请稍后重试"
    else -> "请检查该服务的 API 配置"
})
