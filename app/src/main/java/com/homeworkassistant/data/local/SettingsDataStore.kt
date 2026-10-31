package com.homeworkassistant.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("settings")
@Singleton
class SettingsDataStore @Inject constructor(@param:ApplicationContext private val context: Context) : SettingsRepository {
    private val store get() = context.preferences
    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    override val accountId = data.map { it[stringPreferencesKey("account")] }
    override val guideCompleted = data.map { it[booleanPreferencesKey("guideCompleted")] ?: false }
    override suspend fun completeGuide() { store.edit { it[booleanPreferencesKey("guideCompleted")] = true } }
    override val settings = data.map { p ->
        AppSettings(p[stringPreferencesKey("url")] ?: "", p[stringPreferencesKey("model")] ?: "",
            p[floatPreferencesKey("temperature")] ?: 0.2f,
            runCatching { ThemeMode.valueOf(p[stringPreferencesKey("theme")] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            p[booleanPreferencesKey("compact")] ?: false, p[intPreferencesKey("hours")] ?: 24,
            p[intPreferencesKey("inputBudget")] ?: 18000, p[intPreferencesKey("outputTokens")] ?: 2048,
            p[booleanPreferencesKey("imageInput")] ?: false,
            (p[intPreferencesKey("fillIntervalMillis")] ?: AppSettings.DEFAULT_FILL_INTERVAL_MILLIS)
                .coerceIn(AppSettings.MIN_FILL_INTERVAL_MILLIS, AppSettings.MAX_FILL_INTERVAL_MILLIS))
    }
    override suspend fun save(settings: AppSettings) { store.edit { p ->
        p[stringPreferencesKey("url")] = settings.baseUrl.trim()
        p[stringPreferencesKey("model")] = settings.model.trim()
        p[floatPreferencesKey("temperature")] = settings.temperature.coerceIn(0f, 2f)
        p[stringPreferencesKey("theme")] = settings.theme.name
        p[booleanPreferencesKey("compact")] = settings.compact
        p[intPreferencesKey("hours")] = settings.reminderHours.coerceIn(1, 168)
        p[intPreferencesKey("inputBudget")] = settings.inputBudgetChars.coerceIn(2000, 100000)
        p[intPreferencesKey("outputTokens")] = settings.outputTokens.coerceIn(256, 16384)
        p[booleanPreferencesKey("imageInput")] = settings.imageInputEnabled
        p[intPreferencesKey("fillIntervalMillis")] = settings.fillIntervalMillis
            .coerceIn(AppSettings.MIN_FILL_INTERVAL_MILLIS, AppSettings.MAX_FILL_INTERVAL_MILLIS)
    } }
    override suspend fun setAccount(id: String?) { store.edit {
        if (id == null) it.remove(stringPreferencesKey("account")) else it[stringPreferencesKey("account")] = id
    } }
    override suspend fun saveAppearance(theme: ThemeMode, compact: Boolean, reminderHours: Int) {
        store.edit { p ->
            p[stringPreferencesKey("theme")] = theme.name
            p[booleanPreferencesKey("compact")] = compact
            p[intPreferencesKey("hours")] = reminderHours.coerceIn(1, 168)
        }
    }
    override suspend fun saveFillInterval(intervalMillis: Int) {
        store.edit { it[intPreferencesKey("fillIntervalMillis")] = intervalMillis
            .coerceIn(AppSettings.MIN_FILL_INTERVAL_MILLIS, AppSettings.MAX_FILL_INTERVAL_MILLIS) }
    }
}
