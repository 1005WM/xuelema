package com.homeworkassistant.ui.home

import com.homeworkassistant.domain.model.*

data class HomeUiState(
    val initialized: Boolean = false,
    val accountId: String? = null,
    val snapshot: HomeworkSnapshot? = null,
    val content: HomeContent? = null,
    val settings: AppSettings = AppSettings(),
    val filter: HomeFilter = HomeFilter(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val needsLogin: Boolean = false,
    val cacheLabel: Boolean = true,
    val syncProgress: String = "",
    val detailsUpdating: Boolean = false,
    val aiConfigured: Boolean? = null,
    val courseCache: Map<String, com.homeworkassistant.domain.model.CourseCacheInfo> = emptyMap(),
    val currentCourseId: String? = null,
)
