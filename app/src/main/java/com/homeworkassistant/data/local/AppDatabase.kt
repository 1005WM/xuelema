package com.homeworkassistant.data.local

import androidx.room.*
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.HomeworkRepository
import com.homeworkassistant.domain.usecase.MergePartialSnapshotUseCase
import com.homeworkassistant.domain.usecase.ApplyHomeworkStatusesUseCase
import com.homeworkassistant.domain.usecase.ApplyHomeworkDetailsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

@Entity(tableName = "homework", primaryKeys = ["accountId", "id"])
data class HomeworkEntity(
    val accountId: String, val id: String, val courseId: String, val courseName: String,
    val title: String, val detailUrl: String, val publishedAt: Long?, val dueAt: Long?,
    val submittedAt: Long?, val status: String, val score: Double?, val maxScore: Double?,
    val canSubmit: Boolean?, val rawStatus: String?,
    val dueText: String? = null,
)
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val accountId: String, val revision: Long, val fetchedAt: Long,
    val coverage: String, val discoveredCourses: Int, val completedCourses: Int,
)
data class SnapshotRecord(
    @Embedded val metadata: SyncMetadataEntity,
    @Relation(parentColumn = "accountId", entityColumn = "accountId") val items: List<HomeworkEntity>,
)
@Dao
interface HomeworkDao {
    @Transaction
    @Query("SELECT * FROM sync_metadata WHERE accountId = :accountId")
    fun observe(accountId: String): Flow<SnapshotRecord?>
    @Transaction
    @Query("SELECT * FROM sync_metadata WHERE accountId = :accountId")
    suspend fun read(accountId: String): SnapshotRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItems(items: List<HomeworkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: SyncMetadataEntity)
    @Query("DELETE FROM homework WHERE accountId = :accountId")
    suspend fun deleteItems(accountId: String)
    @Query("DELETE FROM homework") suspend fun deleteAllItems()
    @Query("DELETE FROM sync_metadata") suspend fun deleteAllMetadata()
}
@Database(entities = [HomeworkEntity::class, SyncMetadataEntity::class, ExamEntity::class, ExamSyncEntity::class, CourseWorkCacheEntity::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun homeworkDao(): HomeworkDao
    abstract fun examDao(): ExamDao
    abstract fun courseWorkCacheDao(): CourseWorkCacheDao
}

class HomeworkRepositoryImpl @Inject constructor(private val db: AppDatabase) : HomeworkRepository {
    private fun CourseWorkCacheEntity.info() = CourseCacheInfo(successfulAt?.let(Instant::ofEpochMilli), lastError, dirty)
    override fun observeCourseCache(accountId: String) = db.courseWorkCacheDao().observe(accountId).map { values -> values.associate { it.courseId to it.info() } }
    override suspend fun courseCache(accountId: String): Map<String, CachedCourseHomework> = db.withTransaction {
        val items = observe(accountId).first()?.items.orEmpty().groupBy { it.course.id }
        db.courseWorkCacheDao().read(accountId).associate { it.courseId to CachedCourseHomework(items[it.courseId].orEmpty(), it.info()) }
    }
    override suspend fun courseReadFailed(accountId: String, courseId: String, message: String) = db.withTransaction {
        val old = db.courseWorkCacheDao().read(accountId).firstOrNull { it.courseId == courseId }
        db.courseWorkCacheDao().put(CourseWorkCacheEntity(accountId, courseId, old?.successfulAt, message, true))
    }
    override suspend fun publishRead(snapshot: HomeworkSnapshot, updates: List<CourseHomeworkUpdate>, preserveUnseen: Boolean) = db.withTransaction {
        val old = observe(snapshot.accountId).first()
        val states = db.courseWorkCacheDao().read(snapshot.accountId).associateBy { it.courseId }
        val accepted = updates.filter { update -> states[update.course.id]?.successfulAt?.let { it > update.successfulAt.toEpochMilli() } != true }
        if (accepted.isEmpty() && old != null) {
            val metadata = db.homeworkDao().read(snapshot.accountId)!!.metadata
            if (metadata.coverage != snapshot.coverage.name || metadata.discoveredCourses != snapshot.discoveredCourses || metadata.completedCourses != snapshot.completedCourses)
                db.homeworkDao().putMetadata(metadata.copy(revision = metadata.revision + 1, coverage = snapshot.coverage.name,
                    discoveredCourses = snapshot.discoveredCourses, completedCourses = snapshot.completedCourses))
            return@withTransaction
        }
        val merged = old?.items.orEmpty().associateByTo(linkedMapOf()) { it.id }
        accepted.forEach { update ->
            val previous = merged.values.filter { it.course.id == update.course.id }.associateBy { it.id }
            if (update.complete) merged.entries.removeAll { it.value.course.id == update.course.id }
            update.items.forEach { fresh ->
                val known = previous[fresh.id]
                merged[fresh.id] = fresh.copy(dueAt = if (fresh.dueText != null) fresh.dueAt else fresh.dueAt ?: known?.dueAt, dueText = fresh.dueText ?: known?.dueText,
                    publishedAt = fresh.publishedAt ?: known?.publishedAt,
                    submittedAt = fresh.submittedAt ?: known?.submittedAt, maxScore = fresh.maxScore ?: known?.maxScore,
                    score = fresh.score ?: known?.score?.takeIf { fresh.status.isCompleted && known.status.isCompleted && fresh.rawStatus == known.rawStatus })
            }
            // Partial pages do not confer a fresh five-minute TTL.
            db.courseWorkCacheDao().put(CourseWorkCacheEntity(snapshot.accountId, update.course.id,
                if (update.complete) update.successfulAt.toEpochMilli() else states[update.course.id]?.successfulAt,
                if (update.complete) null else "分页尚未确认，保留原作业", !update.complete))
        }
        val successTime = accepted.filter { it.complete }.maxOfOrNull { it.successfulAt }
        publish(snapshot.copy(items = merged.values.toList(), revision = (old?.revision ?: 0) + 1,
            fetchedAt = listOfNotNull(old?.fetchedAt, successTime).maxOrNull() ?: snapshot.fetchedAt), false)
    }
    override fun observe(accountId: String) = db.homeworkDao().observe(accountId).map { record ->
        record?.let { r ->
            val m = r.metadata
            HomeworkSnapshot(m.accountId, m.revision, r.items.map { e ->
                Homework(e.id, Course(e.courseId, e.courseName), e.title, e.detailUrl,
                    e.publishedAt?.let(Instant::ofEpochMilli), e.dueAt?.let(Instant::ofEpochMilli),
                    e.submittedAt?.let(Instant::ofEpochMilli),
                    runCatching { HomeworkStatus.valueOf(e.status) }.getOrDefault(HomeworkStatus.UNKNOWN),
                    e.score, e.maxScore, e.canSubmit, e.rawStatus, e.dueText)
            }, Instant.ofEpochMilli(m.fetchedAt), SnapshotCoverage.valueOf(m.coverage),
                m.discoveredCourses, m.completedCourses)
        }
    }
    override suspend fun publish(snapshot: HomeworkSnapshot, preserveUnseen: Boolean) {
        require(snapshot.items.map { it.id }.distinct().size == snapshot.items.size) { "作业标识重复" }
        db.withTransaction {
            val dao = db.homeworkDao()
            val value = if (preserveUnseen) {
                val previous = dao.read(snapshot.accountId)?.let { record ->
                    val m = record.metadata
                    HomeworkSnapshot(m.accountId, m.revision, record.items.map { e ->
                        Homework(e.id, Course(e.courseId, e.courseName), e.title, e.detailUrl,
                            e.publishedAt?.let(Instant::ofEpochMilli), e.dueAt?.let(Instant::ofEpochMilli), e.submittedAt?.let(Instant::ofEpochMilli),
                            runCatching { HomeworkStatus.valueOf(e.status) }.getOrDefault(HomeworkStatus.UNKNOWN),
                            e.score, e.maxScore, e.canSubmit, e.rawStatus, e.dueText)
                    }, Instant.ofEpochMilli(m.fetchedAt), SnapshotCoverage.valueOf(m.coverage), m.discoveredCourses, m.completedCourses)
                }
                MergePartialSnapshotUseCase()(previous, snapshot)
            } else snapshot
            dao.deleteItems(snapshot.accountId)
            dao.putItems(value.items.map { h ->
                HomeworkEntity(snapshot.accountId, h.id, h.course.id, h.course.name, h.title,
                    h.detailUrl, h.publishedAt?.toEpochMilli(), h.dueAt?.toEpochMilli(),
                    h.submittedAt?.toEpochMilli(), h.status.name, h.score, h.maxScore, h.canSubmit, h.rawStatus, h.dueText)
            })
            dao.putMetadata(SyncMetadataEntity(value.accountId, value.revision,
                value.fetchedAt.toEpochMilli(), value.coverage.name,
                value.discoveredCourses, value.completedCourses))
        }
    }
    override suspend fun updateStatuses(accountId: String, updates: List<Homework>): Int = updateRows(accountId) { ApplyHomeworkStatusesUseCase()(it, updates) }
    override suspend fun updateDetails(accountId: String, updates: List<Homework>): Int = updateRows(accountId) { ApplyHomeworkDetailsUseCase()(it, updates) }
    private suspend fun updateRows(accountId: String, transform: (HomeworkSnapshot) -> HomeworkSnapshot): Int = db.withTransaction {
        val dao = db.homeworkDao()
        val record = dao.read(accountId) ?: return@withTransaction 0
        val m = record.metadata
        val previous = HomeworkSnapshot(m.accountId, m.revision, record.items.map { e ->
            Homework(e.id, Course(e.courseId, e.courseName), e.title, e.detailUrl,
                e.publishedAt?.let(Instant::ofEpochMilli), e.dueAt?.let(Instant::ofEpochMilli), e.submittedAt?.let(Instant::ofEpochMilli),
                runCatching { HomeworkStatus.valueOf(e.status) }.getOrDefault(HomeworkStatus.UNKNOWN),
                e.score, e.maxScore, e.canSubmit, e.rawStatus, e.dueText)
        }, Instant.ofEpochMilli(m.fetchedAt), SnapshotCoverage.valueOf(m.coverage), m.discoveredCourses, m.completedCourses)
        val value = transform(previous)
        if (value == previous) return@withTransaction 0
        val changed = value.items.filter { fresh -> previous.items.first { it.id == fresh.id } != fresh }
        dao.putItems(changed.map { h -> HomeworkEntity(accountId, h.id, h.course.id, h.course.name, h.title, h.detailUrl,
            h.publishedAt?.toEpochMilli(), h.dueAt?.toEpochMilli(), h.submittedAt?.toEpochMilli(), h.status.name,
            h.score, h.maxScore, h.canSubmit, h.rawStatus, h.dueText) })
        dao.putMetadata(m.copy(revision = value.revision))
        changed.size
    }
    override suspend fun clear() = db.withTransaction {
        db.homeworkDao().deleteAllItems()
        db.homeworkDao().deleteAllMetadata()
        db.courseWorkCacheDao().clear()
    }
}
