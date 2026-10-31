package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ApplyHomeworkDetailsUseCaseTest {
    private val h = Homework("11:22:71", Course("11:22", "数学"), "练习", "https://mooc1.chaoxing.com", status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
    private val snapshot = HomeworkSnapshot("one", 1, listOf(h), Instant.EPOCH, SnapshotCoverage.COMPLETE, 1, 1)
    @Test fun lateDetailsCannotRestoreAnOldSubmissionOrWriteStaleScore() {
        val submitted = ApplyHomeworkStatusesUseCase()(snapshot, listOf(h.copy(status = HomeworkStatus.SUBMITTED, rawStatus = "待批阅")))
        assertEquals(submitted, ApplyHomeworkDetailsUseCase()(submitted, listOf(h.copy(score = 10.0, dueAt = Instant.now()))))
    }
    @Test fun matchingDetailsPreserveCurrentStatusAndSnapshotFreshness() {
        val value = ApplyHomeworkDetailsUseCase()(snapshot, listOf(h.copy(dueAt = Instant.ofEpochSecond(100), score = 10.0)))
        assertEquals(h.status, value.items.single().status)
        assertEquals(10.0, value.items.single().score!!, 0.001)
        assertEquals(snapshot.fetchedAt, value.fetchedAt)
        assertEquals(snapshot.coverage, value.coverage)
        assertEquals(2L, value.revision)
    }
    @Test fun changedDeadlineTextDoesNotReuseAnObsoleteYearBasedInstant() {
        val old = snapshot.copy(items = listOf(h.copy(dueAt = Instant.parse("2026-11-02T15:59:00Z"))))
        val update = h.copy(dueText = "10-31 23:59（年份待确认）")
        assertNull(ApplyHomeworkDetailsUseCase()(old, listOf(update)).items.single().dueAt)
        assertEquals(update.dueText, ApplyHomeworkDetailsUseCase()(old, listOf(update)).items.single().dueText)
        assertNull(MergePartialSnapshotUseCase()(old, old.copy(items = listOf(update), coverage = SnapshotCoverage.PARTIAL)).items.single().dueAt)
    }
}
