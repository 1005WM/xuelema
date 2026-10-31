package com.homeworkassistant.data

import com.homeworkassistant.data.mcp.CookieMcpReadTransport
import com.homeworkassistant.data.session.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class OfficialReadPauseTest {
    @Test fun retryAfterSupportsSecondsAndHttpDate() {
        val now = Instant.parse("2026-10-30T02:00:00Z")
        assertEquals(120_000L, retryAfterMillis("120", now))
        assertEquals(90_000L, retryAfterMillis(DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(90).atZone(ZoneOffset.UTC)), now))
        assertNull(retryAfterMillis("invalid", now))
    }
    @Test fun http429PausesAndManualForceCannotBypassServerWait() = runBlocking {
        var now = Instant.parse("2026-10-30T02:00:00Z")
        val store = MemoryReadPauseStore()
        val gate = PlatformReadGate(store).apply { this.now = { now } }
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("limited")
                .header("Retry-After", "120").body("".toResponseBody()).build()
        }.build()
        val transport = CookieMcpReadTransport(client, gate)
        repeat(2) {
            try { transport.get("https://mooc1.chaoxing.com/work/list"); fail() } catch (_: OfficialReadPausedException) { }
            transport.resumeAfterVerification()
        }
        assertEquals(1, calls)
        val recreatedGate = PlatformReadGate(store).apply { this.now = { now } }
        try { recreatedGate.checkPaused(); fail() } catch (_: OfficialReadPausedException) { }
        now = now.plusSeconds(121); recreatedGate.checkPaused()
    }
    @Test fun verificationRequiresExplicitResumeAndPreservesTimedPauses() {
        val gate = PlatformReadGate()
        try { gate.pause("人机验证，暂停"); fail() } catch (_: OfficialReadPausedException) { }
        try { gate.checkPaused(); fail() } catch (_: OfficialReadPausedException) { }
        gate.resumeAfterVerification(); gate.checkPaused()
    }
    @Test fun explicitAccessRestrictionStopsWithoutAutomaticRetries() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("forbidden").body("".toResponseBody()).build()
        }.build()
        val gate = PlatformReadGate(); val transport = CookieMcpReadTransport(client, gate)
        repeat(2) { try { transport.get("https://mooc1.chaoxing.com/work/list"); fail() } catch (_: OfficialReadPausedException) { } }
        assertEquals(1, calls)
    }
    @Test fun permissionPagePausesWhileExplicitExpiredEncIsClassifiedSeparately() = runBlocking {
        fun client(html: String) = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(html.toResponseBody()).build()
        }.build()
        try { CookieMcpReadTransport(client("没有此页面的访问权限")).get("https://mooc1.chaoxing.com/work/list"); fail() }
        catch (_: OfficialReadPausedException) { }
        try { CookieMcpReadTransport(client("<p>enc校验失败</p>")).get("https://mooc1.chaoxing.com/work/list"); fail() }
        catch (_: SignatureExpiredException) { }
    }
}
