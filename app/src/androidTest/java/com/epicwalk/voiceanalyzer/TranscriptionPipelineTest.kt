package com.epicwalk.voiceanalyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranscriptionPipelineTest {
    @Test fun realImportDecoderAsrCancellationAndResume() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await()
        app.models.install(setOf("ASR")) { instrumentation.context.assets.open(it) }
        assertTrue(app.models.state.value.asrReady)
        val source = File(app.cacheDir, "pipeline-real.wav")
        instrumentation.context.assets.open("example.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        var id: String? = null
        try {
            id = app.repository.importAudio(Uri.fromFile(source))
            val pipeline = TranscriptionPipeline(app)
            var cancelled = false
            try {
                pipeline.process(id) {
                    if (it.stage == "Распознавание речи" && it.processedMs >= 1000) throw CancellationException("Test cancellation")
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals("CANCELLED", app.database.dao().conversation(id)!!.status)
            pipeline.process(id) { }
            val result = app.database.dao().conversation(id)!!
            val utterances = app.database.dao().utterances(id)
            assertEquals("TRANSCRIBED", result.status)
            assertTrue(utterances.isNotEmpty())
            assertTrue(utterances.any { Regex("[А-Яа-яЁё]").containsMatchIn(it.rawText) })
            assertTrue(utterances.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= result.durationMs })
            assertTrue(utterances.all { it.provisional && it.speakerId == "speaker_unknown" })
            assertEquals(11290L, result.durationMs)
        } finally {
            id?.let {
                app.repository.remove(it)
                File(app.repository.audioDirectory, "$it.pcm").delete()
                File(app.repository.audioDirectory, "$it.pcm.sha256").delete()
            }
            source.delete()
        }
    }
}
