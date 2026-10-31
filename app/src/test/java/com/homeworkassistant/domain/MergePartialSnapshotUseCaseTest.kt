package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.MergePartialSnapshotUseCase
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MergePartialSnapshotUseCaseTest {
    private val now = Instant.parse("2026-10-27T02:00:00Z")
    private val a = Homework("a", Course("math", "数学"), "作业 A", "https://i.chaoxing.com/work?workId=a", dueAt = now, status = HomeworkStatus.NOT_SUBMITTED)
    private val b = a.copy(id = "b", course = Course("english", "英语"))
    private fun snapshot(items: List<Homework>, account: String = "one", coverage: SnapshotCoverage = SnapshotCoverage.PARTIAL) =
        HomeworkSnapshot(account, 1, items, now, coverage, items.map { it.course.id }.distinct().size, 0)
    @Test fun currentPageUpdatesExistingRowsAndPreservesOtherCourses() {
        val result = MergePartialSnapshotUseCase()(snapshot(listOf(a,b)), snapshot(listOf(a.copy(status = HomeworkStatus.SUBMITTED))))
        assertEquals(2, result.items.size)
        assertEquals(HomeworkStatus.SUBMITTED, result.items.first { it.id == "a" }.status)
        assertEquals(b, result.items.first { it.id == "b" })
        assertEquals(SnapshotCoverage.PARTIAL, result.coverage)
    }
    @Test fun missingFieldsAndUnknownStatusDoNotEraseKnownCachedValues() {
        val graded = a.copy(status = HomeworkStatus.GRADED, score = 90.0)
        val result = MergePartialSnapshotUseCase()(snapshot(listOf(graded)), snapshot(listOf(a.copy(status = HomeworkStatus.UNKNOWN, dueAt = null))))
        assertEquals(HomeworkStatus.GRADED, result.items.single().status)
        assertEquals(90.0, result.items.single().score)
        assertEquals(now, result.items.single().dueAt)
    }
    @Test fun partialReadNeverKeepsAllDoneCoverage() {
        val result = MergePartialSnapshotUseCase()(snapshot(listOf(a), coverage = SnapshotCoverage.COMPLETE), snapshot(listOf(b)))
        assertEquals(SnapshotCoverage.PARTIAL, result.coverage)
        assertEquals(0, result.completedCourses)
    }
    @Test fun emptyPartialReadDoesNotClearCache() {
        assertEquals(listOf(a,b), MergePartialSnapshotUseCase()(snapshot(listOf(a,b)), snapshot(emptyList())).items)
    }
    @Test(expected = IllegalArgumentException::class) fun differentAccountsCannotMerge() {
        MergePartialSnapshotUseCase()(snapshot(listOf(a)), snapshot(listOf(b), account = "two"))
    }
    @Test(expected = IllegalArgumentException::class) fun partialMergeRejectsCompleteCoverage() {
        MergePartialSnapshotUseCase()(null, snapshot(listOf(a), coverage = SnapshotCoverage.COMPLETE))
    }
}
