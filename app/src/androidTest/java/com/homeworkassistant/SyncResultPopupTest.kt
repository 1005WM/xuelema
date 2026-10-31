package com.homeworkassistant

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.ui.components.SyncNotice
import com.homeworkassistant.ui.components.SyncResultPopup
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Rule
import org.junit.Test

class SyncResultPopupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultDisappearsAfterThreeSeconds() {
        var notice by mutableStateOf<SyncNotice?>(SyncNotice(1, "已跳过结课课程", true))
        compose.setContent { HomeworkTheme {
            SyncResultPopup(notice) { id -> if (notice?.id == id) notice = null }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("sync-result-popup").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_900)
        compose.onNodeWithTag("sync-result-popup").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("sync-result-popup").assertDoesNotExist()
    }

    @Test fun identicalNewResultGetsItsOwnThreeSeconds() {
        var notice by mutableStateOf<SyncNotice?>(SyncNotice(1, "同步完成", false))
        compose.setContent { HomeworkTheme {
            SyncResultPopup(notice) { id -> if (notice?.id == id) notice = null }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { notice = SyncNotice(2, "同步完成", false) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("sync-result-popup").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("sync-result-popup").assertDoesNotExist()
    }
}
