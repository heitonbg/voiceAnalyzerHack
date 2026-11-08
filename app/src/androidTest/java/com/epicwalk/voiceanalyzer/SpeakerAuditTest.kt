package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.diarization.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64

/** Metadata-only diagnosis on Android; no private text or audio leaves the device. */
@RunWith(AndroidJUnit4::class)
class SpeakerAuditTest {
    @Test fun inspectExistingMeetingEmbeddingsWithoutExportingSpeech() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ScribeApplication
        app.startup.await()
        val reports = JSONArray()
        for (conversation in app.database.dao().observeConversations().first().filter { it.durationMs in 600000..1800000 }) {
            val file = File(app.repository.audioDirectory, "${conversation.id}.diarization.json")
            if (!file.isFile) continue
            val array = JSONObject(file.readText()).getJSONArray("turns")
            val turns = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val embedding = if (row.has("vector")) {
                    val buffer = ByteBuffer.wrap(Base64.decode(row.getString("vector"), Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(buffer.remaining() / 4) { buffer.float }
                } else null
                VoiceTurn(
                    row.getLong("start"), row.getLong("end"), embedding, row.getBoolean("overlap"),
                    localSpeakerKey = row.takeIf { it.has("localKey") }?.getLong("localKey")
                )
            }
            val oldRows = app.database.dao().utterances(conversation.id).filter { it.sourcePass == "diarized" }
            fun count(rows: List<VoiceTurn>) = rows.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size
            reports.put(JSONObject().put("duration_ms", conversation.durationMs)
                .put("stored_speakers", oldRows.map { it.speakerId }.filter { it != "speaker_unknown" }.distinct().size)
                .put("revised_auto_speakers", count(GlobalSpeakers.assign(turns)))
                .put("segments", turns.size)
                .put("thresholds", JSONObject().also { sweep -> listOf(0.35, 0.4, 0.45, 0.5, 0.55, 0.6).forEach { threshold ->
                    sweep.put(threshold.toString(), count(GlobalSpeakers.assign(turns, similarity = threshold)))
                } }))
        }
        File(app.getExternalFilesDir(null), "speaker-audit.json").writeText(reports.toString(2))
    }
}
