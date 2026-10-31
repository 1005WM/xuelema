package com.homeworkassistant.data.session

import android.content.Context
import androidx.core.content.edit
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SignatureExpiredException : IOException("官方签名校验失败")
class OfficialReadPausedException(message: String) : IOException(message)
data class ReadPause(val until: Long?, val reason: String)
interface ReadPauseStore { fun read(): ReadPause?; fun write(value: ReadPause?) }
class MemoryReadPauseStore : ReadPauseStore {
    @Volatile private var value: ReadPause? = null
    override fun read() = value
    override fun write(value: ReadPause?) { this.value = value }
}
class PersistentReadPauseStore(context: Context) : ReadPauseStore {
    private val prefs = context.getSharedPreferences("official-read-pause", Context.MODE_PRIVATE)
    override fun read(): ReadPause? = prefs.getString("reason", null)?.let { ReadPause(prefs.getLong("until", -1).takeIf { it >= 0 }, it) }
    override fun write(value: ReadPause?) {
        prefs.edit(commit = true) { if (value == null) clear() else { putString("reason", value.reason); putLong("until", value.until ?: -1) } }
    }
}

internal fun retryAfterMillis(value: String?, now: Instant): Long? {
    if (value == null) return null
    val seconds = value.trim().toLongOrNull()?.takeIf { it >= 0 }
    if (seconds != null) return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    return runCatching { java.time.Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis().coerceAtLeast(0) }.getOrNull()
}
