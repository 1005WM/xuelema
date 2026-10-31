package com.homeworkassistant.domain.model

import java.time.Instant

data class CourseCacheInfo(val successfulAt: Instant?, val lastError: String? = null, val dirty: Boolean = false)
data class CachedCourseHomework(val items: List<Homework>, val info: CourseCacheInfo)
data class CourseHomeworkUpdate(val course: Course, val items: List<Homework>, val complete: Boolean, val successfulAt: Instant)

/** Product defaults only; these are not platform safety limits. */
object ReadCacheDefaults {
    const val CATALOG_MILLIS = 30 * 60 * 1000L
    const val HOMEWORK_MILLIS = 5 * 60 * 1000L
    fun fresh(time: Instant?, now: Instant, ttl: Long) = time != null && now.toEpochMilli() - time.toEpochMilli() in 0 until ttl
}
