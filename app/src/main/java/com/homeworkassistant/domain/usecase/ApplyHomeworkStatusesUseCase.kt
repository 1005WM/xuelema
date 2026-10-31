package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.*

/** Reconcile existing rows only. A status check is not a new full-list snapshot. */
class ApplyHomeworkStatusesUseCase {
    operator fun invoke(snapshot: HomeworkSnapshot, updates: List<Homework>): HomeworkSnapshot {
        require(updates.distinctBy { it.id }.size == updates.size) { "作业状态标识重复" }
        val fresh = updates.associateBy { it.id }
        val items = snapshot.items.map { old ->
            val update = fresh[old.id]?.takeIf { it.course.id == old.course.id && it.status != HomeworkStatus.UNKNOWN }
                ?: return@map old
            val sameState = old.status == update.status && old.rawStatus == update.rawStatus
            old.copy(status = update.status, rawStatus = update.rawStatus,
                dueAt = if (update.dueText != null) update.dueAt else update.dueAt ?: old.dueAt, dueText = update.dueText ?: old.dueText,
                submittedAt = old.submittedAt.takeIf { update.status.isCompleted },
                score = old.score.takeIf { sameState },
                canSubmit = old.canSubmit.takeIf { sameState })
        }
        return if (items == snapshot.items) snapshot else snapshot.copy(items = items, revision = snapshot.revision + 1)
    }
}
