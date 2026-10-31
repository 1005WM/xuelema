package com.homeworkassistant

import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.SettingsDataStore
import com.homeworkassistant.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppearancePersistenceTest {
    @Test fun appearancePersistsIndependentlyWithoutTouchingAiOrAccountSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsDataStore(context)
        val previous = store.settings.first()
        val account = store.accountId.first()
        try {
            val ai = previous.copy(baseUrl = "", model = "fixture-model", temperature = 0.35f)
            store.save(ai)
            store.saveAppearance(ThemeMode.DARK, true, 36)
            val saved = SettingsDataStore(context).settings.first()
            assertEquals(ThemeMode.DARK, saved.theme)
            assertTrue(saved.compact)
            assertEquals(36, saved.reminderHours)
            assertEquals(ai.baseUrl, saved.baseUrl)
            assertEquals(ai.model, saved.model)
            assertEquals(ai.temperature, saved.temperature)
            assertEquals(account, store.accountId.first())
        } finally { store.save(previous) }
    }
}
