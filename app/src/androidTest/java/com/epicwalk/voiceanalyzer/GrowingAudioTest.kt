package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.audio.AudioDecoder
import com.epicwalk.voiceanalyzer.core.ModelGate
import com.epicwalk.voiceanalyzer.data.Conversation
import com.epicwalk.voiceanalyzer.diarization.StreamingTranscription
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GrowingAudioTest {
    @Test fun realPhrasesAreCommittedWhileThePcmProducerIsStillRunning() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await(); assertTrue(app.modelPreparation.await())
        val id = UUID.randomUUID().toString()
        val source = File(app.cacheDir, "$id.wav")
        val decoded = File(app.cacheDir, "$id.raw")
        val full = File(app.cacheDir, "$id.full")
        val spool = File(app.cacheDir, "$id.growing")
        instrumentation.context.assets.open("example.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        AudioDecoder().decode(source, decoded, {}, {})
        full.outputStream().use { output -> repeat(6) { decoded.inputStream().use { it.copyTo(output, 8192) } } }
        val duration = full.length() * 1000 / 32000
        app.database.dao().insert(Conversation(id, "Real growing PCM test", audioPath = source.path, durationMs = duration, status = "READY"))
        var emittedBeforeEof = false
        val firstPhrase = CompletableDeferred<Unit>()
        try {
            withTimeout(120000) {
                ModelGate.mutex.withLock {
                    coroutineScope {
                        val initial = CompletableDeferred<Unit>()
                        val producer = async(Dispatchers.IO) {
                            RandomAccessFile(full, "r").use { input ->
                                RandomAccessFile(spool, "rw").use { output ->
                                    val bytes = ByteArray(8192)
                                    var copied = 0L
                                    while (copied < 31 * 32000L) {
                                        val n = minOf(bytes.size.toLong(), 31 * 32000L - copied).toInt()
                                        input.readFully(bytes, 0, n); output.write(bytes, 0, n); copied += n
                                    }
                                    initial.complete(Unit)
                                    // Deliberate I/O backpressure, with real inference unchanged.
                                    firstPhrase.await()
                                    while (true) {
                                        val count = input.read(bytes)
                                        if (count < 0) break
                                        output.write(bytes, 0, count)
                                    }
                                }
                            }
                        }
                        initial.await()
                        StreamingTranscription(app).process(id, spool, "real-growing-pcm-test", 1, { state ->
                            if (state.stage == "Распознавание речи" && !firstPhrase.isCompleted) {
                                val actual = runBlocking { app.database.dao().utterances(id) }.filter { it.sourcePass == "diarized" }
                                assertTrue(actual.isNotEmpty())
                                assertTrue(actual.any { Regex("[А-Яа-яЁё]").containsMatchIn(it.rawText) })
                                emittedBeforeEof = !producer.isCompleted
                                firstPhrase.complete(Unit)
                            }
                        }, awaitSamples = { wanted ->
                            while (spool.length() / 2 < wanted && !producer.isCompleted) delay(10)
                            if (producer.isCompleted) producer.await()
                            spool.length() / 2
                        }, declaredDurationMs = duration)
                        producer.await()
                    }
                }
            }
            assertTrue("A real phrase must precede decoder EOF", emittedBeforeEof)
            assertEquals("COMPLETE", app.database.dao().conversation(id)!!.status)
            val rows = app.database.dao().utterances(id).filter { it.sourcePass == "diarized" }
            assertTrue(rows.all { it.endMs > it.startMs && it.endMs <= duration })
            assertEquals(setOf("speaker_1"), rows.map { it.speakerId }.toSet())
        } finally {
            app.database.dao().processingState(id, "CANCELLED", "", null)
            app.repository.remove(id)
            listOf(source, decoded, full, spool).forEach { it.delete() }
        }
    }
}
