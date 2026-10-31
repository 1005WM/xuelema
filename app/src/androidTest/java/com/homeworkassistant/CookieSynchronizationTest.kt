package com.homeworkassistant

import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.session.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CookieSynchronizationTest {
    @Test fun webCookieCanBeReadByJarAndLogoutRemovesIt() = runBlocking {
        val latch = CountDownLatch(1)
        val url = "https://i.chaoxing.com/"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setCookie(url, "test_session=sample; Secure; HttpOnly; Path=/") { latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val coordinator = CookieCoordinator()
        assertTrue(WebViewCookieJar(coordinator).loadForRequest(url.toHttpUrl()).any { it.name == "test_session" && it.value == "sample" })
        coordinator.clear()
        assertFalse(coordinator.header(url).contains("test_session"))
    }
}
