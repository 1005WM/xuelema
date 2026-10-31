package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.*
import java.time.*
import java.time.temporal.TemporalAdjusters

class AggregateOverviewUseCase {
    operator fun invoke(items: List<Homework>, now: Instant, zoneId: ZoneId,
        coverage: SnapshotCoverage): OverviewSummary {
        val end24 = now.plus(Duration.ofHours(24))
        val endWeek = now.atZone(zoneId).toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zoneId).toInstant()
        fun overdue(h: Homework) = h.dueAt?.isBefore(now) == true
        fun within(h: Homework, end: Instant) = h.dueAt?.let { !it.isBefore(now) && it.isBefore(end) } == true
        val pending = items.filter { it.status.isPending }
        val stats = pending.groupBy { it.course.id }.values.map { group ->
            CourseStat(group.first().course, group.size, group.count(::overdue),
                group.count { within(it, end24) }, group.count { within(it, endWeek) },
                group.mapNotNull { it.dueAt }.minOrNull())
        }.sortedWith(compareByDescending<CourseStat> { it.pendingCount }
            .thenBy { it.nearestDeadline ?: Instant.MAX }.thenBy { it.course.name }.thenBy { it.course.id })
        return OverviewSummary(pending.size, items.count { it.status.isCompleted },
            items.count { it.status == HomeworkStatus.UNKNOWN }, stats.sumOf { it.overdueCount },
            stats.sumOf { it.dueWithin24HoursCount }, stats.sumOf { it.dueThisWeekCount }, stats, coverage, now)
    }
}
