package com.homeworkassistant.domain.model

import java.time.Instant

/** Schedule phase and submission state are independent: an ended exam may still be unsubmitted. */
data class Exam(
    val id: String,
    val title: String,
    val timeText: String,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val duration: String,
    val scheduleStatus: String,
    val answerStatus: String,
    val score: String,
    val mode: String,
    val submissionStatus: HomeworkStatus,
    val courseId: String? = null,
    val classId: String? = null,
    val courseName: String? = null,
    val detailUrl: String? = null,
)

data class ExamSnapshot(val accountId: String, val items: List<Exam>, val fetchedAt: Instant)
