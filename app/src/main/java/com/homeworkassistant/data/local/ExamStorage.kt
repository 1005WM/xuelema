package com.homeworkassistant.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.ExamRepository
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

@Entity(tableName = "exams", primaryKeys = ["accountId", "id"])
data class ExamEntity(val accountId: String, val id: String, val title: String, val timeText: String,
    val startsAt: Long?, val endsAt: Long?, val duration: String, val scheduleStatus: String,
    val answerStatus: String, val score: String, val mode: String, val submissionStatus: String,
    val courseId: String? = null, val classId: String? = null, val courseName: String? = null,
    val detailUrl: String? = null)
@Entity(tableName = "exam_sync")
data class ExamSyncEntity(@PrimaryKey val accountId: String, val fetchedAt: Long)
data class ExamRecord(@Embedded val metadata: ExamSyncEntity,
    @Relation(parentColumn = "accountId", entityColumn = "accountId") val items: List<ExamEntity>)
@Dao interface ExamDao {
    @Transaction @Query("SELECT * FROM exam_sync WHERE accountId = :accountId")
    fun observe(accountId: String): kotlinx.coroutines.flow.Flow<ExamRecord?>
    @Query("DELETE FROM exams WHERE accountId = :accountId") suspend fun delete(accountId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<ExamEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun metadata(value: ExamSyncEntity)
    @Query("DELETE FROM exams") suspend fun clearItems()
    @Query("DELETE FROM exam_sync") suspend fun clearMetadata()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `exams` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT NOT NULL, `timeText` TEXT NOT NULL, `startsAt` INTEGER, `endsAt` INTEGER, `duration` TEXT NOT NULL, `scheduleStatus` TEXT NOT NULL, `answerStatus` TEXT NOT NULL, `score` TEXT NOT NULL, `mode` TEXT NOT NULL, `submissionStatus` TEXT NOT NULL, PRIMARY KEY(`accountId`, `id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `exam_sync` (`accountId` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exams` ADD COLUMN `courseId` TEXT")
        db.execSQL("ALTER TABLE `exams` ADD COLUMN `classId` TEXT")
        db.execSQL("ALTER TABLE `exams` ADD COLUMN `courseName` TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `homework` ADD COLUMN `dueText` TEXT")
        db.execSQL("ALTER TABLE `exams` ADD COLUMN `detailUrl` TEXT")
    }
}

class ExamRepositoryImpl @Inject constructor(private val db: AppDatabase) : ExamRepository {
    override fun observe(accountId: String) = db.examDao().observe(accountId).map { record ->
        record?.let { ExamSnapshot(accountId, it.items.map { e -> Exam(e.id, e.title, e.timeText,
            e.startsAt?.let(Instant::ofEpochMilli), e.endsAt?.let(Instant::ofEpochMilli), e.duration,
            e.scheduleStatus, e.answerStatus, e.score, e.mode,
            runCatching { HomeworkStatus.valueOf(e.submissionStatus) }.getOrDefault(HomeworkStatus.UNKNOWN),
            e.courseId, e.classId, e.courseName, e.detailUrl) },
            Instant.ofEpochMilli(it.metadata.fetchedAt)) }
    }
    override suspend fun publish(snapshot: ExamSnapshot) {
        require(snapshot.items.map { it.id }.distinct().size == snapshot.items.size)
        db.withTransaction {
            val dao = db.examDao()
            dao.delete(snapshot.accountId)
            dao.put(snapshot.items.map { e -> ExamEntity(snapshot.accountId, e.id, e.title, e.timeText,
                e.startsAt?.toEpochMilli(), e.endsAt?.toEpochMilli(), e.duration, e.scheduleStatus,
                e.answerStatus, e.score, e.mode, e.submissionStatus.name, e.courseId, e.classId, e.courseName, e.detailUrl) })
            dao.metadata(ExamSyncEntity(snapshot.accountId, snapshot.fetchedAt.toEpochMilli()))
        }
    }
    override suspend fun clear() = db.withTransaction { db.examDao().clearItems(); db.examDao().clearMetadata() }
}
