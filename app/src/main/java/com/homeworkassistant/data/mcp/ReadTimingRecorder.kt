package com.homeworkassistant.data.mcp

import android.util.Log
import javax.inject.Inject

data class ReadTimingSample(val stage: String, val kind: String, val elapsedNanos: Long, val success: Boolean) {
    fun logLine() = "stage=$stage kind=$kind elapsed_ms=${"%.3f".format(java.util.Locale.ROOT, elapsedNanos / 1_000_000.0)} success=$success"
}

/** Labels are fixed operation names; URLs, identifiers, HTML and credentials never enter logs. */
class ReadTimingRecorder(private val sink: (ReadTimingSample) -> Unit) {
    @Inject constructor() : this({ Log.d("XieLeMa.ReadTiming", it.logLine()); Unit })
    fun record(stage: String, kind: String, started: Long, success: Boolean = true) {
        runCatching { sink(ReadTimingSample(stage, kind, System.nanoTime() - started, success)) }
    }
    fun <T> parse(kind: String, block: () -> T): T {
        val start = System.nanoTime(); var success = false
        try { return block().also { success = true } } finally { record("html_parse", kind, start, success) }
    }
    suspend fun <T> measure(stage: String, kind: String, block: suspend () -> T): T {
        val start = System.nanoTime(); var success = false
        try { return block().also { success = true } } finally { record(stage, kind, start, success) }
    }
}
