package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.web.SessionExpiredException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okio.*

class McpReadTransportTest {
    private fun transport(handler: (Request) -> Pair<Int, Pair<String, String?>>): CookieMcpReadTransport =
        CookieMcpReadTransport(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, content) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(content.first.toResponseBody()).apply { content.second?.let { header("Location", it) } }.build()
        }.build())

    @Test fun smallResponseIsReadWithoutRequiringFourMegabytes() = runBlocking {
        val result = transport { 200 to ("<p>暂无作业</p>" to null) }.get(McpChaoxingParser.WORK_LIST)
        assertEquals("<p>暂无作业</p>", result.html)
    }
    @Test fun loginRedirectBecomesSessionExpiry() = runBlocking {
        try {
            transport { 302 to ("" to "https://passport2.chaoxing.com/login") }.get(McpChaoxingParser.BASE)
            fail("Expected expiry")
        } catch (_: SessionExpiredException) { }
    }
    @Test fun externalRedirectIsNeverRequested() = runBlocking {
        var count = 0
        try {
            transport { count++; 302 to ("" to "https://evil.example/collect") }.get(McpChaoxingParser.BASE)
            fail("Expected blocked redirect")
        } catch (_: IOException) { assertEquals(1, count) }
    }
    @Test fun oversizedPageIsRejected() = runBlocking {
        try {
            transport { 200 to ("x".repeat(4 * 1024 * 1024 + 1) to null) }.get(McpChaoxingParser.WORK_LIST)
            fail("Expected size bound")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("过大")) }
    }
    @Test fun unauthorizedSourceNeverReachesHttpClient() = runBlocking {
        var count = 0
        try {
            transport { count++; 200 to ("" to null) }.get("http://i.chaoxing.com/base")
            fail("Expected HTTPS enforcement")
        } catch (_: IllegalArgumentException) { assertEquals(0, count) }
    }
    @Test fun cancellationAlsoStopsReadingBodyAfterHeadersHaveArrived() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reading.countDown()
                        if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("Body was not cancelled")
                        return -1L
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        val job = launch(Dispatchers.Default) { CookieMcpReadTransport(client).get(McpChaoxingParser.BASE) }
        assertTrue(withContext(Dispatchers.IO) { reading.await(3, TimeUnit.SECONDS) })
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0, cancelled.count)
    }
}
