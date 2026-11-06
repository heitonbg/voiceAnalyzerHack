package com.epicwalk.voiceanalyzer

import android.app.Application
import androidx.room.Room
import com.epicwalk.voiceanalyzer.data.ConversationRepository
import com.epicwalk.voiceanalyzer.data.ScribeDatabase
import kotlinx.coroutines.*

class ScribeApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ScribeDatabase::class.java, "privatescribe.db")
        .addMigrations(ScribeDatabase.migration1To2).build() }
    val models by lazy { com.epicwalk.voiceanalyzer.core.ModelStore(this) }
    val repository by lazy { ConversationRepository(this, database.dao()) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val startup by lazy {
        applicationScope.async {
            for (item in database.dao().interruptedRecordings()) {
                val file = java.io.File(item.audioPath)
                if (file.parentFile == repository.audioDirectory && file.isFile) {
                    runCatching { com.epicwalk.voiceanalyzer.audio.Wav16.repair(file) }
                        .getOrNull()?.let { duration -> database.dao().update(item.copy(durationMs = duration)) }
                }
            }
            database.dao().markInterrupted()
        }
    }
    override fun onCreate() {
        super.onCreate()
        startup
    }
}
