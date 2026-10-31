package com.homeworkassistant

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.*
import com.homeworkassistant.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ExamMigrationTest {
    @Test fun upgradeFromV3KeepsExistingRowsAndPersistsDeadlineTextAndExamEntrances() = runBlocking { verifyUpgrade(3) }
    @Test fun upgradeFromV4KeepsExistingRowsWithoutInventingCourseFreshness() = runBlocking { verifyUpgrade(4) }
    private suspend fun verifyUpgrade(version: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "metadata-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("com.homeworkassistant.data.local.AppDatabase/$version.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO homework (accountId,id,courseId,courseName,title,detailUrl,status) VALUES ('one','old','11:22','数学','旧作业','https://i.chaoxing.com','NOT_SUBMITTED')")
            old.execSQL("INSERT INTO sync_metadata VALUES ('one',1,0,'COMPLETE',1,1)")
            old.execSQL("INSERT INTO exams (accountId,id,title,timeText,duration,scheduleStatus,answerStatus,score,mode,submissionStatus) VALUES ('one','old-exam','旧考试','另行通知','60','已结束','已交卷','90','线上','SUBMITTED')")
            old.execSQL("INSERT INTO exam_sync VALUES ('one',0)")
            old.version = version
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            val home = HomeworkRepositoryImpl(db)
            assertTrue(home.courseCache("one").isEmpty())
            val old = home.observe("one").first()!!.items.single()
            assertNull(old.dueText)
            home.updateDetails("one", listOf(old.copy(dueText = "10-30 23:59（年份待确认）")))
            assertEquals("10-30 23:59（年份待确认）", home.observe("one").first()!!.items.single().dueText)
            val exams = ExamRepositoryImpl(db)
            val cached = exams.observe("one").first()!!
            assertEquals("old-exam", cached.items.single().id)
            assertNull(cached.items.single().detailUrl)
            val updated = cached.items.single().copy(detailUrl = "https://mooc1.chaoxing.com/exam-ans/exam/test?examId=1")
            exams.publish(cached.copy(items = listOf(updated)))
            assertEquals(updated, exams.observe("one").first()!!.items.single())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun upgradePreservesHomeworkAndAddsAccountIsolatedExamCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "exam-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("com.homeworkassistant.data.local.AppDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO homework (accountId,id,courseId,courseName,title,detailUrl,status) VALUES ('one','old','course','数学','旧缓存','https://i.chaoxing.com','NOT_SUBMITTED')")
            old.execSQL("INSERT INTO sync_metadata VALUES ('one',1,0,'COMPLETE',1,1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            assertEquals("old", HomeworkRepositoryImpl(db).observe("one").first()!!.items.single().id)
            val exams = ExamRepositoryImpl(db)
            val exam = Exam("exam", "数学考试", "另行通知", null, null, "60分钟", "已结束", "未交卷", "--", "线上", HomeworkStatus.NOT_SUBMITTED,
                courseId = "11", classId = "22", courseName = "数学", detailUrl = "https://mooc1.chaoxing.com/exam-ans/exam/test?examId=1")
            exams.publish(ExamSnapshot("one", listOf(exam), Instant.EPOCH))
            assertEquals(exam, exams.observe("one").first()!!.items.single())
            exams.publish(ExamSnapshot("two", listOf(exam.copy(id = "other")), Instant.EPOCH))
            exams.publish(ExamSnapshot("one", emptyList(), Instant.EPOCH))
            assertTrue(exams.observe("one").first()!!.items.isEmpty())
            assertEquals("other", exams.observe("two").first()!!.items.single().id)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
