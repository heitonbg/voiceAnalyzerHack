package com.epicwalk.voiceanalyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.audio.Wav16
import com.epicwalk.voiceanalyzer.core.TranscriptionPipeline
import com.epicwalk.voiceanalyzer.core.TranscriptExport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Stress fixture repeats actual public speech, not an independent conversation quality benchmark. */
@RunWith(AndroidJUnit4::class)
class LongAudioTest {
    @Test fun longRepeatedSpeechCompletesInBoundedBlocks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await()
        assertTrue(app.modelPreparation.await())
        val source = File(app.cacheDir, "long-source.wav")
        instrumentation.context.assets.open("example.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val decoded = File(app.cacheDir, "long-source.pcm")
        com.epicwalk.voiceanalyzer.audio.AudioDecoder().decode(source, decoded, {}, {})
        val target = File(app.cacheDir, "long-repeated.wav")
        val repetitions = InstrumentationRegistry.getArguments().getString("longRepetitions")?.toInt() ?: 80
        require(repetitions in 80..1000)
        var samples = 0L
        RandomAccessFile(target, "rw").use { out ->
            Wav16.header(out, 0)
            val buffer = ByteArray(8192)
            repeat(repetitions) {
                RandomAccessFile(decoded, "r").use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count); samples += count / 2
                    }
                }
            }
            Wav16.header(out, samples)
        }
        val id = app.repository.importAudio(Uri.fromFile(target))
        val start = android.os.SystemClock.elapsedRealtime()
        var firstResultMs = -1L
        var firstResultAudioMs = -1L
        val cpuStart = android.os.Process.getElapsedCpuTime()
        TranscriptionPipeline(app).process(id) { state ->
            if (firstResultMs < 0 && state.stage == "Распознавание речи") {
                val partial = runBlocking { app.database.dao().utterances(id) }.filter { it.sourcePass == "diarized" }
                if (partial.isNotEmpty()) {
                    firstResultMs = android.os.SystemClock.elapsedRealtime() - start
                    firstResultAudioMs = state.processedMs
                    assertTrue(partial.any { it.provisional })
                    assertTrue(partial.any { it.speakerId != "speaker_unknown" })
                }
            }
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        val conversation = app.database.dao().conversation(id)!!
        assertTrue(conversation.durationMs >= 900000)
        assertEquals("COMPLETE", conversation.status)
        val rows = app.database.dao().utterances(id).filter { it.sourcePass == "diarized" }
        assertTrue(rows.size > 50)
        assertTrue(rows.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= conversation.durationMs })
        assertTrue(rows.last().endMs > 890000)
        val report = JSONObject().put("fixture", "$repetitions repetitions of real public GigaAM example.wav; stress only")
            .put("duration_ms", conversation.durationMs).put("elapsed_ms", elapsed)
            .put("rtf", elapsed.toDouble() / conversation.durationMs).put("utterances", rows.size)
            .put("speakers", org.json.JSONArray(rows.map { it.speakerId }.distinct())).put("abi", android.os.Build.SUPPORTED_ABIS[0])
            .put("first_result_wall_ms", firstResultMs).put("first_result_audio_ms", firstResultAudioMs)
            .put("cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStart)
            .put("process_peak_rss_kib", com.epicwalk.voiceanalyzer.llm.NativeLlm.peakRssKiB())
        assertTrue(firstResultMs > 0 && firstResultAudioMs < conversation.durationMs)
        val pdf = File(app.getExternalFilesDir(null), "long-report.pdf")
        com.epicwalk.voiceanalyzer.core.ConversationReport.pdf(conversation, rows, pdf) { it }
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {
            assertTrue(it.pageCount > 1)
            report.put("pdf_pages", it.pageCount)
        }
        File(app.getExternalFilesDir(null), "long-audio-result.json").writeText(report.toString(2))
        File(app.getExternalFilesDir(null), "long-audio-transcript.json").writeText(TranscriptExport.encode(rows, conversation.durationMs))
        source.delete(); decoded.delete(); target.delete()
        Unit
    }
}
