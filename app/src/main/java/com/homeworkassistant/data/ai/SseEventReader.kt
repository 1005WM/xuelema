package com.homeworkassistant.data.ai

import java.io.BufferedReader
import java.io.IOException

/** An SSE event may span multiple data lines and arbitrary network chunks. */
class SseEventReader(private val maxEventChars: Int = 1_000_000) {
    suspend fun read(reader: BufferedReader, event: suspend (String) -> Boolean) {
        val data = StringBuilder()
        fun boundedLine(): String? {
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next == -1) return if (line.isEmpty()) null else line.toString()
                if (next == '\n'.code) return line.toString()
                if (next == '\r'.code) {
                    reader.mark(1)
                    if (reader.read() != '\n'.code) reader.reset()
                    return line.toString()
                }
                line.append(next.toChar())
                if (line.length > maxEventChars) throw IOException("AI 流行过长")
            }
        }
        suspend fun dispatch(): Boolean {
            if (data.isEmpty()) return true
            val value = data.toString().removeSuffix("\n")
            data.setLength(0)
            return event(value)
        }
        var first = true
        while (true) {
            val raw = boundedLine() ?: break
            val line = if (first) raw.removePrefix("\uFEFF") else raw
            first = false
            if (line.isEmpty()) { if (!dispatch()) return; continue }
            if (line.startsWith(":")) continue
            if (line.startsWith("data:")) {
                data.append(line.substring(5).removePrefix(" ")).append('\n')
                if (data.length > maxEventChars) throw IOException("AI 流事件过大")
            }
        }
        dispatch()
    }
}
