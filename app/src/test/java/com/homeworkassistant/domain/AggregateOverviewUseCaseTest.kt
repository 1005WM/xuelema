package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AggregateOverviewUseCaseTest {
    private val now = Instant.parse("2026-10-27T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val aggregate = AggregateOverviewUseCase()
    private fun work(id: String, course: String = "math", due: Instant? = null,
        status: HomeworkStatus = HomeworkStatus.NOT_SUBMITTED) =
        Homework(id, Course(course, course), id, "https://i.chaoxing.com", dueAt = due, status = status)
    private fun summary(items: List<Homework>, coverage: SnapshotCoverage = SnapshotCoverage.COMPLETE) = aggregate(items, now, zone, coverage)
    @Test fun emptyCompleteCanCelebrate() {
        val s = summary(emptyList())
        assertEquals(0, s.pendingTotal); assertTrue(s.courseStats.isEmpty()); assertTrue(s.canCelebrateAllDone)
    }
    @Test fun emptyPartialMustNotCelebrate() { assertFalse(summary(emptyList(), SnapshotCoverage.PARTIAL).canCelebrateAllDone) }
    @Test fun unknownStatusPreventsCelebration() {
        val s = summary(listOf(work("unknown", status = HomeworkStatus.UNKNOWN)))
        assertEquals(1, s.unknownStatusTotal); assertEquals(0, s.pendingTotal); assertFalse(s.canCelebrateAllDone)
    }
    @Test fun overdueAnd24HourBoundaries() {
        val s = summary(listOf(work("past", due = now.minusMillis(1)), work("now", due = now),
            work("inside", due = now.plusSeconds(86399)), work("edge", due = now.plusSeconds(86400)), work("none")))
        assertEquals(5, s.pendingTotal); assertEquals(1, s.overdueTotal); assertEquals(2, s.dueWithin24HoursTotal)
    }
    @Test fun weekUsesLocalMondayExclusiveAndExcludesOverdue() {
        val end = Instant.parse("2026-11-01T16:00:00Z")
        val s = summary(listOf(work("before", due = end.minusMillis(1)), work("end", due = end), work("past", due = now.minusSeconds(1))))
        assertEquals(1, s.dueThisWeekTotal)
    }
    @Test fun groupOrderByCountThenDeadlineAndStableId() {
        val s = summary(listOf(work("a", "math", now.plusSeconds(30)), work("b", "math"),
            work("c", "english", now.plusSeconds(10)), work("d", "physics", now.plusSeconds(5)),
            work("e", "none"), work("done", "history", status = HomeworkStatus.GRADED)))
        assertEquals(listOf("math", "physics", "english", "none"), s.courseStats.map { it.course.id })
        assertEquals(2, s.pendingByCourse[Course("math", "math")]); assertEquals(1, s.completedTotal)
        assertEquals(s.pendingTotal, s.courseStats.sumOf { it.pendingCount })
    }
    @Test fun sameNamedDifferentCoursesStaySeparateAndReturnedIsPending() {
        val a = work("a", "a", status = HomeworkStatus.RETURNED).copy(course = Course("a", "英语"))
        val b = work("b", "b").copy(course = Course("b", "英语"))
        assertEquals(2, summary(listOf(a,b)).involvedCourseCount)
    }
    @Test fun completedDeadlinesNeverCountAsOverdue() {
        val s = summary(listOf(work("a", due = now.minusSeconds(1), status = HomeworkStatus.SUBMITTED),
            work("b", due = now.plusSeconds(1), status = HomeworkStatus.GRADED)))
        assertEquals(2, s.completedTotal); assertEquals(0, s.overdueTotal); assertEquals(0, s.dueWithin24HoursTotal)
    }
    @Test fun mondayDoesNotEndCurrentWeekImmediately() {
        val monday = Instant.parse("2026-10-25T16:00:00Z")
        val s = aggregate(listOf(work("a", due = monday.plusSeconds(1))), monday, zone, SnapshotCoverage.COMPLETE)
        assertEquals(1, s.dueThisWeekTotal)
    }
    @Test fun daylightSavingWeekUsesCalendarBoundary() {
        val localNow = Instant.parse("2026-10-31T12:00:00Z")
        val s = aggregate(listOf(work("a", due = Instant.parse("2026-11-02T04:59:59Z")),
            work("b", due = Instant.parse("2026-11-02T05:00:00Z"))), localNow, ZoneId.of("America/New_York"), SnapshotCoverage.COMPLETE)
        assertEquals(1, s.dueThisWeekTotal)
    }
}
