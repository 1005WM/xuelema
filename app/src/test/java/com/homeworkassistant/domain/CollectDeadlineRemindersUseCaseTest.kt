package com.homeworkassistant.domain

import com.homeworkassistant.domain.model.Course
import com.homeworkassistant.domain.model.Homework
import com.homeworkassistant.domain.model.HomeworkSnapshot
import com.homeworkassistant.domain.model.HomeworkStatus
import com.homeworkassistant.domain.model.SnapshotCoverage
import com.homeworkassistant.domain.usecase.CollectDeadlineRemindersUseCase
import com.homeworkassistant.domain.usecase.planDeadlineAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CollectDeadlineRemindersUseCaseTest {
    private val now = Instant.parse("2026-11-04T08:00:00Z")
    private val useCase = CollectDeadlineRemindersUseCase()

    private fun homework(id: String, dueAt: Instant?,
        status: HomeworkStatus = HomeworkStatus.NOT_SUBMITTED): Homework =
        Homework(id, Course("course-1", "课程一"), "作业 $id", "https://i.chaoxing.com/work",
            dueAt = dueAt, status = status)

    private fun snapshot(items: List<Homework>,
        coverage: SnapshotCoverage = SnapshotCoverage.COMPLETE) =
        HomeworkSnapshot("account-1", 1, items, now, coverage, 1, 1)

    @Test fun emptySnapshotHasNoReminder() {
        assertNull(useCase(snapshot(emptyList()), 24, now))
    }

    @Test fun completedHomeworkIsIgnored() {
        val items = listOf(
            homework("a", now.minusSeconds(3600), HomeworkStatus.SUBMITTED),
            homework("b", now.plusSeconds(3600), HomeworkStatus.GRADED))
        assertNull(useCase(snapshot(items), 24, now))
    }

    @Test fun unknownStatusAndMissingDeadlineAreIgnored() {
        val items = listOf(
            homework("a", now.minusSeconds(3600), HomeworkStatus.UNKNOWN),
            homework("b", null))
        assertNull(useCase(snapshot(items), 24, now))
    }

    @Test fun returnedWorkIsGroupedAndCounted() {
        val items = listOf(homework("a", now.minusSeconds(7200)),
            homework("b", now.plusSeconds(3600)), homework("c", now.plusSeconds(48 * 3600)))
        val reminder = useCase(snapshot(items), 24, now)!!
        assertEquals(listOf("a"), reminder.overdue.map { it.id })
        assertEquals(listOf("b"), reminder.upcoming.map { it.id })
        assertEquals(2, reminder.size)
        assertEquals(listOf("a", "b"), reminder.items.map { it.id })
    }

    @Test fun overdueIsSortedByDeadlineThenId() {
        val older = now.minusSeconds(7200)
        val items = listOf(homework("z", older), homework("b", now.minusSeconds(3600)), homework("a", older))
        val reminder = useCase(snapshot(items), 24, now)!!
        assertEquals(listOf("a", "z", "b"), reminder.overdue.map { it.id })
    }

    @Test fun exactWindowBoundaryIsExcluded() {
        val inside = homework("a", now.plusSeconds(24 * 3600 - 1))
        val edge = homework("b", now.plusSeconds(24 * 3600))
        val reminder = useCase(snapshot(listOf(edge, inside)), 24, now)!!
        assertEquals(listOf("a"), reminder.upcoming.map { it.id })
    }

    @Test fun dueExactlyNowIsUpcomingNotOverdue() {
        val reminder = useCase(snapshot(listOf(homework("a", now))), 24, now)!!
        assertTrue(reminder.overdue.isEmpty())
        assertEquals(listOf("a"), reminder.upcoming.map { it.id })
    }

    @Test fun hoursAreCoercedIntoSupportedRange() {
        val soon = homework("a", now.plusSeconds(1800))
        assertEquals(1, useCase(snapshot(listOf(soon)), -5, now)!!.hours)
        assertEquals(168, useCase(snapshot(listOf(soon)), 9999, now)!!.hours)
        val week = now.plusSeconds(100 * 3600)
        assertNull(useCase(snapshot(listOf(homework("b", week))), 24, now))
        assertEquals(listOf("b"), useCase(snapshot(listOf(homework("b", week))), 168, now)!!.upcoming.map { it.id })
    }

    @Test fun partialAndUnknownCoverageAreFlagged() {
        val soon = homework("a", now.plusSeconds(3600))
        assertFalse(useCase(snapshot(listOf(soon)), 24, now)!!.partial)
        assertTrue(useCase(snapshot(listOf(soon), SnapshotCoverage.PARTIAL), 24, now)!!.partial)
        assertTrue(useCase(snapshot(listOf(soon), SnapshotCoverage.UNKNOWN), 24, now)!!.partial)
    }

    @Test fun returnedStatusIsPendingOnly() {
        val items = listOf(homework("a", now.minusSeconds(3600), HomeworkStatus.RETURNED),
            homework("b", now.minusSeconds(3600), HomeworkStatus.NOT_SUBMITTED))
        val reminder = useCase(snapshot(items), 24, now)!!
        assertEquals(listOf("a", "b"), reminder.overdue.map { it.id })
    }

    @Test fun alarmPicksNextWindowAndSkipsDeliveredOrCompletedWorkWhenItFires() {
        val delivered = homework("already-notified", now.plusSeconds(60))
        val first = homework("next", now.plusSeconds(3 * 3600))
        val second = homework("later", now.plusSeconds(4 * 3600))
        val completed = homework("done", now.plusSeconds(2 * 3600), HomeworkStatus.SUBMITTED)
        val items = snapshot(listOf(delivered, first, second, completed, homework("no-date", null)))
        val firstCheck = planDeadlineAlarm(items, 2, now, emptySet())
        assertEquals(listOf(delivered), firstCheck.ready)
        val alarmAt = now.plusSeconds(3600).plusMillis(1)
        assertEquals(alarmAt, firstCheck.next)

        val afterAlarm = planDeadlineAlarm(items, 2, alarmAt, firstCheck.readyKeys)
        assertEquals(listOf(first), afterAlarm.ready)
        assertEquals(now.plusSeconds(2 * 3600).plusMillis(1), afterAlarm.next)

        val revised = delivered.copy(dueAt = alarmAt.plusSeconds(60))
        val afterDeadlineChange = planDeadlineAlarm(snapshot(listOf(revised)), 2, alarmAt, firstCheck.readyKeys)
        assertEquals(listOf(revised), afterDeadlineChange.ready)
    }
}
