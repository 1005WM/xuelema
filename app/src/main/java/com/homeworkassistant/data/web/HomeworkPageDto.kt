package com.homeworkassistant.data.web

import com.homeworkassistant.domain.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class CourseDto(val id: String = "", val name: String = "", val url: String = "")
data class HomeworkDto(
    val id: String = "", val courseId: String = "", val courseName: String = "",
    val title: String = "", val detailUrl: String = "", val publishedAt: String? = null,
    val dueAt: String? = null, val submittedAt: String? = null, val status: String = "UNKNOWN",
    val score: Double? = null, val rawStatus: String? = null,
)
data class HomeworkPageDto(
    val version: String = "", val recognized: Boolean = false, val empty: Boolean = false,
    val explicitEnd: Boolean = false, val courses: List<CourseDto> = emptyList(),
    val items: List<HomeworkDto> = emptyList(), val frames: List<String> = emptyList(),
    val navigation: List<String> = emptyList(), val next: List<String> = emptyList(),
    val loginRequired: Boolean = false,
    val currentCourse: CourseDto? = null,
    val courseListComplete: Boolean = false, val workListComplete: Boolean = false,
    val diagnostics: PageDiagnosticsDto = PageDiagnosticsDto(),
) {
    // Loading containers and failed rows are never successful empty results.
    val hasConfirmedEmptyWorkList: Boolean get() = items.isEmpty() && empty && workListComplete &&
        diagnostics.kind == "WORK_LIST" && diagnostics.workCandidates == 0 && !diagnostics.blocksCompleteCoverage
}
data class PageDiagnosticsDto(val host: String = "", val kind: String = "UNKNOWN",
    val anchors: Int = 0, val frames: Int = 0, val workCandidates: Int = 0,
    val missingCourseId: Int = 0, val missingWorkId: Int = 0,
    val unsupportedNavigation: Int = 0, val unresolvedCourses: Int = 0,
    val width: Int = 0, val height: Int = 0, val attributeLinks: Int = 0,
    val referenceLinks: Int = 0, val parsedDocuments: Int = 1, val allFrames: Boolean = false,
    val unreadFrames: Int = 0, val pendingLabels: Int = 0) {
    val blocksCompleteCoverage get() = missingCourseId > 0 || missingWorkId > 0 || unsupportedNavigation > 0 || unresolvedCourses > 0 || unreadFrames > 0
    fun summary() = "页面 $kind · 候选作业 $workCandidates · 未完成状态文字 $pendingLabels 处 · 属性链接 $attributeLinks · 引用链接 $referenceLinks · 缺少课程标识 $missingCourseId · 缺少作业标识 $missingWorkId · 待适配入口 $unsupportedNavigation · 未识别课程 $unresolvedCourses · 内嵌页 $frames · 已读文档 $parsedDocuments（${if (allFrames) "框架读取" else "主页面读取"}）· 未读框架 $unreadFrames · 视口 ${width}×$height"
}
data class SessionDto(val verified: Boolean = false, val uid: String? = null,
    val loginForm: Boolean = false, val hasPersonalContent: Boolean = false, val ready: Boolean = false,
    val hasPageContent: Boolean = false)
class HomeworkMapper {
    private val zone = ZoneId.of("Asia/Shanghai")
    fun parseDate(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        runCatching { return Instant.parse(value) }
        val normalized = value.replace('年','-').replace('月','-').replace("日", "").replace('/', '-').trim()
        for (pattern in listOf("uuuu-M-d H:mm:ss", "uuuu-M-d H:mm")) {
            runCatching { return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant() }
        }
        return null
    }
    fun map(dto: HomeworkDto): Homework {
        require(dto.id.isNotBlank() && dto.courseId.isNotBlank() && TrustedOriginPolicy().allows(dto.detailUrl)) { "作业字段不完整" }
        return Homework(dto.id, Course(dto.courseId, dto.courseName), dto.title, dto.detailUrl,
            parseDate(dto.publishedAt), parseDate(dto.dueAt), parseDate(dto.submittedAt),
            runCatching { HomeworkStatus.valueOf(dto.status) }.getOrDefault(HomeworkStatus.UNKNOWN),
            dto.score, rawStatus = dto.rawStatus)
    }
}
