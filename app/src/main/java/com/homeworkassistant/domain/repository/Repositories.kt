package com.homeworkassistant.domain.repository

import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.flow.Flow

interface HomeworkRepository {
    fun observe(accountId: String): Flow<HomeworkSnapshot?>
    suspend fun publish(snapshot: HomeworkSnapshot, preserveUnseen: Boolean = false)
    suspend fun updateStatuses(accountId: String, updates: List<Homework>): Int
    suspend fun updateDetails(accountId: String, updates: List<Homework>): Int
    suspend fun courseCache(accountId: String): Map<String, CachedCourseHomework> = emptyMap()
    fun observeCourseCache(accountId: String): Flow<Map<String, CourseCacheInfo>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun publishRead(snapshot: HomeworkSnapshot, updates: List<CourseHomeworkUpdate>, preserveUnseen: Boolean) = publish(snapshot, preserveUnseen)
    suspend fun courseReadFailed(accountId: String, courseId: String, message: String) = Unit
    suspend fun clear()
}
interface ExamRepository {
    fun observe(accountId: String): Flow<ExamSnapshot?>
    suspend fun publish(snapshot: ExamSnapshot)
    suspend fun clear()
}
interface SettingsRepository {
    val settings: Flow<AppSettings>
    val accountId: Flow<String?>
    val guideCompleted: Flow<Boolean>
    suspend fun completeGuide()
    suspend fun save(settings: AppSettings)
    suspend fun saveAppearance(theme: ThemeMode, compact: Boolean, reminderHours: Int)
    suspend fun saveFillInterval(intervalMillis: Int)
    suspend fun setAccount(id: String?)
}
interface AiRepository {
    suspend fun models(settings: AppSettings): List<String>
    fun generate(questions: List<Question>, settings: AppSettings): Flow<AiEvent>
    suspend fun test(settings: AppSettings): String
}
