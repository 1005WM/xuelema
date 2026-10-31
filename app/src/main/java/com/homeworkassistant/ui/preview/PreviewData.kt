package com.homeworkassistant.ui.preview

import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.home.HomeUiState
import java.time.*

object PreviewData {
    val now: Instant = Instant.parse("2026-10-28T02:24:00Z")
    val items = listOf(
        Homework("math-1", Course("math", "高等数学"), "第三章 · 微分与应用", "https://i.chaoxing.com", dueAt = now.plusSeconds(7200), status = HomeworkStatus.NOT_SUBMITTED),
        Homework("math-2", Course("math", "高等数学"), "第二章课后练习", "https://i.chaoxing.com", dueAt = now.minusSeconds(3600), status = HomeworkStatus.NOT_SUBMITTED),
        Homework("english-1", Course("english", "大学英语"), "Unit 4 Reading", "https://i.chaoxing.com", dueAt = now.plusSeconds(172800), status = HomeworkStatus.NOT_SUBMITTED),
        Homework("physics-1", Course("physics", "大学物理"), "运动学练习", "https://i.chaoxing.com", status = HomeworkStatus.GRADED, score = 96.0),
    )
    val snapshot = HomeworkSnapshot("preview", 1, items, now, SnapshotCoverage.COMPLETE, 3, 3)
    val content = BuildHomeContentUseCase()(snapshot, HomeFilter(), now, ZoneId.of("Asia/Shanghai"))
    val state = HomeUiState(true, "preview", snapshot, content, cacheLabel = false)
}
