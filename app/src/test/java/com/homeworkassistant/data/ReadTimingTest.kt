package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class ReadTimingTest {
    @Test fun timingSeparatesRateLimitNetworkAndActualHtmlParsingWithoutLoggingSignedUrls() = runBlocking {
        val samples = Collections.synchronizedList(mutableListOf<ReadTimingSample>())
        val recorder = ReadTimingRecorder { samples += it }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Thread.sleep(60) // Local simulated RTT, not a real school measurement.
            val html = if (chain.request().url.encodedPath.contains("stucoursemiddle"))
                "<input id=workEnc value=private-signature><input id=enc value=private-stuenc><input id=cpi value=33>"
                else javaClass.getResource("/fixtures/mcp_homework.html")!!.readText()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(html.toResponseBody()).build()
        }.build()
        val transport = CookieMcpReadTransport(client, timing = recorder)
        val reader = McpChaoxingReader(transport, timing = recorder)
        assertEquals(4, reader.homeworkStatuses("account", "11:22").size)
        assertEquals(2, samples.count { it.stage == "network_body" })
        assertTrue(samples.any { it.stage == "throttle_wait" && it.elapsedNanos > 500_000_000 })
        assertTrue(samples.any { it.stage == "html_parse" && it.kind == "homework" })
        assertTrue(samples.all { it.success })
        val log = samples.joinToString("\n") { it.logLine() }
        assertFalse(log.contains("private-signature")); assertFalse(log.contains("private-stuenc")); assertFalse(log.contains("https://"))
        samples.forEach { println("READ_TIMING ${it.logLine()}") }
    }
}
