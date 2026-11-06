package com.epicwalk.voiceanalyzer

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Iteration 1 research harness. No mocked engines, expected transcript or synthetic speech.
 * Requires publicly sourced model files in externalFilesDir/models. Never downloads on Android.
 * These short-input tests do not establish 15-minute stability or recognition accuracy.
 */
@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models get() = File(context.filesDir, "model-smoke")
    private fun model(name: String): String = File(models, name).also { target ->
        if (!target.isFile || target.length() == 0L) {
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(target.length() > 0) { "Empty real model/input: ${target.absolutePath}" }
    }.absolutePath

    private fun report(name: String, body: JSONObject) {
        body.put("abi", Build.SUPPORTED_ABIS[0])
        body.put("device", Build.MODEL)
        body.put("android_api", Build.VERSION.SDK_INT)
        body.put("pss_kib_at_report", Debug.getPss())
        body.put("memory_note", "PSS snapshot, not peak RAM")
        File(context.getExternalFilesDir(null), name).writeText(body.toString(2))
    }

    @Test fun sileroDetectsRealSpeech() {
        val samples = wav(model("example.wav"))
        val begin = SystemClock.elapsedRealtime()
        val vad = Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model("silero_vad.onnx"), maxSpeechDuration = 20f
            ), numThreads = 1, provider = "cpu"
        ))
        val loadMs = SystemClock.elapsedRealtime() - begin
        val regions = JSONArray()
        val runStart = SystemClock.elapsedRealtime()
        try {
            var offset = 0
            while (offset < samples.size) {
                val frame = FloatArray(512)
                samples.copyInto(frame, 0, offset, minOf(samples.size, offset + 512))
                vad.acceptWaveform(frame)
                offset += 512
                while (!vad.empty()) {
                    val s = vad.front()
                    regions.put(JSONObject().put("start_sample", s.start).put("sample_count", s.samples.size))
                    vad.pop()
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val s = vad.front()
                regions.put(JSONObject().put("start_sample", s.start).put("sample_count", s.samples.size))
                vad.pop()
            }
            assertTrue("No speech detected in real input", regions.length() > 0)
            report("vad-smoke.json", JSONObject().put("load_ms", loadMs)
                .put("inference_ms", SystemClock.elapsedRealtime() - runStart).put("regions", regions))
        } finally { vad.release() }
    }

    @Test fun russianRnntAndDiarizationExportRealInference() {
        val samples = wav(model("example.wav"))
        val diarStart = SystemClock.elapsedRealtime()
        val diar = diarizer()
        val diarLoad = SystemClock.elapsedRealtime() - diarStart
        val turns: Array<OfflineSpeakerDiarizationSegment>
        val diarRun = SystemClock.elapsedRealtime()
        try { turns = diar.process(samples) } finally { diar.release() }
        val diarMs = SystemClock.elapsedRealtime() - diarRun
        assertTrue("No diarization segments", turns.isNotEmpty())

        val asrStart = SystemClock.elapsedRealtime()
        val asr = OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = model("encoder.int8.onnx"),
                    decoder = model("decoder.onnx"), joiner = model("joiner.onnx")
                ), tokens = model("tokens.txt"), modelType = "nemo_transducer",
                provider = "cpu", numThreads = 2
            )
        ))
        val asrLoad = SystemClock.elapsedRealtime() - asrStart
        val output = JSONArray()
        val asrRun = SystemClock.elapsedRealtime()
        try {
            // Stable IDs within this single whole-file diarization run.
            val ids = turns.sortedBy { it.start }.map { it.speaker }.distinct()
            for (turn in turns.sortedBy { it.start }) {
                val start = (turn.start * 16000).toInt().coerceIn(0, samples.size)
                val end = (turn.end * 16000).toInt().coerceIn(start, samples.size)
                if (end <= start) continue
                val stream = asr.createStream()
                try {
                    stream.acceptWaveform(samples.copyOfRange(start, end), 16000)
                    asr.decode(stream)
                    val text = asr.getResult(stream).text.trim()
                    if (text.isNotEmpty()) {
                        output.put(JSONObject().put("speaker_id", "speaker_${ids.indexOf(turn.speaker) + 1}")
                            .put("start_ms", start * 1000L / 16000)
                            .put("end_ms", end * 1000L / 16000).put("text", text))
                    }
                } finally { stream.release() }
            }
            assertTrue("Real inference produced no utterances", output.length() > 0)
            assertTrue("Expected Cyrillic from Russian test audio",
                (0 until output.length()).any { Regex("[А-Яа-яЁё]").containsMatchIn(output.getJSONObject(it).getString("text")) })
            // Organizer export contains only the required root and utterance fields.
            File(context.getExternalFilesDir(null), "real-transcript.json")
                .writeText(JSONObject().put("transcript", output).toString(2))
            report("asr-diarization-smoke.json", JSONObject()
                .put("audio_seconds", samples.size / 16000.0)
                .put("diar_load_ms", diarLoad).put("diar_inference_ms", diarMs)
                .put("asr_load_ms", asrLoad)
                .put("asr_inference_ms", SystemClock.elapsedRealtime() - asrRun)
                .put("utterances", output.length()).put("speakers", ids.size)
                .put("timestamps", "Estimated diarization boundaries; not word alignment"))
        } finally { asr.release() }
    }

    @Test fun multipleSpeakersOnRealPublicAudio() {
        val samples = wav(model("four-speakers.wav"))
        val start = SystemClock.elapsedRealtime()
        val diar = diarizer()
        try {
            val turns = diar.process(samples)
            val ids = turns.map { it.speaker }.distinct()
            assertTrue("Automatic clustering did not distinguish multiple voices", ids.size >= 2)
            assertTrue(turns.all { it.start >= 0 && it.end > it.start && it.end <= samples.size / 16000f + 0.1f })
            report("multispeaker-smoke.json", JSONObject()
                .put("elapsed_including_load_ms", SystemClock.elapsedRealtime() - start)
                .put("audio_seconds", samples.size / 16000.0).put("detected_speakers", ids.size)
                .put("accuracy_note", "No reference RTTM; DER not measured"))
        } finally { diar.release() }
    }

    @Test fun diarizationCalibrationOnPublicFixture() {
        val samples = wav(model("four-speakers.wav"))
        val results = JSONArray()
        for (threshold in listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)) {
            val engine = diarizer(threshold = threshold)
            try {
                val turns = engine.process(samples)
                results.put(JSONObject().put("threshold", threshold.toDouble())
                    .put("speakers", turns.map { it.speaker }.distinct().size))
            } finally { engine.release() }
        }
        val known = diarizer(clusters = 4)
        val knownCount = try { known.process(samples).map { it.speaker }.distinct().size }
            finally { known.release() }
        report("diarization-calibration.json", JSONObject().put("automatic", results)
            .put("manual_requested", 4).put("manual_detected", knownCount)
            .put("note", "Calibration on one public Chinese fixture, not independent validation or DER"))
        assertEquals("Manual cluster count must be honored", 4, knownCount)
    }

    private fun diarizer(clusters: Int = -1, threshold: Float = 0.5f) =
        OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model("sherpa-onnx-pyannote-segmentation-3-0/model.onnx")),
                numThreads = 1, provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = model("titanet.onnx"), numThreads = 1, provider = "cpu"),
            clustering = FastClusteringConfig(numClusters = clusters, threshold = threshold)
        ))

    /** Minimal strict reader ONLY for public PCM16 mono/16k smoke fixtures, never the import path. */
    private fun wav(path: String): FloatArray = RandomAccessFile(path, "r").use { f ->
        fun fourcc(): String = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
        fun u32(): Long = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
        fun u16(): Int = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        check(fourcc() == "RIFF"); u32(); check(fourcc() == "WAVE")
        var validFormat = false
        while (f.filePointer + 8 <= f.length()) {
            val id = fourcc()
            val length = u32()
            val end = f.filePointer + length
            check(end <= f.length()) { "Truncated WAV" }
            if (id == "fmt ") {
                check(length >= 16)
                val format = u16(); val channels = u16(); val rate = u32()
                u32(); u16(); val bits = u16()
                check(format == 1 && channels == 1 && rate == 16000L && bits == 16) {
                    "Smoke fixture must be PCM16 mono 16 kHz"
                }
                validFormat = true
            } else if (id == "data") {
                check(validFormat && length % 2L == 0L)
                check(length <= 16000L * 2 * 60) { "Smoke harness limited to 60 seconds; production must stream" }
                return@use FloatArray((length / 2).toInt()) {
                    java.lang.Short.reverseBytes(f.readShort()).toFloat() / 32768f
                }
            }
            f.seek(end + length % 2)
        }
        error("Missing WAV data")
    }
}
