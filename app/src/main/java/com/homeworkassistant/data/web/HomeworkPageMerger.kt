package com.homeworkassistant.data.web

import java.io.IOException

/** Merge documents read during one bounded operation, never cached or previous-page data. */
class HomeworkPageMerger {
    fun merge(root: HomeworkPageDto, documents: List<HomeworkPageDto>, allFrames: Boolean, unreadFrames: Int = 0): HomeworkPageDto {
        val pages = documents.ifEmpty { listOf(root) }
        val items = linkedMapOf<String, HomeworkDto>()
        pages.flatMap { it.items }.forEach { fresh ->
            val old = items[fresh.id]
            if (old != null && old.status != "UNKNOWN" && fresh.status != "UNKNOWN" && old.status != fresh.status)
                throw IOException("同一作业在当前页面中的状态冲突，请等待网页稳定后重试")
            items[fresh.id] = when {
                old == null -> fresh
                fresh.status == "UNKNOWN" && old.status != "UNKNOWN" -> old
                fresh.courseName == "未命名课程" && old.courseName != "未命名课程" -> fresh.copy(courseName = old.courseName)
                else -> fresh
            }
        }
        val workPages = pages.filter { it.diagnostics.kind == "WORK_LIST" }
        val d = root.diagnostics
        val diagnostics = d.copy(
            kind = if (workPages.isNotEmpty()) "WORK_LIST" else d.kind,
            anchors = pages.sumOf { it.diagnostics.anchors },
            workCandidates = pages.sumOf { it.diagnostics.workCandidates },
            missingCourseId = pages.sumOf { it.diagnostics.missingCourseId },
            missingWorkId = pages.sumOf { it.diagnostics.missingWorkId },
            unsupportedNavigation = pages.sumOf { it.diagnostics.unsupportedNavigation },
            unresolvedCourses = pages.sumOf { it.diagnostics.unresolvedCourses },
            attributeLinks = pages.sumOf { it.diagnostics.attributeLinks },
            referenceLinks = pages.sumOf { it.diagnostics.referenceLinks },
            parsedDocuments = pages.size, allFrames = allFrames, unreadFrames = unreadFrames,
            pendingLabels = pages.sumOf { it.diagnostics.pendingLabels },
        )
        return root.copy(recognized = pages.any { it.recognized }, loginRequired = pages.any { it.loginRequired },
            items = items.values.toList(), courses = pages.flatMap { it.courses }.distinctBy { it.id },
            frames = pages.flatMap { it.frames }.distinct(), navigation = pages.flatMap { it.navigation }.distinct(),
            next = pages.flatMap { it.next }.distinct(),
            currentCourse = root.currentCourse ?: pages.firstNotNullOfOrNull { it.currentCourse },
            empty = items.isEmpty() && workPages.isNotEmpty() && workPages.all { it.empty },
            workListComplete = workPages.isNotEmpty() && workPages.all { it.workListComplete }, diagnostics = diagnostics)
    }
}
