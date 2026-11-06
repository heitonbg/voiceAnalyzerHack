package com.epicwalk.voiceanalyzer.diarization

import android.util.AtomicFile
import android.util.Base64
import com.epicwalk.voiceanalyzer.core.ModelStore
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface DiarizationEngine : AutoCloseable {
    suspend fun analyze(pcm: File, checkpoint: File, fingerprint: String, expectedSpeakers: Int,
        checkCancelled: () -> Unit, progress: (Long) -> Unit,
        awaitSamples: suspend (Long) -> Long = { pcm.length() / 2 },
        onWindow: suspend (List<VoiceTurn>, Long) -> Unit = { _, _ -> }): List<VoiceTurn>
}

class BoundedDiarization(private val models: ModelStore) : DiarizationEngine {
    private val segmentation = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                models.file("sherpa-onnx-pyannote-segmentation-3-0/model.onnx").absolutePath),
            numThreads = 1, provider = "cpu"),
        embedding = SpeakerEmbeddingExtractorConfig(model = models.file("titanet.onnx").absolutePath, numThreads = 1, provider = "cpu"),
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.8f)
    ))
    private var extractor: SpeakerEmbeddingExtractor? = null

    override suspend fun analyze(pcm: File, checkpoint: File, fingerprint: String, expectedSpeakers: Int,
        checkCancelled: () -> Unit, progress: (Long) -> Unit,
        awaitSamples: suspend (Long) -> Long,
        onWindow: suspend (List<VoiceTurn>, Long) -> Unit): List<VoiceTurn> {
        var sampleCount = awaitSamples(31L * 16000)
        val config = "$fingerprint:pyannote-titanet-block30-margin1-v1"
        val turns = ArrayList<VoiceTurn>()
        var processed = 0L
        if (checkpoint.isFile && checkpoint.length() < 64L * 1024 * 1024) {
            runCatching {
                val root = JSONObject(checkpoint.readText())
                if (root.getString("fingerprint") == config) {
                    val saved = root.getJSONArray("turns")
                    val restored = ArrayList<VoiceTurn>()
                    for (index in 0 until saved.length()) {
                        val item = saved.getJSONObject(index)
                        val bytes = if (item.has("vector")) Base64.decode(item.getString("vector"), Base64.NO_WRAP) else null
                        val vector = bytes?.let {
                            check(it.size == 192 * 4)
                            val buffer = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                            FloatArray(192) { buffer.float }
                        }
                        restored.add(VoiceTurn(item.getLong("start"), item.getLong("end"), vector, item.getBoolean("overlap")))
                    }
                    val savedAt = root.getLong("processed")
                    check(savedAt in 0..sampleCount && restored.all { it.startSample >= 0 && it.endSample <= savedAt && it.endSample > it.startSample })
                    turns.addAll(restored)
                    processed = savedAt
                }
            }.onFailure { turns.clear(); processed = 0 }
        }
        if (processed > 0) onWindow(GlobalSpeakers.assign(turns, expectedSpeakers), processed)
        if (processed < sampleCount && extractor == null) {
            extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                model = models.file("titanet.onnx").absolutePath, numThreads = 1, provider = "cpu"))
        }
        RandomAccessFile(pcm, "r").use { input ->
            while (true) {
                sampleCount = awaitSamples(processed + 31L * 16000)
                if (processed >= sampleCount) break
                checkCancelled()
                val end = minOf(sampleCount, processed + 30 * 16000)
                val loadStart = (processed - 16000).coerceAtLeast(0)
                val loadEnd = minOf(sampleCount, end + 16000)
                val samples = read(input, loadStart, (loadEnd - loadStart).toInt())
                val detected = segmentation.process(samples)
                checkCancelled()
                data class Span(val start: Long, val end: Long, val localSpeaker: Int)
                val events = java.util.TreeMap<Long, MutableList<Pair<Int, Int>>>()
                for (segment in detected) {
                    val start = (loadStart + (segment.start * 16000).toLong()).coerceAtLeast(processed)
                    val finish = (loadStart + (segment.end * 16000).toLong()).coerceAtMost(end)
                    if (finish > start) {
                        events.getOrPut(start) { ArrayList() }.add(segment.speaker to 1)
                        events.getOrPut(finish) { ArrayList() }.add(segment.speaker to -1)
                    }
                }
                val spans = ArrayList<Span>()
                val active = HashMap<Int, Int>()
                var at = processed
                for ((point, changes) in events) {
                    if (point > at && active.isNotEmpty()) spans.add(Span(at, point, if (active.size == 1) active.keys.first() else -1))
                    for ((speaker, delta) in changes) {
                        val count = (active[speaker] ?: 0) + delta
                        if (count <= 0) active.remove(speaker) else active[speaker] = count
                    }
                    at = point
                }
                val embeddings = HashMap<Int, FloatArray>()
                for (speaker in spans.map { it.localSpeaker }.filter { it >= 0 }.distinct()) {
                    checkCancelled()
                    val clean = spans.filter { it.localSpeaker == speaker }
                    val total = minOf(48000L, clean.sumOf { it.end - it.start }).toInt()
                    if (total < 9600) continue
                    val voice = FloatArray(total)
                    var copied = 0
                    for (span in clean) {
                        val count = minOf(total - copied, (span.end - span.start).toInt())
                        if (count > 0) samples.copyInto(voice, copied, (span.start - loadStart).toInt(), (span.start - loadStart).toInt() + count)
                        copied += count
                        if (copied == total) break
                    }
                    val engine = extractor!!
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(voice, 16000)
                        stream.inputFinished()
                        if (engine.isReady(stream)) embeddings[speaker] = engine.compute(stream)
                    } finally { stream.release() }
                }
                for (span in spans) turns.add(VoiceTurn(span.start, span.end, embeddings[span.localSpeaker], span.localSpeaker < 0))
                processed = end
                checkCancelled()
                val root = JSONObject().put("fingerprint", config).put("processed", processed)
                val items = JSONArray()
                for (turn in turns) {
                    val item = JSONObject().put("start", turn.startSample).put("end", turn.endSample).put("overlap", turn.overlap)
                    turn.embedding?.let { vector ->
                        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        vector.forEach { buffer.putFloat(it) }
                        item.put("vector", Base64.encodeToString(buffer.array(), Base64.NO_WRAP))
                    }
                    items.put(item)
                }
                root.put("turns", items)
                val atomic = AtomicFile(checkpoint)
                val output = atomic.startWrite()
                try {
                    output.write(root.toString().toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                onWindow(GlobalSpeakers.assign(turns, expectedSpeakers), processed)
                progress(processed * 1000 / 16000)
            }
        }
        checkCancelled()
        return GlobalSpeakers.assign(turns, expectedSpeakers)
    }

    private fun read(file: RandomAccessFile, start: Long, count: Int): FloatArray {
        require(count <= 32 * 16000)
        file.seek(start * 2)
        val bytes = ByteArray(count * 2)
        file.readFully(bytes)
        return FloatArray(count) {
            (((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f
        }
    }
    override fun close() { extractor?.release(); extractor = null; segmentation.release() }
}
