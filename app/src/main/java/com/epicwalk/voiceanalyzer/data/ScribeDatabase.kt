package com.epicwalk.voiceanalyzer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val createdMs: Long = System.currentTimeMillis(),
    val audioPath: String = "",
    val durationMs: Long = 0,
    val status: String = "IMPORTING",
    val stage: String = "",
    val processedMs: Long = 0,
    val error: String? = null
)

@Entity(tableName = "utterances", indices = [Index("conversationId")],
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class Utterance(
    @PrimaryKey val id: String,
    val conversationId: String,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val editedText: String? = null,
    val editedSpeakerId: String? = null,
    val provisional: Boolean = true
)

@Dao
interface ScribeDao {
    @Query("SELECT * FROM conversations ORDER BY createdMs DESC")
    fun observeConversations(): Flow<List<Conversation>>
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): Conversation?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation)
    @Update suspend fun update(conversation: Conversation)
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT * FROM utterances WHERE conversationId = :id ORDER BY startMs, endMs")
    fun observeUtterances(id: String): Flow<List<Utterance>>
    @Query("SELECT * FROM utterances WHERE conversationId = :id ORDER BY startMs, endMs")
    suspend fun utterances(id: String): List<Utterance>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtterance(utterance: Utterance)
    @Update suspend fun updateUtterance(utterance: Utterance)
    @Query("UPDATE conversations SET status = :status, stage = :stage, error = :error WHERE id = :id")
    suspend fun processingState(id: String, status: String, stage: String, error: String?)
    @Query("UPDATE conversations SET processedMs = :processedMs, durationMs = :durationMs WHERE id = :id")
    suspend fun processingProgress(id: String, processedMs: Long, durationMs: Long)
    @Query("SELECT * FROM conversations WHERE status = 'RECORDING'")
    suspend fun interruptedRecordings(): List<Conversation>
    @Query("UPDATE conversations SET status = 'INTERRUPTED', error = 'Операция прервана. Аудио сохранено локально.' WHERE status IN ('IMPORTING','RECORDING','PROCESSING')")
    suspend fun markInterrupted()
}

@Database(entities = [Conversation::class, Utterance::class], version = 1, exportSchema = false)
abstract class ScribeDatabase : RoomDatabase() {
    abstract fun dao(): ScribeDao
}
