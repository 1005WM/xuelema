package com.homeworkassistant.data

import com.google.gson.Gson
import com.homeworkassistant.data.web.*
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeworkPageReadTest {
    private val empty = HomeworkPageDto(recognized = true, empty = true, workListComplete = true,
        diagnostics = PageDiagnosticsDto(kind = "WORK_LIST"))

    @Test fun explicitEmptyWorkListIsReportedAsEmpty() {
        assertTrue(empty.hasConfirmedEmptyWorkList)
    }

    @Test fun loadingUnsupportedAndCourseEmptyAreNotEmptyWorkLists() {
        assertFalse(empty.copy(empty = false).hasConfirmedEmptyWorkList)
        assertFalse(empty.copy(workListComplete = false).hasConfirmedEmptyWorkList)
        assertFalse(empty.copy(diagnostics = PageDiagnosticsDto(kind = "COURSE_LIST")).hasConfirmedEmptyWorkList)
        assertFalse(HomeworkPageDto().hasConfirmedEmptyWorkList)
    }

    @Test fun unreadableRowsAndTabsBlockConfirmedEmpty() {
        assertFalse(empty.copy(diagnostics = PageDiagnosticsDto(kind = "WORK_LIST", workCandidates = 1)).hasConfirmedEmptyWorkList)
        assertFalse(empty.copy(diagnostics = PageDiagnosticsDto(kind = "WORK_LIST", missingWorkId = 1)).hasConfirmedEmptyWorkList)
        assertFalse(empty.copy(diagnostics = PageDiagnosticsDto(kind = "WORK_LIST", unsupportedNavigation = 1)).hasConfirmedEmptyWorkList)
    }

    @Test fun pendingAndCompletedDtosReachTheCorrectTabAndSharedOverview() {
        val json = """{"items":[
            {"id":"11:22:77","courseId":"11:22","courseName":"数学","title":"导数练习","detailUrl":"https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=77&answerId=0","status":"NOT_SUBMITTED"},
            {"id":"11:22:78","courseId":"11:22","courseName":"数学","title":"已交练习","detailUrl":"https://mooc1.chaoxing.com/work/select?workId=78","status":"GRADED","score":92},
            {"id":"11:22:79","courseId":"11:22","courseName":"数学","title":"状态未知","detailUrl":"https://mooc1.chaoxing.com/work/task?workId=79","status":"UNKNOWN"}
        ]}"""
        val page = Gson().fromJson(json, HomeworkPageDto::class.java)
        val now = Instant.parse("2026-10-27T00:00:00Z")
        val snapshot = HomeworkSnapshot("account", 1, page.items.map(HomeworkMapper()::map), now, SnapshotCoverage.PARTIAL, 1, 0)
        val build = BuildHomeContentUseCase()
        val pending = build(snapshot, HomeFilter(), now, ZoneId.of("Asia/Shanghai"))
        val completed = build(snapshot, HomeFilter(completed = true), now, ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf("11:22:77"), pending.visibleItems.map { it.id })
        assertEquals(listOf("11:22:78"), completed.visibleItems.map { it.id })
        assertEquals(1, pending.summary.pendingTotal)
        assertEquals(1, pending.summary.completedTotal)
        assertEquals(1, pending.summary.unknownStatusTotal)
        assertEquals(pending.summary, completed.summary)
        assertFalse(page.hasConfirmedEmptyWorkList)
    }
}
