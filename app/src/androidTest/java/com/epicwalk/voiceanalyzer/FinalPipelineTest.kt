package com.epicwalk.voiceanalyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FinalPipelineTest {
    @Test fun russianFinalTranscriptPreservesOriginalAndHasStableSpeakers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await()
        app.models.install(setOf("ASR", "DIAR")) { instrumentation.context.assets.open(it) }
        val fixture = File(app.cacheDir, "real-final-input.wav")
        instrumentation.context.assets.open("example.wav").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        var id: String? = null
        try {
            id = app.repository.importAudio(Uri.fromFile(fixture))
            val pipeline = TranscriptionPipeline(app)
            pipeline.process(id) { }
            val conversation = app.database.dao().conversation(id)!!
            val all = app.database.dao().utterances(id)
            val original = all.filter { it.sourcePass == "asr" }
            val final = all.filter { it.sourcePass == "diarized" }
            assertEquals("COMPLETE", conversation.status)
            assertTrue(original.isNotEmpty())
            assertTrue(final.isNotEmpty())
            assertEquals(setOf("speaker_1"), final.map { it.speakerId }.toSet())
            assertTrue(final.all { !it.provisional && it.endMs > it.startMs && it.endMs <= conversation.durationMs })
            pipeline.process(id) { }
            val resumed = app.database.dao().utterances(id)
            assertEquals(all.size, resumed.size)
            assertEquals(original.map { it.rawText }, resumed.filter { it.sourcePass == "asr" }.map { it.rawText })
            assertEquals(final.map { it.speakerId }, resumed.filter { it.sourcePass == "diarized" }.map { it.speakerId })
        } finally {
            id?.let { app.repository.remove(it) }
            fixture.delete()
        }
    }
}
