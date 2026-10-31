package com.homeworkassistant.data.mcp

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import com.homeworkassistant.domain.model.ReadCacheDefaults

data class CachedCourseCatalog(val courses: List<McpCourse>, val complete: Boolean, val successfulAt: Long) {
    fun fresh(now: Long) = now - successfulAt in 0 until ReadCacheDefaults.CATALOG_MILLIS
}

/** Persist course entry metadata, never enc/stuenc, cookies or submission states. */
interface CourseReadCache {
    fun course(account: String, id: String): McpCourse?
    fun remember(account: String, courses: List<McpCourse>)
    fun school(account: String): String?
    fun rememberSchool(account: String, fid: String)
    fun catalog(account: String): CachedCourseCatalog?
    fun rememberCatalog(account: String, catalog: CachedCourseCatalog)
    fun invalidateCatalog(account: String)
    fun clear()
}

class MemoryCourseReadCache : CourseReadCache {
    private val courses = ConcurrentHashMap<Pair<String, String>, McpCourse>()
    private val schools = ConcurrentHashMap<String, Pair<String, Long>>()
    private val catalogs = ConcurrentHashMap<String, CachedCourseCatalog>()
    override fun catalog(account: String) = catalogs[account]
    override fun rememberCatalog(account: String, catalog: CachedCourseCatalog) { catalogs[account] = catalog; remember(account, catalog.courses) }
    override fun invalidateCatalog(account: String) { catalogs.remove(account); schools.remove(account) }
    override fun course(account: String, id: String) = courses[account to id]
    override fun remember(account: String, courses: List<McpCourse>) { courses.forEach { this.courses[account to it.domain.id] = it } }
    override fun school(account: String) = schools[account]?.takeIf { System.currentTimeMillis() - it.second in 0..120_000 }?.first
    override fun rememberSchool(account: String, fid: String) { schools[account] = fid to System.currentTimeMillis() }
    override fun clear() { courses.clear(); schools.clear(); catalogs.clear() }
}

class PersistentCourseReadCache(context: Context) : CourseReadCache {
    private val prefs = context.getSharedPreferences("course-read-entries", Context.MODE_PRIVATE)
    private val gson = Gson()
    override fun catalog(account: String): CachedCourseCatalog? = runCatching {
        gson.fromJson(prefs.getString("catalog:$account", null), CachedCourseCatalog::class.java)?.takeIf { value ->
            value.courses.all { it.courseId.matches(Regex("\\d+")) && it.classId.matches(Regex("\\d+")) &&
                it.cpi.matches(Regex("\\d*")) && it.lifecycle != null } }
    }.getOrNull()
    override fun rememberCatalog(account: String, catalog: CachedCourseCatalog) {
        remember(account, catalog.courses)
        prefs.edit { putString("catalog:$account", gson.toJson(catalog)) }
    }
    override fun invalidateCatalog(account: String) { prefs.edit { remove("catalog:$account"); remove("school:$account"); remove("school-time:$account") } }
    override fun course(account: String, id: String): McpCourse? = runCatching {
        gson.fromJson(prefs.getString("course:$account:$id", null), McpCourse::class.java)?.takeIf { it.domain.id == id &&
            it.courseId.matches(Regex("\\d+")) && it.classId.matches(Regex("\\d+")) && it.cpi.matches(Regex("\\d*")) }
    }.getOrNull()
    override fun remember(account: String, courses: List<McpCourse>) {
        prefs.edit { courses.forEach { putString("course:$account:${it.domain.id}", gson.toJson(it)) } }
    }
    override fun school(account: String): String? {
        val age = System.currentTimeMillis() - prefs.getLong("school-time:$account", 0)
        return prefs.getString("school:$account", null)?.takeIf { age in 0..120_000 && it.matches(Regex("\\d+")) }
    }
    override fun rememberSchool(account: String, fid: String) {
        prefs.edit { putString("school:$account", fid); putLong("school-time:$account", System.currentTimeMillis()) }
    }
    override fun clear() { prefs.edit { clear() } }
}
