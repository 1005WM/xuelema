package com.homeworkassistant

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.SettingsDataStore
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FillSpeedSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sliderSavesWithoutRequiringAnAiKeyOrConnection() {
        var saved = 0
        compose.setContent { HomeworkTheme { SettingsScreen(AppSettings(), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {},
            onFillIntervalChange = { saved = it }) } }
        compose.onNodeWithContentDescription("每题回填间隔").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3500f) }
        compose.onNodeWithText("每题间隔 3.5 秒").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3500, saved) }
    }
    @Test fun speedPersistsIndependentlyOfAppearanceAiAndAccountAndIsClamped() = runBlocking {
        val store = SettingsDataStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = store.settings.first()
        try {
            store.saveFillInterval(3500)
            assertEquals(original.copy(fillIntervalMillis = 3500), SettingsDataStore(InstrumentationRegistry.getInstrumentation().targetContext).settings.first())
            store.saveAppearance(ThemeMode.DARK, false, 24)
            assertEquals(3500, store.settings.first().fillIntervalMillis)
            store.saveFillInterval(-1)
            assertEquals(500, store.settings.first().fillIntervalMillis)
            store.saveFillInterval(60000)
            assertEquals(5000, store.settings.first().fillIntervalMillis)
        } finally { store.save(original) }
    }
}
