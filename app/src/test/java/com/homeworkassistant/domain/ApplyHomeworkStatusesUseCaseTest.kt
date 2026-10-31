package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ApplyHomeworkStatusesUseCaseTest {
    private val apply = ApplyHomeworkStatusesUseCase()
    private val now = Instant.parse("2026-10-29T08:00:00Z")
    private val a = Homework("11:22:71", Course("11:22", "数学"), "练习", "https://mooc1.chaoxing.com/work?workId=71",
        dueAt = now.plusSeconds(3600), status = HomeworkStatus.NOT_SUBMITTED, rawStatus = "未提交")
    private val b = a.copy(id = "44:55:71", course = Course("44:55", "英语"))
    private val snapshot = HomeworkSnapshot("account", 8, listOf(a, b), Instant.EPOCH, SnapshotCoverage.COMPLETE, 2, 2)

    @Test fun confirmedSubmissionUpdatesBothTotalsAndCompletedFilterInTheSameSnapshot() {
        val value = apply(snapshot, listOf(a.copy(status = HomeworkStatus.SUBMITTED, rawStatus = "待批阅")))
        val content = BuildHomeContentUseCase()(value, HomeFilter(completed = true), now, ZoneId.of("Asia/Shanghai"))
        assertEquals(1, content.summary.pendingTotal)
        assertEquals(1, content.summary.completedTotal)
        assertEquals(listOf(a.id), content.visibleItems.map { it.id })
        assertEquals(9, value.revision)
        assertEquals(snapshot.fetchedAt, value.fetchedAt)
        assertEquals(snapshot.coverage, value.coverage)
        assertEquals(a.dueAt, value.items.first().dueAt)
        assertEquals(b, value.items.last())
    }
    @Test fun unknownStatusOrMissingRowsNeverPretendThatHomeworkIsSubmitted() {
        assertEquals(snapshot, apply(snapshot, emptyList()))
        assertEquals(snapshot, apply(snapshot, listOf(a.copy(status = HomeworkStatus.UNKNOWN, rawStatus = ""))))
    }
    @Test fun foreignCourseAndNewRowsCannotOverwriteOrRecreateExistingHomework() {
        val submitted = a.copy(status = HomeworkStatus.SUBMITTED)
        assertEquals(snapshot, apply(snapshot, listOf(submitted.copy(course = b.course), submitted.copy(id = "unknown"))))
    }
    @Test fun unchangedChecksDoNotRewriteTheSnapshot() {
        assertSame(snapshot, apply(snapshot, listOf(a)))
    }
    @Test fun returnedHomeworkMovesBackToPendingAndClearsAnOldScore() {
        val old = snapshot.copy(items = listOf(a.copy(status = HomeworkStatus.GRADED, rawStatus = "已批阅", score = 90.0, submittedAt = now)))
        val value = apply(old, listOf(a.copy(status = HomeworkStatus.RETURNED, rawStatus = "退回重做")))
        assertTrue(value.items.single().status.isPending)
        assertNull(value.items.single().score)
        assertNull(value.items.single().submittedAt)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateStatusesAreRejected() {
        apply(snapshot, listOf(a, a.copy(status = HomeworkStatus.SUBMITTED)))
    }
}
