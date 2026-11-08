package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: reprocesses the existing meeting in-place, preserving archived/raw passes. */
@RunWith(AndroidJUnit4::class)
class MeetingRegressionTest {
    @Test fun reprocessReportedMeetingAndRecordOnlyTechnicalMetrics() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reprocessExisting") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScribeApplication
        app.startup.await(); assertTrue(app.modelPreparation.await())
        val conversation = app.database.dao().observeConversations().first()
            .firstOrNull { it.durationMs in 1200000..1400000 && it.status == "COMPLETE" }
        assumeTrue(conversation != null)
        val item = conversation!!
        val prefs = app.getSharedPreferences("processing", 0)
        val expected = prefs.getInt("expected-speakers", 0)
        prefs.edit().putInt("expected-speakers", 0).commit()
        val started = android.os.SystemClock.elapsedRealtime()
        var firstPhrase = -1L
        try {
            TranscriptionPipeline(app).process(item.id) { state ->
                if (state.stage == "Распознавание речи" && firstPhrase < 0)
                    firstPhrase = android.os.SystemClock.elapsedRealtime() - started
            }
            val rows = app.database.dao().utterances(item.id).filter { it.sourcePass == "diarized" }
            assertEquals("COMPLETE", app.database.dao().conversation(item.id)!!.status)
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.endMs > it.startMs && it.endMs <= item.durationMs + 1000 })
            val metrics = JSONObject().put("duration_ms", item.durationMs)
                .put("wall_ms", android.os.SystemClock.elapsedRealtime() - started)
                .put("first_phrase_ms", firstPhrase).put("asr_reused", firstPhrase < 0)
                .put("speakers", rows.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                .put("unknown_speaker_utterances", rows.count { it.speakerId == "speaker_unknown" })
                .put("utterances", rows.size).put("sub_500ms_utterances", rows.count { it.endMs - it.startMs < 500 })
                .put("note", "No reference speaker labels; speaker count is not DER. No private speech exported.")
            File(app.getExternalFilesDir(null), "meeting-regression.json").writeText(metrics.toString(2))
        } finally { prefs.edit().putInt("expected-speakers", expected).commit() }
    }
}
