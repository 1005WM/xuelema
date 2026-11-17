package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.Homework
import com.homeworkassistant.domain.model.HomeworkSnapshot
import com.homeworkassistant.domain.model.SnapshotCoverage
import java.time.Instant

/**
 * Pending homework that needs a deadline reminder: already overdue, or due within the
 * user-configured highlight window. Homework without an explicit deadline never enters
 * time-based metrics, matching AggregateOverviewUseCase and HomeworkList.
 */
data class DeadlineReminder(
    val overdue: List<Homework>,
    val upcoming: List<Homework>,
    val hours: Int,
    val partial: Boolean,
) {
    val size get() = overdue.size + upcoming.size
    val items get() = overdue + upcoming
}

class CollectDeadlineRemindersUseCase {
    private val byDeadline = compareBy<Homework> { it.dueAt ?: Instant.MAX }.thenBy { it.id }

    operator fun invoke(snapshot: HomeworkSnapshot, hours: Int, now: Instant): DeadlineReminder? {
        val windowHours = hours.coerceIn(1, 168)
        val end = now.plusSeconds(windowHours * 3600L)
        val dated = snapshot.items
            .filter { it.status.isPending }
            .mapNotNull { homework -> homework.dueAt?.let { homework to it } }
        val overdue = dated.filter { (_, due) -> due.isBefore(now) }
            .map { (homework, _) -> homework }.sortedWith(byDeadline)
        // The exact upper bound is excluded, consistent with the 24h metric and list highlight.
        val upcoming = dated.filter { (_, due) -> !due.isBefore(now) && due.isBefore(end) }
            .map { (homework, _) -> homework }.sortedWith(byDeadline)
        if (overdue.isEmpty() && upcoming.isEmpty()) return null
        return DeadlineReminder(overdue, upcoming, windowHours,
            snapshot.coverage != SnapshotCoverage.COMPLETE)
    }
}

internal data class DeadlineAlarmPlan(val ready: List<Homework>, val readyKeys: Set<String>, val next: Instant?)

internal fun planDeadlineAlarm(snapshot: HomeworkSnapshot, hours: Int, now: Instant, delivered: Set<String>): DeadlineAlarmPlan {
    val windowSeconds = hours.coerceIn(1, 168) * 3600L
    val ready = mutableListOf<Homework>()
    val keys = mutableSetOf<String>()
    var next: Instant? = null
    for (item in snapshot.items) {
        val due = item.dueAt ?: continue
        if (!item.status.isPending) continue
        val key = "${due.toEpochMilli()}:${item.id}"
        if (key in delivered) continue
        // The existing reminder window excludes its upper endpoint.
        val at = due.minusSeconds(windowSeconds).plusMillis(1)
        if (at > now) {
            if (next == null || at < next) next = at
        } else {
            ready += item
            keys += key
        }
    }
    return DeadlineAlarmPlan(ready, keys, next)
}
