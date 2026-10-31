package com.homeworkassistant

import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.SettingsDataStore
import com.homeworkassistant.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuidePersistenceTest {
    @Test fun completionPersistsAcrossRepositoryRecreationAndIsIndependentOfAiAppearanceAndLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsDataStore(context)
        val initial = store.settings.first()
        val account = store.accountId.first()
        try {
            store.completeGuide()
            assertTrue(SettingsDataStore(context).guideCompleted.first())
            store.save(initial.copy(baseUrl = "", model = ""))
            store.saveAppearance(ThemeMode.DARK, false, 24)
            store.setAccount(null)
            assertTrue(SettingsDataStore(context).guideCompleted.first())
        } finally { store.save(initial); store.setAccount(account) }
    }
}
