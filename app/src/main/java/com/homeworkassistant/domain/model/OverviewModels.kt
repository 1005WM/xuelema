package com.homeworkassistant.domain.model

import java.time.Instant

data class Course(val id: String, val name: String)
enum class HomeworkStatus {
    NOT_SUBMITTED, RETURNED, SUBMITTED, GRADED, UNKNOWN;
    val isPending get() = this == NOT_SUBMITTED || this == RETURNED
    val isCompleted get() = this == SUBMITTED || this == GRADED
}
data class Homework(
    val id: String,
    val course: Course,
    val title: String,
    val detailUrl: String,
    val publishedAt: Instant? = null,
    val dueAt: Instant? = null,
    val submittedAt: Instant? = null,
    val status: HomeworkStatus = HomeworkStatus.UNKNOWN,
    val score: Double? = null,
    val maxScore: Double? = null,
    val canSubmit: Boolean? = null,
    val rawStatus: String? = null,
    val dueText: String? = null,
)
enum class SnapshotCoverage { COMPLETE, PARTIAL, UNKNOWN }
data class CourseStat(
    val course: Course,
    val pendingCount: Int,
    val overdueCount: Int,
    val dueWithin24HoursCount: Int,
    val dueThisWeekCount: Int,
    val nearestDeadline: Instant?,
) { val hasOverdue get() = overdueCount > 0 }
data class OverviewSummary(
    val pendingTotal: Int,
    val completedTotal: Int,
    val unknownStatusTotal: Int,
    val overdueTotal: Int,
    val dueWithin24HoursTotal: Int,
    val dueThisWeekTotal: Int,
    val courseStats: List<CourseStat>,
    val coverage: SnapshotCoverage,
    val calculatedAt: Instant,
) {
    val involvedCourseCount get() = courseStats.size
    val pendingByCourse: Map<Course, Int>
        get() = courseStats.associateTo(linkedMapOf()) { it.course to it.pendingCount }
    val canCelebrateAllDone get() = coverage == SnapshotCoverage.COMPLETE &&
        unknownStatusTotal == 0 && pendingTotal == 0
}
data class HomeworkSnapshot(
    val accountId: String,
    val revision: Long,
    val items: List<Homework>,
    val fetchedAt: Instant,
    val coverage: SnapshotCoverage,
    val discoveredCourses: Int,
    val completedCourses: Int,
)
