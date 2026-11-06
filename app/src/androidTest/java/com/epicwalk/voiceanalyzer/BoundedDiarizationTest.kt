package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.audio.AudioDecoder
import com.epicwalk.voiceanalyzer.diarization.BoundedDiarization
import com.epicwalk.voiceanalyzer.diarization.GlobalSpeakers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BoundedDiarizationTest {
    @Test fun realVoicesRemainGlobalAcrossBlocksAndCheckpoint() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await()
        app.models.install(setOf("DIAR")) { instrumentation.context.assets.open(it) }
        val source = File(app.cacheDir, "diar-four.wav")
        val pcm = File(app.cacheDir, "diar-four.pcm")
        val checkpoint = File(app.cacheDir, "diar-four.checkpoint")
        instrumentation.context.assets.open("four-speakers.wav").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        checkpoint.delete()
        try {
            AudioDecoder().decode(source, pcm, {}, {})
            BoundedDiarization(app.models).use { engine ->
                val turns = engine.analyze(pcm, checkpoint, "public-four-speakers-test", 4, {}, {})
                assertTrue(turns.isNotEmpty())
                assertTrue(turns.any { it.startSample > 30 * 16000 })
                val speakers = turns.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct()
                fun at(ms: Long): String = turns.first {
                    it.startSample <= ms * 16 && it.endSample > ms * 16
                }.speakerId
                val report = JSONObject().put("manual_speakers", speakers.size)
                    .put("speaker_at_16s", at(16000)).put("speaker_at_49s", at(49000))
                    .put("speaker_at_28s", at(28000)).put("speaker_at_35s", at(35000))
                    .put("automatic_speakers", GlobalSpeakers.assign(turns).map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                    .put("thresholds", JSONObject().also { sweep -> listOf(0.35, 0.4, 0.45, 0.5, 0.55, 0.6).forEach { threshold ->
                        sweep.put(threshold.toString(), GlobalSpeakers.assign(turns, similarity = threshold)
                            .map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                    } })
                    .put("note", "Public Chinese fixture; global consistency checks, not DER")
                File(app.getExternalFilesDir(null), "bounded-diarization.json").writeText(report.toString(2))
                assertEquals(4, speakers.size)
                assertNotEquals("speaker_unknown", at(16000))
                assertEquals("A returning voice must keep its global ID", at(16000), at(49000))
                assertEquals("Voice crossing the 30s boundary", at(28000), at(35000))
                val restored = engine.analyze(pcm, checkpoint, "public-four-speakers-test", 4, {}, {})
                assertEquals(turns.map { it.speakerId }, restored.map { it.speakerId })
            }
        } finally { source.delete(); pcm.delete(); checkpoint.delete() }
    }
}
