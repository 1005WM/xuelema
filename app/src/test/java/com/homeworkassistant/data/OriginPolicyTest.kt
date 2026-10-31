package com.homeworkassistant.data

import com.homeworkassistant.data.web.TrustedOriginPolicy
import org.junit.Assert.*
import org.junit.Test

class OriginPolicyTest {
    @Test fun acceptsOnlyActualHttpsOfficialHosts() {
        val p = TrustedOriginPolicy()
        assertTrue(p.allows("https://i.chaoxing.com/path"))
        listOf("http://i.chaoxing.com", "https://chaoxing.com.evil.test", "https://evil.test/?chaoxing.com", "https://u:p@i.chaoxing.com", "https://i.chaoxing.com:8443", "javascript:alert(1)").forEach { assertFalse(it, p.allows(it)) }
    }
}
