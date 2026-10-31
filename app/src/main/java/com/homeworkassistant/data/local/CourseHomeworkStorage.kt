package com.homeworkassistant.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "course_work_cache", primaryKeys = ["accountId", "courseId"])
data class CourseWorkCacheEntity(val accountId: String, val courseId: String, val successfulAt: Long?, val lastError: String?, val dirty: Boolean)

@Dao interface CourseWorkCacheDao {
    @Query("SELECT * FROM course_work_cache WHERE accountId = :account") suspend fun read(account: String): List<CourseWorkCacheEntity>
    @Query("SELECT * FROM course_work_cache WHERE accountId = :account") fun observe(account: String): Flow<List<CourseWorkCacheEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: CourseWorkCacheEntity)
    @Query("DELETE FROM course_work_cache") suspend fun clear()
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `course_work_cache` (`accountId` TEXT NOT NULL, `courseId` TEXT NOT NULL, `successfulAt` INTEGER, `lastError` TEXT, `dirty` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `courseId`))")
    }
}
