package com.homeworkassistant

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run the two methods in separate instrumentation invocations with am force-stop in between. */
class CourseCacheRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("restart-proof-$name", mode)
    }
    private val name = "course-cache-restart-proof.db"

    @Test fun seedDiskCacheBeforeProcessStop() = runBlocking {
        context.deleteDatabase(name)
        val entries = PersistentCourseReadCache(cacheContext)
        entries.clear()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var requests = 0
        try {
            val reader = McpChaoxingReader(McpReadTransport { url ->
                requests++
                val html = when {
                    url.endsWith("/base") -> "<input id=fid value=123>"
                    url.contains("courselistdata") -> "<div class=course><input class=courseId value=11><input class=clazzId value=22><input class=cpi value=33><span class=course-name>重启验证</span></div>"
                    url.contains("stucoursemiddle") -> "<input id=workEnc value=private-signature><input id=enc value=private-stuenc>"
                    else -> "<span>0/1</span><li data-workid=1 data='/work/task?workId=1'><h3>磁盘作业缓存</h3><p class=status>未提交</p></li>"
                }
                OfficialHtml(url, html)
            }, entries)
            val repo = HomeworkRepositoryImpl(db)
            HomeworkSyncCoordinator(reader, repo, ExamRepositoryImpl(db)).sync("restart-account", includeDetails = false, includeExams = false)
            assertEquals(4, requests)
            assertEquals(1, repo.observe("restart-account").first()!!.items.size)
            // Also wait for SharedPreferences' pending async writes before deliberately killing the process.
            cacheContext.getSharedPreferences("course-read-entries", Context.MODE_PRIVATE).edit(commit = true) { putInt("seed-pid", Process.myPid()) }
            println("RESTART_PROOF seeded_pid=${Process.myPid()} network_requests=$requests")
        } finally { db.close() }
    }

    @Test fun restoreDiskCacheInANewProcessWithoutNetworkRequests() = runBlocking {
        val prefs = cacheContext.getSharedPreferences("course-read-entries", Context.MODE_PRIVATE)
        val seedPid = prefs.getInt("seed-pid", -1)
        assertTrue("Run the seed method first", seedPid > 0)
        assertNotEquals("This must be a different Android process", seedPid, Process.myPid())
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val repo = HomeworkRepositoryImpl(db)
            val before = repo.observe("restart-account").first()!!
            assertEquals("磁盘作业缓存", before.items.single().title)
            val reader = McpChaoxingReader(McpReadTransport { throw AssertionError("Fresh disk caches must not require any HTTP requests") }, PersistentCourseReadCache(cacheContext))
            val result = HomeworkSyncCoordinator(reader, repo, ExamRepositoryImpl(db)).sync("restart-account", includeDetails = false, includeExams = false)
            assertFalse(result.networkUpdated)
            assertEquals(before.fetchedAt, repo.observe("restart-account").first()!!.fetchedAt)
            println("RESTART_PROOF restored_pid=${Process.myPid()} previous_pid=$seedPid network_requests=0")
        } finally { db.close(); context.deleteDatabase(name); PersistentCourseReadCache(cacheContext).clear() }
    }
}
