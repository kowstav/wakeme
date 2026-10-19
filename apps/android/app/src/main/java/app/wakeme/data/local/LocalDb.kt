package app.wakeme.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Grants cached on this phone. Those I gave are used to re-validate incoming commands offline. */
@Entity(tableName = "grants")
data class GrantEntity(
    @PrimaryKey val id: String,
    val grantorId: String,
    val granteeId: String,
    val version: Int,
    val revoked: Boolean,
    val json: String,
)

/** Commands already handled here; duplicate pushes become no-ops. */
@Entity(tableName = "processed_commands")
data class ProcessedCommandEntity(@PrimaryKey val id: String, val processedAt: Long, val decision: String)

/** The accepted routine version installed on this phone (everything needed to ring offline). */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val planId: String,
    val version: Int,
    val json: String,
    val creatorName: String,
    val ownerIsCreator: Boolean,
    val installedAt: Long,
)

/** Verified, signed challenge revisions available offline. */
@Entity(tableName = "challenge_revisions")
data class RevisionEntity(@PrimaryKey val revisionId: String, val definitionJson: String, val verifiedAt: Long)

/** Alarms registered with AlarmManager (so cancel/reschedule and readiness are exact). */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val requestCode: Int,
    val planId: String?,
    val commandId: String?,
    val version: Int,
    val occurrenceKey: String,
    val role: String,
    val fireAt: Long,
    val soundId: String,
    val ringSeconds: Int,
    val label: String,
    val helperName: String?,
    val challengeRevisionId: String?,
)

@Dao
interface GrantDao {
    @Upsert suspend fun upsert(grant: GrantEntity)
    @Query("SELECT * FROM grants WHERE grantorId = :grantorId AND granteeId = :granteeId AND revoked = 0 ORDER BY version DESC LIMIT 1")
    suspend fun live(grantorId: String, granteeId: String): GrantEntity?
    @Query("SELECT * FROM grants WHERE revoked = 0") fun observeLive(): Flow<List<GrantEntity>>
    @Query("UPDATE grants SET revoked = 1 WHERE grantorId = :grantorId AND granteeId = :granteeId") suspend fun revokeLocally(grantorId: String, granteeId: String)
    @Query("DELETE FROM grants") suspend fun clear()
}

@Dao
interface ProcessedCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: ProcessedCommandEntity): Long
    @Query("SELECT EXISTS(SELECT 1 FROM processed_commands WHERE id = :id)") suspend fun seen(id: String): Boolean
    @Query("DELETE FROM processed_commands WHERE processedAt < :before") suspend fun prune(before: Long)
}

@Dao
interface RoutineDao {
    @Upsert suspend fun upsert(r: RoutineEntity)
    @Query("SELECT * FROM routines") suspend fun all(): List<RoutineEntity>
    @Query("SELECT * FROM routines") fun observe(): Flow<List<RoutineEntity>>
    @Query("SELECT * FROM routines WHERE planId = :planId") suspend fun get(planId: String): RoutineEntity?
    @Query("DELETE FROM routines WHERE planId = :planId") suspend fun delete(planId: String)
    @Query("DELETE FROM routines") suspend fun clear()
}

@Dao
interface RevisionDao {
    @Upsert suspend fun upsert(r: RevisionEntity)
    @Query("SELECT * FROM challenge_revisions WHERE revisionId = :id") suspend fun get(id: String): RevisionEntity?
}

@Dao
interface ScheduledAlarmDao {
    @Upsert suspend fun upsert(a: ScheduledAlarmEntity)
    @Query("SELECT * FROM scheduled_alarms ORDER BY fireAt") suspend fun all(): List<ScheduledAlarmEntity>
    @Query("SELECT * FROM scheduled_alarms ORDER BY fireAt") fun observe(): Flow<List<ScheduledAlarmEntity>>
    @Query("SELECT * FROM scheduled_alarms WHERE planId = :planId") suspend fun forPlan(planId: String): List<ScheduledAlarmEntity>
    @Query("SELECT * FROM scheduled_alarms WHERE requestCode = :code") suspend fun get(code: Int): ScheduledAlarmEntity?
    @Query("DELETE FROM scheduled_alarms WHERE requestCode = :code") suspend fun delete(code: Int)
    @Query("DELETE FROM scheduled_alarms") suspend fun clear()
}

@Database(
    entities = [GrantEntity::class, ProcessedCommandEntity::class, RoutineEntity::class, RevisionEntity::class, ScheduledAlarmEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LocalDb : RoomDatabase() {
    abstract fun grants(): GrantDao
    abstract fun processed(): ProcessedCommandDao
    abstract fun routines(): RoutineDao
    abstract fun revisions(): RevisionDao
    abstract fun alarms(): ScheduledAlarmDao
}
