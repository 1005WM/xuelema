package com.homeworkassistant.data

import com.homeworkassistant.data.web.*
import org.junit.Assert.*
import org.junit.Test

class LoginSessionEvaluatorTest {
    private val evaluator = LoginSessionEvaluator()
    private val cookies = "_uid=123456; _d=session; fid=1"
    @Test fun personalHomeWithHttpOnlyCookiesDoesNotRequireLogoutButton() {
        val page = SessionDto(ready = true, hasPageContent = true)
        assertEquals("123456", evaluator.authenticatedUid("https://i.chaoxing.com/base", page, cookies))
        assertEquals("123456", evaluator.authenticatedUid("https://i.chaoxing.com/", page, cookies))
    }
    @Test fun iframePersonalAreaCanConfirmSession() {
        assertEquals("123456", evaluator.authenticatedUid("https://i.chaoxing.com/new-home",
            SessionDto(ready = true, hasPersonalContent = true), cookies))
    }
    @Test fun uidAloneIsNotAuthenticatedSession() {
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/base", SessionDto(ready = true, hasPageContent = true), "_uid=123456"))
    }
    @Test fun blankPageLoadingAndLoginFormNeverCountAsSuccess() {
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/base", SessionDto(ready = true), cookies))
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/base", SessionDto(hasPageContent = true), cookies))
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/base", SessionDto(ready = true, hasPageContent = true, loginForm = true), cookies))
        assertNull(evaluator.authenticatedUid("https://passport2.chaoxing.com/login", SessionDto(ready = true, verified = true), cookies))
    }
    @Test fun unrelatedOrSpoofedDomainsCannotUseOfficialCookiesAsProof() {
        val page = SessionDto(ready = true, hasPageContent = true, verified = true)
        assertNull(evaluator.authenticatedUid("https://sso.school.example", page, cookies))
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com.evil.test/base", page, cookies))
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/help", page.copy(verified = false), cookies))
    }
    @Test fun explicitProtectedPageAndNumericAccountIdCanConfirmWithoutCookieFallback() {
        assertEquals("7654321", evaluator.authenticatedUid("https://i.chaoxing.com/base",
            SessionDto(ready = true, verified = true, uid = "7654321"), ""))
        assertNull(evaluator.authenticatedUid("https://i.chaoxing.com/base", SessionDto(ready = true, verified = true, uid = "bad"), ""))
    }
}
