package com.homeworkassistant.domain.usecase

import com.homeworkassistant.domain.model.*

/** Enrichment can never change submission state or restore an older row. */
class ApplyHomeworkDetailsUseCase {
    operator fun invoke(snapshot: HomeworkSnapshot, updates: List<Homework>): HomeworkSnapshot {
        val fresh = updates.associateBy { it.id }
        val items = snapshot.items.map { old ->
            val update = fresh[old.id]?.takeIf { it.course.id == old.course.id && it.status == old.status && it.rawStatus == old.rawStatus }
                ?: return@map old
            old.copy(publishedAt = update.publishedAt ?: old.publishedAt, dueAt = if (update.dueText != null) update.dueAt else update.dueAt ?: old.dueAt,
                dueText = update.dueText ?: old.dueText,
                score = update.score ?: old.score, maxScore = update.maxScore ?: old.maxScore)
        }
        return if (items == snapshot.items) snapshot else snapshot.copy(items = items, revision = snapshot.revision + 1)
    }
}
