package com.epicwalk.voiceanalyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import com.epicwalk.voiceanalyzer.core.TranscriptExport
import com.epicwalk.voiceanalyzer.llm.LocalAnalysis
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledOfflineTest {
    @Test fun packagedModelsRealRussianPipelineExportAndQwen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await()
        assertTrue("Bundled weights must prepare without any network or SAF", app.modelPreparation.await())
        assertTrue(app.models.state.value.asrReady && app.models.state.value.diarizationReady && app.models.state.value.llmReady)
        val fixture = File(app.cacheDir, "bundled-real.wav")
        instrumentation.context.assets.open("example.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val id = app.repository.importAudio(Uri.fromFile(fixture))
        val started = android.os.SystemClock.elapsedRealtime()
        TranscriptionPipeline(app).process(id) { }
        val conversation = app.database.dao().conversation(id)!!
        assertEquals("COMPLETE", conversation.status)
        val rows = app.database.dao().utterances(id).filter { it.sourcePass == "diarized" }
        assertTrue(rows.isNotEmpty())
        val json = TranscriptExport.encode(rows, conversation.durationMs)
        assertEquals(setOf("transcript"), JSONObject(json).keys().asSequence().toSet())
        val array = JSONObject(json).getJSONArray("transcript")
        for (i in 0 until array.length()) assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"),
            array.getJSONObject(i).keys().asSequence().toSet())
        File(app.filesDir, "bundled-real-transcript.json").writeText(json)
        val report = LocalAnalysis(app).run(id) { _, _ -> }
        val pdf = File(app.cacheDir, "bundled-real-report.pdf")
        com.epicwalk.voiceanalyzer.core.ConversationReport.pdf(conversation, rows, pdf,
            sections = report.facts.map { it.kind to it.ids.joinToString(" ") { source ->
                rows.first { it.id == source }.rawText
            } }, name = { it })
        android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                assertTrue("PDF must have at least one readable page", renderer.pageCount > 0)
                renderer.openPage(0).use { page -> assertTrue(page.width > 0 && page.height > 0) }
            }
        }
        pdf.delete()
        val answer = LocalAnalysis(app).run(id, "Какое дерево упомянуто у лукоморья?") { _, _ -> }
        File(app.getExternalFilesDir(null), "bundled-diagnostic.json").writeText(JSONObject().put("transcript", JSONObject(json))
            .put("report", report.toString()).put("answer", answer.toString()).toString(2))
        assertTrue("Qwen should select real evidence", answer.facts.isNotEmpty())
        assertTrue(answer.facts.flatMap { it.ids }.all { source -> rows.any { it.id == source } })
        assertTrue("Actual source must mention the tree", answer.facts.flatMap { it.ids }.any { source ->
            rows.first { it.id == source }.rawText.contains("дуб", true)
        })
        val metrics = JSONObject().put("conversation_id", id).put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
            .put("duration_ms", conversation.durationMs).put("llm_analysis_ms", report.elapsedMs)
            .put("llm_question_ms", answer.elapsedMs).put("facts", report.facts.toString()).put("answer", answer.facts.toString())
        File(app.filesDir, "bundled-real-result.json").writeText(metrics.toString(2))
        val first = rows.first()
        app.database.dao().updateUtterance(first.copy(editedText = first.rawText + " (исправлено)", editedSpeakerId = "speaker_2"))
        val updated = app.database.dao().utterance(first.id)!!
        assertEquals(first.rawText, updated.rawText)
        val edited = JSONObject(TranscriptExport.encode(listOf(updated), conversation.durationMs)).getJSONArray("transcript").getJSONObject(0)
        assertEquals("speaker_2", edited.getString("speaker_id"))
        assertEquals(updated.editedText, edited.getString("text"))
        app.database.dao().updateUtterance(first)
        fixture.delete()
        Unit
    }
}
