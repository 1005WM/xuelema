package com.homeworkassistant.data.sync

import com.homeworkassistant.data.web.TrustedOriginPolicy
import java.net.URI

/** Only in-memory, account-scoped URLs; signed query strings never enter logs or settings. */
class HomeworkSyncEntries {
    private val observed = mutableMapOf<String, LinkedHashSet<String>>()
    fun remember(accountId: String, url: String) {
        if (!TrustedOriginPolicy().allows(url)) return
        val path = URI(url).path
        if (!Regex("/work/(?:list|stu-work|student|stuwork)(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return
        val entries = observed.getOrPut(accountId) { linkedSetOf() }
        entries.remove(url); entries.add(url)
        while (entries.size > 8) entries.remove(entries.first())
    }
    fun primary(accountId: String): List<String> = (observed[accountId]?.toList()?.reversed().orEmpty() + AGGREGATE).distinct()
    fun clear() = observed.clear()
    companion object {
        const val AGGREGATE = "https://mooc1-api.chaoxing.com/work/stu-work"
        const val FALLBACK = "https://i.chaoxing.com"
    }
}
