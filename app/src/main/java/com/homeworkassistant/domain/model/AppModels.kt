package com.homeworkassistant.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Float = 0.2f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val compact: Boolean = false,
    val reminderHours: Int = 24,
    val inputBudgetChars: Int = 18000,
    val outputTokens: Int = 2048,
    val imageInputEnabled: Boolean = false,
    val fillIntervalMillis: Int = DEFAULT_FILL_INTERVAL_MILLIS,
) {
    companion object {
        const val DEFAULT_FILL_INTERVAL_MILLIS = 1500
        const val MIN_FILL_INTERVAL_MILLIS = 500
        const val MAX_FILL_INTERVAL_MILLIS = 5000
    }
}
enum class QuestionType { SINGLE, MULTIPLE, BOOLEAN, FILL, SHORT, MATCHING, UNKNOWN }
data class QuestionImage(val id: String, val url: String, val optionKey: String = "", val location: String = "stem",
    val referer: String = "")
data class Question(
    val id: String,
    val number: Int,
    val type: QuestionType,
    val stem: String,
    val options: List<String>,
    val hasUnsupportedMedia: Boolean = false,
    val optionKeys: List<String> = emptyList(),
    val images: List<QuestionImage> = emptyList(),
    val canAnalyze: Boolean = true,
    val canFill: Boolean = false,
    val diagnostics: List<String> = emptyList(),
    val sourceAdapter: String = "",
    val sourceNumber: Int = number,
    val sourcePart: String = "",
) {
    val displayLabel: String get() = "第 $sourceNumber 题" + if (sourcePart.isBlank()) "" else " · $sourcePart"
}
data class QuestionFrame(val frameId: String, val url: String, val fingerprint: String)
data class QuestionPage(val url: String, val fingerprint: String, val questions: List<Question>,
    val frames: List<QuestionFrame> = emptyList(), val warnings: List<String> = emptyList())
data class Answer(val questionId: String, val answer: String, val explanation: String)
sealed interface AiEvent {
    data class Progress(val text: String) : AiEvent
    data class Batch(val answers: List<Answer>) : AiEvent
    data class Warning(val message: String) : AiEvent
}
data class HomeFilter(
    val completed: Boolean = false,
    val courseId: String? = null,
    val query: String = "",
    val ascending: Boolean = true,
)
data class HomeContent(val summary: OverviewSummary, val visibleItems: List<Homework>)
