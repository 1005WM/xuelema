package com.homeworkassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.homeworkassistant.domain.model.AppSettings
import com.homeworkassistant.domain.model.ThemeMode
import com.homeworkassistant.ui.AiConnectionUiState
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsModelSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingAServiceModelSavesItsExactIdentifier() {
        var savedModel: String? = null
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(baseUrl = "https://api.deepseek.com"), { "fixture-key-not-real" },
                { settings, _ -> savedModel = settings.model }, { _, _ -> }, {}, {}, {}, {},
                connection = AiConnectionUiState(models = listOf("model-a", "model-b")))
        } }
        compose.onNodeWithText("模型（下拉选择或手动填写）").performScrollTo().performClick()
        compose.onNodeWithText("model-b").performClick()
        compose.runOnIdle { assertEquals("model-b", savedModel) }
    }

    @Test fun unreadableOldKeyCanBeReplacedBeforeFetchingModels() {
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(baseUrl = "https://api.deepseek.com"), { error("fixture decryption failure") },
                { _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("API Key").performScrollTo().performTextInput("replacement-fixture-key")
        compose.onNodeWithText("保存并获取模型").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("fixture decryption failure").assertDoesNotExist()
    }
    @Test fun appearanceAppliesImmediatelyEvenWithNoApiAddressAndAnUnreadableKey() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var background = Color.Unspecified
        var aiSaves = 0
        compose.setContent { HomeworkTheme(mode) {
            val color = MaterialTheme.colorScheme.background
            SideEffect { background = color }
            SettingsScreen(AppSettings(theme = mode), { error("fixture key failure") },
                { _, _ -> aiSaves++ }, { _, _ -> }, {}, {}, {}, {},
                onAppearanceChange = { mode = it.theme })
        } }
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, mode)
            assertTrue(background.luminance() < 0.5f)
            assertEquals(0, aiSaves)
        }
        compose.onNodeWithText("浅色").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.LIGHT, mode)
            assertTrue(background.luminance() > 0.5f)
        }
        compose.onNodeWithText("系统").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemeMode.SYSTEM, mode) }
    }
    @Test fun temperatureHasAReadableLabelAndMeaningInsteadOfADegreeValue() {
        compose.setContent { HomeworkTheme {
            SettingsScreen(AppSettings(temperature = 0.2f), { "" }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("回答随机性（temperature）：0.20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("数值越低，回答通常越稳定", substring = true).assertExists()
    }
}
