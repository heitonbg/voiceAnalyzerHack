package com.epicwalk.voiceanalyzer

import android.Manifest
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.audio.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AudioSourcesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ScribeApplication

    @Test fun actualMicrophonePauseResumeAndWavePersistence() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            app.startup.await()
            check(RecordingService.state.value.id == null) { "A recording is already active" }
            var id: String? = null
            fun action(value: String) {
                app.startService(Intent(app, RecordingService::class.java).setAction(value))
            }
            suspend fun until(predicate: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (!predicate()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Recorder state timeout: ${RecordingService.state.value}" }
                    delay(30)
                }
            }
            try {
                ContextCompat.startForegroundService(app,
                    Intent(app, RecordingService::class.java).setAction(RecordingService.START))
                until { RecordingService.state.value.durationMs >= 1000 }
                id = RecordingService.state.value.id
                assertNotNull(id)
                action(RecordingService.PAUSE)
                until { RecordingService.state.value.paused }
                delay(250)
                val pausedAt = RecordingService.state.value.durationMs
                delay(500)
                assertEquals("Pause must not add saved samples", pausedAt, RecordingService.state.value.durationMs)
                action(RecordingService.RESUME)
                until { RecordingService.state.value.durationMs >= pausedAt + 1000 }
                action(RecordingService.STOP)
                until { RecordingService.state.value.id == null }
                val conversation = app.database.dao().conversation(id!!)!!
                assertEquals("READY", conversation.status)
                val file = File(conversation.audioPath)
                assertTrue(file.length() > 44)
                assertEquals((file.length() - 44) * 1000 / 32000, conversation.durationMs)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } finally { extractor.release() }
            } finally {
                if (id != null && RecordingService.state.value.id == id) {
                    action(RecordingService.STOP)
                    until { RecordingService.state.value.id == null }
                }
                id?.let { app.repository.remove(it) }
            }
        }
    }

    @Test fun importPreservesRealWaveAndReadsAndroidMetadata() = runBlocking {
        app.startup.await()
        val fixture = File(app.cacheDir, "real-import-test.wav")
        instrumentation.context.assets.open("example.wav").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        var id: String? = null
        try {
            id = app.repository.importAudio(Uri.fromFile(fixture))
            val conversation = app.database.dao().conversation(id)!!
            assertEquals("READY", conversation.status)
            assertTrue(conversation.durationMs in 11280L..11300L)
            fun digest(file: File): ByteArray {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest()
            }
            assertArrayEquals(digest(fixture), digest(File(conversation.audioPath)))
        } finally {
            id?.let { app.repository.remove(it) }
            fixture.delete()
        }
    }
}
