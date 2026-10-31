package com.homeworkassistant.data

import com.homeworkassistant.data.sync.HomeworkSyncEntries
import org.junit.Assert.*
import org.junit.Test

class HomeworkSyncEntriesTest {
    @Test fun refreshStartsAtTheOfficialHomeworkPage() {
        assertEquals(listOf(HomeworkSyncEntries.AGGREGATE), HomeworkSyncEntries().primary("account"))
    }
    @Test fun observedListIsPreferredOnlyForItsAccount() {
        val entries = HomeworkSyncEntries()
        val url = "https://mooc1.chaoxing.com/mooc2/work/list?courseId=11&classId=22"
        entries.remember("one", url)
        assertEquals(url, entries.primary("one").first())
        assertEquals(listOf(HomeworkSyncEntries.AGGREGATE), entries.primary("two"))
        entries.clear()
        assertEquals(listOf(HomeworkSyncEntries.AGGREGATE), entries.primary("one"))
    }
    @Test fun detailExternalAndInsecureAddressesCannotBecomeListEntrances() {
        val entries = HomeworkSyncEntries()
        for (url in listOf("https://evil.test/work/list", "http://mooc1.chaoxing.com/work/list",
            "https://mooc1.chaoxing.com/mooc2/work/task?workId=77", "https://i.chaoxing.com/base")) entries.remember("one", url)
        assertEquals(listOf(HomeworkSyncEntries.AGGREGATE), entries.primary("one"))
    }
}
