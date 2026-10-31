package com.homeworkassistant.data.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

/** Shared by list/detail and image readers; a logical read retains the single permit through its body. */
@Singleton
class PlatformReadGate(private val pauses: ReadPauseStore = MemoryReadPauseStore()) {
    private val mutex = Mutex()
    private var lastRequestNanos: Long? = null
    internal var now: () -> java.time.Instant = java.time.Instant::now
    fun checkPaused() {
        val value = pauses.read() ?: return
        if (value.until != null && now().toEpochMilli() >= value.until) { pauses.write(null); return }
        throw OfficialReadPausedException(value.reason + (value.until?.let {
            "，请在 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))} 后重试"
        } ?: "，请打开官方页面完成验证，再点击“验证后重试”"))
    }
    fun pause(reason: String, retryAfter: String? = null, timed: Boolean = false): Nothing {
        val current = now()
        val wait = retryAfterMillis(retryAfter, current) ?: if (timed) 60_000L else null
        val until = wait?.let { if (it > Long.MAX_VALUE - current.toEpochMilli()) Long.MAX_VALUE else current.toEpochMilli() + it }
        val previous = pauses.read()
        pauses.write(if (previous != null && (previous.until == null || (until != null && previous.until > until))) previous else ReadPause(until, reason))
        checkPaused()
        throw OfficialReadPausedException(reason)
    }
    fun resumeAfterVerification() { if (pauses.read()?.until == null) pauses.write(null) }
    suspend fun <T> withPermit(block: suspend PlatformReadGate.() -> T): T = mutex.withLock { block() }
    suspend fun beforeRequest() {
        checkPaused()
        val remaining = lastRequestNanos?.let { 1000L - (System.nanoTime() - it) / 1_000_000 } ?: 0L
        if (remaining > 0) delay(remaining)
        checkPaused()
        lastRequestNanos = System.nanoTime()
    }
}
