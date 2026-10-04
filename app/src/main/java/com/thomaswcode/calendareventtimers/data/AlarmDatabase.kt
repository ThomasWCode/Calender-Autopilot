package com.thomaswcode.calendareventtimers.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class AlarmState {
    /** Registered with AlarmManager, waiting. */
    SCHEDULED,

    /** Rang and was snoozed; registered again for the snooze time. */
    SNOOZED,

    /** Ringing now. */
    RINGING,
    DISMISSED,

    /** Auto-silenced after ringing unanswered, or due while the phone was off. */
    MISSED,

    /** Cancelled from the Upcoming list. Does not count as "already set" when the day is scanned again. */
    CANCELLED,
}

/** One alarm the app has set (PLAN.md §4.1 AlarmStore). Dates and times are kept as ISO strings. */
@Entity(tableName = "alarms", indices = [Index("eventDate", "eventStart", "title")])
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** yyyy-MM-dd */
    val eventDate: String,
    /** HH:mm */
    val eventStart: String,
    val title: String,
    val label: String,
    val location: String?,
    /** 0 = at the start time, 5 = five minutes before. */
    val offsetMin: Int,
    /** Epoch millis; moves on when snoozed. */
    val triggerAt: Long,
    val state: AlarmState,
    val createdAt: Long,
)

@Dao
interface AlarmDao {
    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): AlarmEntity?

    /** Alarms still to ring (or ringing), soonest first: the Upcoming list. */
    @Query("SELECT * FROM alarms WHERE state IN ('SCHEDULED', 'SNOOZED', 'RINGING') ORDER BY triggerAt, id")
    fun observeActive(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE state IN ('SCHEDULED', 'SNOOZED', 'RINGING') ORDER BY triggerAt, id")
    suspend fun active(): List<AlarmEntity>

    /** Everything set for a day except cancelled alarms, for "Alarm already set". */
    @Query("SELECT * FROM alarms WHERE eventDate = :date AND state != 'CANCELLED'")
    suspend fun notCancelledOn(date: String): List<AlarmEntity>

    @Query("DELETE FROM alarms WHERE state IN ('DISMISSED', 'MISSED', 'CANCELLED') AND triggerAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int

    // State changes are conditional on the state they leave, so the alarm receiver, the ring
    // service, resync and the UI can't overwrite each other's changes. Each returns rows changed.

    @Query("UPDATE alarms SET state = 'RINGING' WHERE id = :id AND state IN ('SCHEDULED', 'SNOOZED')")
    suspend fun claimForRinging(id: Long): Int

    /** [state] is DISMISSED or MISSED. */
    @Query("UPDATE alarms SET state = :state WHERE id = :id AND state = 'RINGING'")
    suspend fun finishRinging(id: Long, state: String): Int

    @Query("UPDATE alarms SET state = 'SNOOZED', triggerAt = :triggerAt WHERE id = :id AND state = 'RINGING'")
    suspend fun snoozeRinging(id: Long, triggerAt: Long): Int

    @Query("UPDATE alarms SET state = 'CANCELLED' WHERE id = :id AND state IN ('SCHEDULED', 'SNOOZED', 'RINGING')")
    suspend fun cancel(id: Long): Int

    /** [state] is SCHEDULED or SNOOZED. */
    @Query("UPDATE alarms SET state = :state WHERE id = :id AND state = 'CANCELLED'")
    suspend fun uncancel(id: Long, state: String): Int

    @Query("UPDATE alarms SET state = 'SCHEDULED' WHERE id = :id AND state = 'RINGING'")
    suspend fun resetRinging(id: Long): Int

    @Query("UPDATE alarms SET state = 'MISSED' WHERE id = :id AND state IN ('SCHEDULED', 'SNOOZED', 'RINGING')")
    suspend fun markMissed(id: Long): Int
}

@Database(entities = [AlarmEntity::class], version = 1, exportSchema = false)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao

    companion object {
        @Volatile
        private var instance: AlarmDatabase? = null

        /**
         * In device-protected storage, so the reboot receiver can re-register alarms (and they can
         * ring) before the phone is first unlocked.
         */
        fun get(context: Context): AlarmDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext.createDeviceProtectedStorageContext(),
                AlarmDatabase::class.java,
                "alarms.db",
            ).build().also { instance = it }
        }
    }
}
