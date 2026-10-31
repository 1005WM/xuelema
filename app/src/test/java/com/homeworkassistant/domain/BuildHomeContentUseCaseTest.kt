package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BuildHomeContentUseCaseTest {
    @Test fun filtersNeverChangeGlobalOverviewAndUnknownIsFlaggedSeparately() {
        val now = Instant.parse("2026-10-27T00:00:00Z")
        val a = Homework("a", Course("math", "数学"), "积分", "https://i.chaoxing.com", status = HomeworkStatus.NOT_SUBMITTED)
        val b = a.copy(id = "b", course = Course("english", "英语"), status = HomeworkStatus.UNKNOWN)
        val snapshot = HomeworkSnapshot("account", 1, listOf(a,b), now, SnapshotCoverage.COMPLETE, 2, 2)
        val useCase = BuildHomeContentUseCase()
        val all = useCase(snapshot, HomeFilter(), now, ZoneId.of("Asia/Shanghai"))
        val filtered = useCase(snapshot, HomeFilter(courseId = "math", query = "积分"), now, ZoneId.of("Asia/Shanghai"))
        assertEquals(all.summary, filtered.summary); assertEquals(1, all.visibleItems.size); assertEquals(listOf(a), filtered.visibleItems)
        assertEquals(all.summary.pendingTotal, all.visibleItems.size)
        assertEquals(1, all.summary.unknownStatusTotal)
    }
    @Test fun nullDeadlineSortsLastInBothDirections() {
        val now = Instant.now()
        val a = Homework("a", Course("c", "c"), "a", "https://i.chaoxing.com", dueAt = now, status = HomeworkStatus.NOT_SUBMITTED)
        val b = a.copy(id = "b", dueAt = null)
        val snapshot = HomeworkSnapshot("account",1,listOf(b,a),now,SnapshotCoverage.COMPLETE,1,1)
        for (ascending in listOf(true,false)) assertEquals("b", BuildHomeContentUseCase()(snapshot,HomeFilter(ascending = ascending),now,ZoneId.of("UTC")).visibleItems.last().id)
    }
}
