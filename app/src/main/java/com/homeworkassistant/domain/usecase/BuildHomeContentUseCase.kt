package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.*
import java.time.Instant
import java.time.ZoneId

class BuildHomeContentUseCase(private val aggregate: AggregateOverviewUseCase = AggregateOverviewUseCase()) {
    operator fun invoke(snapshot: HomeworkSnapshot, filter: HomeFilter, now: Instant, zone: ZoneId): HomeContent {
        val visible = snapshot.items.filter {
            (if (filter.completed) it.status.isCompleted else it.status.isPending) &&
                (filter.courseId == null || filter.courseId == it.course.id) &&
                (filter.query.isBlank() || it.title.contains(filter.query, true) || it.course.name.contains(filter.query, true))
        }.let { items ->
            // Unknown deadlines remain last in both directions.
            if (filter.ascending) items.sortedWith(compareBy<Homework> { it.dueAt == null }.thenBy { it.dueAt }.thenBy { it.id })
            else items.sortedWith(compareBy<Homework> { it.dueAt == null }.thenByDescending { it.dueAt }.thenBy { it.id })
        }
        return HomeContent(aggregate(snapshot.items, now, zone, snapshot.coverage), visible)
    }
}
