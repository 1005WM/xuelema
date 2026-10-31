package com.homeworkassistant.data

import com.homeworkassistant.data.web.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PageOperationTest {
    @Test fun ownTimeoutBecomesVisibleIoFailure() = runBlocking {
        val error = runCatching { pageOperation(10, "页面解析超时") { delay(10000); "late" } }.exceptionOrNull()
        assertTrue(error is PageOperationException)
        assertEquals("页面解析超时", error?.message)
        assertFalse(error is CancellationException)
    }
    @Test fun parentCancellationIsNotReportedAsPageFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async { pageOperation(10000, "unused") { started.complete(Unit); awaitCancellation() } }
        started.await(); job.cancel()
        val error = runCatching { job.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(error is PageOperationException)
    }
    @Test fun outerDeadlineIsNotConvertedByInnerOperation() = runBlocking {
        val error = runCatching { withTimeout(10) { pageOperation(10000, "unused") { delay(10000); Unit } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
    }
    @Test fun successfulPageReturnsNormally() = runBlocking {
        assertEquals("parsed", pageOperation(1000, "unused") { "parsed" })
    }
}
