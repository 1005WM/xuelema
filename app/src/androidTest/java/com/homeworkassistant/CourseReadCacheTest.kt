package com.homeworkassistant

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.mcp.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CourseReadCacheTest {
    @Test fun courseAndSchoolEntrySurviveRecreationButNeverContainSignaturesAndRespectAccountAndLifecycle() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("regression-$name", mode)
        }
        val cache = PersistentCourseReadCache(context)
        cache.clear()
        try {
            val course = McpCourse("11", "22", "33", "数学", CourseLifecycle.ACTIVE)
            cache.remember("one", listOf(course)); cache.rememberSchool("one", "123")
            val restored = PersistentCourseReadCache(context)
            assertEquals(course, restored.course("one", "11:22"))
            assertEquals("123", restored.school("one"))
            assertNull(restored.course("two", "11:22")); assertNull(restored.school("two"))
            val requests = mutableListOf<String>()
            val reader = McpChaoxingReader(McpReadTransport { url ->
                requests += url
                OfficialHtml(url, if (url.contains("stucoursemiddle")) "<input id=workEnc value=private-enc><input id=enc value=private-stuenc>" else "<p>暂无作业</p>")
            }, restored)
            reader.homeworkStatuses("one", "11:22")
            assertEquals(2, requests.size)
            assertTrue(requests.first().contains("cpi=33"))
            val persisted = context.getSharedPreferences("course-read-entries", Context.MODE_PRIVATE).all.toString()
            assertFalse(persisted.contains("private-enc")); assertFalse(persisted.contains("private-stuenc"))
            restored.remember("one", listOf(course.copy(lifecycle = CourseLifecycle.ENDED)))
            requests.clear()
            assertTrue(reader.homeworkStatuses("one", "11:22").isEmpty())
            assertTrue(requests.isEmpty())
            restored.clear()
            assertNull(PersistentCourseReadCache(context).course("one", "11:22"))
        } finally { cache.clear() }
    }
}
