package com.homeworkassistant.data

import com.homeworkassistant.data.web.HomeworkMapper
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkMapperTest {
    private val mapper = HomeworkMapper()
    @Test fun parsesExplicitChineseTimeAndIso() {
        val expected = Instant.parse("2026-10-27T02:24:00Z")
        assertEquals(expected, mapper.parseDate("2026年10月27日 10:24"))
        assertEquals(expected, mapper.parseDate("2026/10/27 10:24:00"))
        assertEquals(expected, mapper.parseDate("2026-10-27T02:24:00Z"))
    }
    @Test fun ambiguousDateStaysUnknown() {
        assertNull(mapper.parseDate("2026-10-27"))
        assertNull(mapper.parseDate("待定"))
        assertNull(mapper.parseDate(null))
    }
    @Test fun invalidCalendarDateIsNotSilentlyNormalized() {
        assertNull(mapper.parseDate("2026-2-29 10:24"))
        assertNull(mapper.parseDate("2026-10-27 25:00"))
    }
}
