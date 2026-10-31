package com.homeworkassistant.data.web

import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class PageOperationException(message: String) : IOException(message)

/** Convert only our own deadline into a reportable failure; parent cancellation still propagates. */
suspend fun <T : Any> pageOperation(timeoutMillis: Long, message: String, block: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { block() } ?: throw PageOperationException(message)
