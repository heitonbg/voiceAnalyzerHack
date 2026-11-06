package com.epicwalk.voiceanalyzer.diarization

import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.asr.GigaAmRnnt
import com.epicwalk.voiceanalyzer.core.ProcessingState
import com.epicwalk.voiceanalyzer.data.Utterance
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile

/** One ASR pass. Native calls run sequentially; bounded CPU models stay warm between windows. */
class StreamingTranscription(private val app: ScribeApplication) {
    suspend fun process(id: String, pcm: File, fingerprint: String, expected: Int, progress: (ProcessingState) -> Unit,
        awaitSamples: suspend (Long) -> Long = { pcm.length() / 2 }, declaredDurationMs: Long = pcm.length() * 1000 / 32000) {
        val dao = app.database.dao()
        val coroutine = currentCoroutineContext()
        var duration = declaredDurationMs
        val config = "$fingerprint:streamed-diar-asr-v5:$expected"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(config.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val configFile = File(app.repository.audioDirectory, "$id.final-config")
        if (!configFile.isFile || configFile.readText() != config) {
            dao.archiveFinal(id, "archived:${System.currentTimeMillis()}")
            configFile.writeText(config)
        }
        val existing = dao.utterances(id).filter { it.sourcePass == "diarized" }.associateBy { it.id }.toMutableMap()
        var recognisedThrough = existing.keys.maxOfOrNull { it.substringAfterLast(':').toLongOrNull() ?: 0L } ?: 0L
        var turns: List<VoiceTurn> = emptyList()
        fun speakerFor(row: Utterance, voices: List<VoiceTurn>): String {
            val start = row.startMs * 16
            val end = row.endMs * 16
            var low = 0
            var high = voices.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (voices[middle].endSample <= start) low = middle + 1 else high = middle
            }
            return voices.asSequence().drop(low).takeWhile { it.startSample < end }
                .groupBy { it.speakerId }.mapValues { (_, group) ->
                    group.sumOf { minOf(it.endSample, end) - maxOf(it.startSample, start) }
                }.maxByOrNull { it.value }?.key ?: "speaker_unknown"
        }
        dao.processingState(id, "PROCESSING", "Разделение голосов и распознавание", null)
        GigaAmRnnt(app.models).use { asr ->
            RandomAccessFile(pcm, "r").use { input ->
                BoundedDiarization(app.models).use { diarization ->
                    turns = diarization.analyze(pcm, File(app.repository.audioDirectory, "$id.diarization.json"),
                        fingerprint, expected, { coroutine.ensureActive() },
                        { at -> progress(ProcessingState(id, "Голоса и текст · предварительный результат", at, duration)) }, awaitSamples
                    ) { voices, through ->
                        coroutine.ensureActive()
                        // Update previously emitted IDs against all voice evidence seen so far.
                        for ((key, row) in existing.toMap()) {
                            val speaker = speakerFor(row, voices)
                            if (row.speakerId != speaker) {
                                val updated = row.copy(speakerId = speaker)
                                dao.updateUtterance(updated); existing[key] = updated
                            }
                        }
                        val finalWindow = awaitSamples(through + 1) <= through
                        val merged = PhrasePlanner.plan(voices, recognisedThrough, through, finalWindow) { from, to ->
                            val frame = ByteArray(640)
                            var best = to
                            var energy = Double.POSITIVE_INFINITY
                            var at = from
                            while (at + 320 <= to) {
                                input.seek(at * 2); input.readFully(frame)
                                var sum = 0.0
                                for (i in 0 until 320) {
                                    val value = ((frame[i * 2].toInt() and 255) or (frame[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
                                    sum += value * value
                                }
                                // Prefer a quiet frame near the end, rather than a rigid 15-second cut.
                                val score = sum / 320 * (1.0 + (to - at).toDouble() / 64000)
                                if (score < energy) { energy = score; best = at + 160 }
                                at += 320
                            }
                            best
                        }
                        for (voice in merged) {
                            var start = voice.startSample
                            while (start < voice.endSample) {
                                coroutine.ensureActive()
                                val end = minOf(voice.endSample, start + 15 * 16000)
                                val key = "$id:stream:$hash:$start:$end"
                                if (key !in existing) {
                                    val contextStart = (start - 2400).coerceAtLeast(0)
                                    val contextEnd = minOf(through, end + 2400)
                                    val count = (contextEnd - contextStart).toInt()
                                    val bytes = ByteArray(count * 2)
                                    input.seek(contextStart * 2); input.readFully(bytes)
                                    val samples = FloatArray(count) {
                                        (((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f
                                    }
                                    val text = asr.transcribe(samples)
                                    coroutine.ensureActive()
                                    if (text.isNotBlank()) {
                                        val row = Utterance(key, id, voice.speakerId, start * 1000 / 16000,
                                            end * 1000 / 16000, text, provisional = true, sourcePass = "diarized", overlap = voice.overlap)
                                        // Preserve immutable original ASR separately without another inference pass.
                                        dao.insertUtterance(row.copy(id = "$key:raw", speakerId = "speaker_unknown", sourcePass = "asr"))
                                        dao.insertUtterance(row); existing[key] = row
                                    }
                                }
                                dao.processingProgress(id, end * 1000 / 16000, duration)
                                progress(ProcessingState(id, "Распознавание речи", end * 1000 / 16000, duration))
                                start = end
                            }
                        }
                        recognisedThrough = merged.lastOrNull()?.endSample ?: recognisedThrough
                        dao.processingProgress(id, through * 1000 / 16000, duration)
                    }
                }
            }
        }
        coroutine.ensureActive()
        for (row in existing.values) {
            dao.updateUtterance(row.copy(speakerId = speakerFor(row, turns), provisional = false))
        }
        duration = pcm.length() * 1000 / 32000
        dao.processingProgress(id, duration, duration)
        dao.processingState(id, "COMPLETE", "Обработка завершена", null)
        progress(ProcessingState(id, "Обработка завершена", duration, duration))
    }
}
