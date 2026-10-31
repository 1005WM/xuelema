package com.homeworkassistant.data

import com.homeworkassistant.data.ai.SseEventReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class SseEventReaderTest {
    @Test fun commentsMultilineCrLfAndEof() = runBlocking {
        val result = mutableListOf<String>()
        SseEventReader().read(StringReader(": heartbeat\r\ndata: first\r\ndata: second\r\n\r\nevent: message\ndata: tail").buffered()) { result.add(it); true }
        assertEquals(listOf("first\nsecond", "tail"), result)
    }
    @Test fun doneStopsConsumption() = runBlocking {
        val result = mutableListOf<String>()
        SseEventReader().read(StringReader("data: [DONE]\n\ndata: ignored\n\n").buffered()) { result.add(it); it != "[DONE]" }
        assertEquals(listOf("[DONE]"), result)
    }
    @Test(expected = java.io.IOException::class) fun rejectsOversizedEvent() = runBlocking {
        SseEventReader(4).read(StringReader("data: 12345\n\n").buffered()) { true }
    }
}
