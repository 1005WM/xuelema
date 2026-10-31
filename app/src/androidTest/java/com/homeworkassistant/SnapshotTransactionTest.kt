package com.homeworkassistant

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.homeworkassistant.data.local.*
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SnapshotTransactionTest {
    @Test fun partialCurrentPageUpdatesDoNotDeleteOtherCourses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = HomeworkRepositoryImpl(db)
            val a = Homework("a", Course("math", "数学"), "练习", "https://i.chaoxing.com", status = HomeworkStatus.NOT_SUBMITTED)
            val b = a.copy(id = "b", course = Course("english", "英语"))
            repo.publish(HomeworkSnapshot("one",1,listOf(a,b),Instant.now(),SnapshotCoverage.COMPLETE,2,2))
            repo.publish(HomeworkSnapshot("one",2,listOf(a.copy(status = HomeworkStatus.SUBMITTED)),Instant.now(),SnapshotCoverage.PARTIAL,1,0), preserveUnseen = true)
            val result = repo.observe("one").first()!!
            assertEquals(2, result.items.size)
            assertEquals(HomeworkStatus.SUBMITTED, result.items.first { it.id == "a" }.status)
            assertEquals(HomeworkStatus.NOT_SUBMITTED, result.items.first { it.id == "b" }.status)
            assertEquals(SnapshotCoverage.PARTIAL, result.coverage)
        } finally { db.close() }
    }
    @Test fun replacementAndAccountIsolation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = HomeworkRepositoryImpl(db)
            val h = Homework("a", Course("c", "数学"), "练习", "https://i.chaoxing.com", status = HomeworkStatus.NOT_SUBMITTED)
            repo.publish(HomeworkSnapshot("one",1,listOf(h),Instant.now(),SnapshotCoverage.COMPLETE,1,1))
            repo.publish(HomeworkSnapshot("two",1,listOf(h.copy(id = "b")),Instant.now(),SnapshotCoverage.COMPLETE,1,1))
            repo.publish(HomeworkSnapshot("one",2,emptyList(),Instant.now(),SnapshotCoverage.COMPLETE,1,1))
            assertTrue(repo.observe("one").first()!!.items.isEmpty())
            assertEquals("b", repo.observe("two").first()!!.items.single().id)
            assertEquals(2L, repo.observe("one").first()!!.revision)
        } finally { db.close() }
    }
}
