package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.*

class MergePartialSnapshotUseCase {
    operator fun invoke(previous: HomeworkSnapshot?, incoming: HomeworkSnapshot): HomeworkSnapshot {
        require(incoming.coverage != SnapshotCoverage.COMPLETE) { "部分更新不能声明全量完成" }
        require(previous == null || previous.accountId == incoming.accountId) { "禁止合并不同账号" }
        val items = linkedMapOf<String, Homework>()
        previous?.items?.forEach { items[it.id] = it }
        incoming.items.forEach { fresh ->
            val old = items[fresh.id]
            val status = if (fresh.status == HomeworkStatus.UNKNOWN) old?.status ?: fresh.status else fresh.status
            items[fresh.id] = if (old == null) fresh else fresh.copy(status = status,
                course = if (fresh.course.name.isBlank() || fresh.course.name == "未命名课程") old.course else fresh.course,
                publishedAt = fresh.publishedAt ?: old.publishedAt, dueAt = if (fresh.dueText != null) fresh.dueAt else fresh.dueAt ?: old.dueAt,
                dueText = fresh.dueText ?: old.dueText,
                submittedAt = fresh.submittedAt ?: old.submittedAt, maxScore = fresh.maxScore ?: old.maxScore,
                score = fresh.score ?: old.score.takeIf { status == HomeworkStatus.GRADED && old.status == HomeworkStatus.GRADED })
        }
        return incoming.copy(items = items.values.toList(), coverage = SnapshotCoverage.PARTIAL,
            discoveredCourses = maxOf(incoming.discoveredCourses, previous?.discoveredCourses ?: 0, items.values.map { it.course.id }.distinct().size),
            completedCourses = 0)
    }
}
