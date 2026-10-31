package com.homeworkassistant.data

import com.homeworkassistant.data.web.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeworkPageMergerTest {
    private val pending = HomeworkDto(id = "11:22:77", courseId = "11:22", courseName = "数学", status = "NOT_SUBMITTED")
    private val submitted = pending.copy(id = "11:22:78", status = "SUBMITTED")
    private fun page(vararg items: HomeworkDto) = HomeworkPageDto(recognized = true, items = items.toList(),
        diagnostics = PageDiagnosticsDto(kind = "WORK_LIST", workCandidates = items.size))
    private val merger = HomeworkPageMerger()

    @Test fun wrapperAndChildHomeworkAreMergedInsteadOfOnlyReadingWrapper() {
        val root = HomeworkPageDto(recognized = true, frames = listOf("https://mooc1-api.chaoxing.com/work/stu-work"))
        val result = merger.merge(root, listOf(root, page(pending, submitted)), true)
        assertEquals(listOf(pending, submitted), result.items)
        assertEquals(2, result.diagnostics.parsedDocuments)
        assertTrue(result.diagnostics.allFrames)
        assertEquals("WORK_LIST", result.diagnostics.kind)
    }
    @Test fun duplicateDocumentsDoNotDoubleCountAndKnownStatusWins() {
        val result = merger.merge(page(pending), listOf(page(pending), page(pending.copy(status = "UNKNOWN"))), true)
        assertEquals(listOf(pending), result.items)
    }
    @Test(expected = IOException::class) fun conflictingKnownStatusesAreRejected() {
        merger.merge(page(pending), listOf(page(pending), page(pending.copy(status = "SUBMITTED"))), true)
    }
    @Test fun anEmptyChildDoesNotEraseOtherDocumentItems() {
        val empty = HomeworkPageDto(recognized = true, empty = true, workListComplete = true,
            diagnostics = PageDiagnosticsDto(kind = "WORK_LIST"))
        val result = merger.merge(page(pending), listOf(page(pending), empty), true)
        assertEquals(listOf(pending), result.items)
        assertFalse(result.empty)
        assertFalse(result.workListComplete)
    }
    @Test fun unsupportedChildrenRemainVisibleInDiagnostics() {
        val broken = page().copy(diagnostics = PageDiagnosticsDto(kind = "WORK_LIST", workCandidates = 1, missingWorkId = 1))
        val result = merger.merge(page(pending), listOf(page(pending), broken), true)
        assertTrue(result.diagnostics.blocksCompleteCoverage)
        assertEquals(1, result.diagnostics.missingWorkId)
    }
    @Test fun missingFramesBlockCompleteCoverage() {
        val result = merger.merge(page(pending), listOf(page(pending)), true, unreadFrames = 1)
        assertTrue(result.diagnostics.blocksCompleteCoverage)
        assertEquals(1, result.diagnostics.unreadFrames)
    }
}
