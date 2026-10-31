package com.homeworkassistant.data

import com.homeworkassistant.data.web.*
import org.junit.Assert.*
import org.junit.Test

class LoginNavigationPolicyTest {
    @Test fun officialHttpsDoesNotRequireAuthorization() {
        val p = LoginNavigationPolicy(true)
        assertEquals(LoginNavigation.Allow, p.decide("https://i.chaoxing.com/"))
        assertEquals(LoginNavigation.Allow, p.decide("https://passport2.chaoxing.com/login"))
    }
    @Test fun legacyOfficialGetUpgradesWithoutCorruptingTicket() {
        val p = LoginNavigationPolicy(true)
        assertEquals(LoginNavigation.Upgrade("https://i.chaoxing.com/base?refer=a%2Fb%3Fc%3D1&ticket=a%2Bb#top"),
            p.decide("http://i.chaoxing.com:80/base?refer=a%2Fb%3Fc%3D1&ticket=a%2Bb#top"))
    }
    @Test fun postIsNeverSilentlyConvertedIntoGet() {
        assertTrue(LoginNavigationPolicy(true).decide("http://i.chaoxing.com/base", method = "POST") is LoginNavigation.Block)
    }
    @Test fun externalApprovalIsExactOriginAndNotSharedWithNewBrowser() {
        val p = LoginNavigationPolicy(true)
        assertEquals(LoginNavigation.Ask("https://sso.school.example"), p.decide("https://sso.school.example/login?ticket=secret"))
        p.approve("https://sso.school.example")
        assertEquals(LoginNavigation.Allow, p.decide("https://sso.school.example/callback"))
        assertTrue(p.decide("https://sso.school.example.evil.test/callback") is LoginNavigation.Ask)
        assertTrue(p.decide("https://sso.school.example:8443/callback") is LoginNavigation.Ask)
        assertTrue(LoginNavigationPolicy(true).decide("https://sso.school.example/callback") is LoginNavigation.Ask)
        assertTrue(LoginNavigationPolicy(false).decide("https://sso.school.example/login") is LoginNavigation.Block)
    }
    @Test fun captchaFrameDoesNotBecomeTopLevelAuthorization() {
        val p = LoginNavigationPolicy(true)
        assertEquals(LoginNavigation.Allow, p.decide("https://captcha.example/frame", mainFrame = false))
        assertTrue(p.decide("https://captcha.example/login") is LoginNavigation.Ask)
    }
    @Test fun popupBlankAndIframeBlankDoNotShowUnauthorizedNullHost() {
        val p = LoginNavigationPolicy(true)
        assertEquals(LoginNavigation.Allow, p.decide("about:blank", popupBlank = true))
        assertEquals(LoginNavigation.Allow, p.decide("about:blank", mainFrame = false))
        assertTrue(p.decide("about:blank") is LoginNavigation.Block)
    }
    @Test fun unsafeAndUnencryptedSourcesCannotBeApprovedAsLoginSites() {
        val p = LoginNavigationPolicy(true)
        listOf("http://sso.school.example", "https://u:p@school.example", "file:///sdcard/a", "javascript:alert(1)", "data:text/html,test").forEach {
            assertTrue(it, p.decide(it) is LoginNavigation.Block)
        }
        assertTrue(p.decide("weixin://login/test") == LoginNavigation.OpenApp)
    }
}
