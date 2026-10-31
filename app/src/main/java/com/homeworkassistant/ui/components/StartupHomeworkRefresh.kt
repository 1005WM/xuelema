package com.homeworkassistant.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Startup readiness is independent of navigation; returning to a screen never retries a read. */
@Composable
fun StartupHomeworkRefresh(accountId: String?, initialized: Boolean, guideCompleted: Boolean, onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(accountId, initialized, guideCompleted) {
        if (accountId != null && initialized && guideCompleted) refresh()
    }
}
