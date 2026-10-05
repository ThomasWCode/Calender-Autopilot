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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Lists of short strings (labels, addresses) as one column: one entry per line. */
object ListCodec {
    fun encode(items: Collection<String>): String = items.joinToString("\n") { it.replace('\n', ' ').trim() }

    fun decode(text: String?): List<String> = text?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** Labels read in Outlook, per event version (PLAN-ROOM-BOOKING.md §3.3). */
@Entity(tableName = "label_cache")
data class LabelCacheEntity(
    /** [com.thomaswcode.calendareventtimers.calendar.CalEvent.labelKey] */
    @PrimaryKey val labelKey: String,
    val changeKey: String,
    /** [ListCodec] */
    val categories: String,
    val title: String,
    val readAt: Long,
)

enum class BookingState { SAVED, DELETED }

/** The room's reply to a booking, from the calendar provider. */
enum class RoomReply { WAITING, RESERVED, TENTATIVE, DECLINED, NOT_FOUND }

/** A room-booking event the app saved in Outlook. Dates and times are ISO strings, like the alarms'. */
@Entity(tableName = "bookings", indices = [Index("occurrenceKey"), Index("eventDate")])
data class BookingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The original event's occurrence. */
    val occurrenceKey: String,
    val seriesKey: String,
    /** The original's provider row, for its invitees. */
    val originalEventId: Long,
    val originalTitle: String,
    /** yyyy-MM-dd */
    val eventDate: String,
    /** HH:mm */
    val start: String,
    val end: String,
    val bookingTitle: String,
    val room: String?,
    /** Addresses told about the booking ([ListCodec]). */
    val notified: String,
    val state: BookingState,
    val roomReply: RoomReply,
    /** The booking event's own `_sync_id`, once found in the provider. */
    val bookingSyncId: String?,
    val createdAt: Long,
    val checkedAt: Long?,
)

enum class AnswerKind {
    /** A room was booked. */
    BOOKED,

    /** The user said no room. */
    NO_ROOM_WANTED,

    /** None of the rooms was free; asked again next time. */
    NO_ROOM_FREE,

    /** The booking failed; asked again next time. */
    FAILED,
}

/** What an occurrence got, so later runs only ask about new events. */
@Entity(tableName = "answers")
data class AnswerEntity(
    @PrimaryKey val occurrenceKey: String,
    val seriesKey: String,
    val eventDate: String,
    val answer: AnswerKind,
    val at: Long,
)

/** The last answers for a meeting that comes back (PLAN-ROOM-BOOKING.md §3.7). */
@Entity(tableName = "series_memory")
data class SeriesMemoryEntity(
    @PrimaryKey val seriesKey: String,
    val bookRoom: Boolean,
    val notify: Boolean,
    /** Addresses the user removed from the people to notify ([ListCodec]). */
    val removed: String,
    val updatedAt: Long,
)

/** Names for addresses, from the invitee lists the app has seen. */
@Entity(tableName = "people")
data class PersonEntity(
    /** Lower case. */
    @PrimaryKey val email: String,
    val name: String?,
    val lastSeen: Long,
)

@Dao
interface LabelCacheDao {
    @Query("SELECT * FROM label_cache WHERE labelKey IN (:keys)")
    suspend fun get(keys: List<String>): List<LabelCacheEntity>

    @Upsert
    suspend fun put(entries: List<LabelCacheEntity>)

    @Query("DELETE FROM label_cache WHERE labelKey IN (:keys)")
    suspend fun remove(keys: List<String>)

    @Query("DELETE FROM label_cache")
    suspend fun clear()

    @Query("DELETE FROM label_cache WHERE readAt < :before")
    suspend fun prune(before: Long): Int
}

@Dao
interface BookingDao {
    @Insert
    suspend fun insert(booking: BookingEntity): Long

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun get(id: Long): BookingEntity?

    @Query("SELECT * FROM bookings WHERE state = 'SAVED' AND eventDate >= :from ORDER BY eventDate, start, id")
    fun observeSavedFrom(from: String): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings WHERE state = 'SAVED' AND eventDate >= :from ORDER BY eventDate, start, id")
    suspend fun savedFrom(from: String): List<BookingEntity>

    @Query("SELECT * FROM bookings WHERE state = 'SAVED' AND eventDate BETWEEN :from AND :to ORDER BY eventDate, start, id")
    suspend fun savedBetween(from: String, to: String): List<BookingEntity>

    @Query("SELECT * FROM bookings WHERE state = 'SAVED' AND occurrenceKey IN (:keys) ORDER BY createdAt")
    suspend fun savedForOccurrences(keys: List<String>): List<BookingEntity>

    @Query("UPDATE bookings SET roomReply = :reply, bookingSyncId = COALESCE(:syncId, bookingSyncId), checkedAt = :at WHERE id = :id")
    suspend fun setReply(id: Long, reply: RoomReply, syncId: String?, at: Long)

    @Query("UPDATE bookings SET room = :room, roomReply = 'WAITING', checkedAt = NULL WHERE id = :id")
    suspend fun setRoom(id: Long, room: String)

    @Query("UPDATE bookings SET notified = :notified WHERE id = :id")
    suspend fun setNotified(id: Long, notified: String)

    @Query("UPDATE bookings SET state = 'DELETED' WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("DELETE FROM bookings WHERE eventDate < :before")
    suspend fun prune(before: String): Int
}

@Dao
interface AnswerDao {
    @Query("SELECT * FROM answers WHERE occurrenceKey IN (:keys)")
    suspend fun get(keys: List<String>): List<AnswerEntity>

    @Upsert
    suspend fun put(answers: List<AnswerEntity>)

    @Query("DELETE FROM answers")
    suspend fun clear()

    @Query("DELETE FROM answers WHERE eventDate < :before")
    suspend fun prune(before: String): Int
}

@Dao
interface SeriesMemoryDao {
    @Query("SELECT * FROM series_memory WHERE seriesKey IN (:keys)")
    suspend fun get(keys: List<String>): List<SeriesMemoryEntity>

    @Upsert
    suspend fun put(entries: List<SeriesMemoryEntity>)

    @Query("DELETE FROM series_memory")
    suspend fun clear()
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM people WHERE email IN (:emails)")
    suspend fun get(emails: List<String>): List<PersonEntity>

    @Upsert
    suspend fun put(people: List<PersonEntity>)
}

/**
 * Everything but the alarms: label cache, bookings and the answers the app remembers. Normal
 * (credential-protected) storage: nothing here is needed before the phone is first unlocked, unlike
 * the alarms, which stay in their own device-protected database.
 */
@Database(
    entities = [LabelCacheEntity::class, BookingEntity::class, AnswerEntity::class, SeriesMemoryEntity::class, PersonEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AutopilotDatabase : RoomDatabase() {
    abstract fun labels(): LabelCacheDao

    abstract fun bookings(): BookingDao

    abstract fun answers(): AnswerDao

    abstract fun seriesMemory(): SeriesMemoryDao

    abstract fun people(): PersonDao

    companion object {
        @Volatile
        private var instance: AutopilotDatabase? = null

        fun get(context: Context): AutopilotDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AutopilotDatabase::class.java, "autopilot.db")
                .build().also { instance = it }
        }
    }
}
