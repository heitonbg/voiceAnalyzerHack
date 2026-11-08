package com.epicwalk.voiceanalyzer.diarization

import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.asr.GigaAmRnnt
import com.epicwalk.voiceanalyzer.core.ProcessingState
import com.epicwalk.voiceanalyzer.data.Utterance
import com.epicwalk.voiceanalyzer.data.UtteranceMerger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.room.withTransaction
import java.io.File
import java.io.RandomAccessFile

/** One ASR pass. Native calls run sequentially; bounded CPU models stay warm between windows. */
class StreamingTranscription(private val app: ScribeApplication) {
    suspend fun process(id: String, pcm: File, fingerprint: String, expected: Int, progress: (ProcessingState) -> Unit,
        awaitSamples: suspend (Long) -> Long = { pcm.length() / 2 }, declaredDurationMs: Long = pcm.length() * 1000 / 32000) {
        val dao = app.database.dao()
        val coroutine = currentCoroutineContext()
        var duration = declaredDurationMs
        val config = "$fingerprint:streamed-diar-asr-v25-word-overlap-quarter-gap1500-threads2-1:$expected"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(config.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val configFile = File(app.repository.audioDirectory, "$id.final-config")
        if (!configFile.isFile || configFile.readText() != config) {
            dao.archiveFinal(id, "archived:${System.currentTimeMillis()}")
            configFile.writeText(config)
        }
        val stored = dao.utterances(id)
        val wordStore = WordCheckpoint(File(app.repository.audioDirectory, "$id.words-$hash"))
        val existing = stored.filter { it.sourcePass == "diarized" }.associateBy { it.id }.toMutableMap()
        var recognisedThrough = stored.filter { it.sourcePass == "asr" &&
            it.id.startsWith("$id:stream:$hash:") && it.id.endsWith(":raw") }
            .maxOfOrNull { it.id.removeSuffix(":raw").substringAfterLast(':').toLongOrNull() ?: 0L } ?: 0L
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
            val intersecting = voices.asSequence().drop(low).takeWhile { it.startSample < end }
                .filter { it.endSample > start }.toList()
            val scores = intersecting.groupBy { it.speakerId }.mapValues { (_, group) ->
                group.sumOf { minOf(it.endSample, end) - maxOf(it.startSample, start) }
            }
            val hasOverlappingEvidence = intersecting.any { it.overlap }
            // Overlap is positive evidence of mixed speech; a single detected lane does not
            // establish that it contains the whole phrase, so keep the schema speaker unknown.
            return if (hasOverlappingEvidence) "speaker_unknown"
            else scores.maxByOrNull { it.value }?.key ?: "speaker_unknown"
        }
        fun overlapCandidates(start: Long, end: Long, voices: List<VoiceTurn>): List<String> =
            voices.asSequence().filter { it.overlap && it.speakerId != "speaker_unknown" &&
                it.startSample < end && it.endSample > start }
                .groupBy { it.speakerId }
                .mapValues { (_, group) -> group.sumOf { minOf(it.endSample, end) - maxOf(it.startSample, start) } }
                .entries.sortedByDescending { it.value }.take(2).map { it.key }
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
                            val candidates = overlapCandidates(row.startMs * 16, row.endMs * 16, voices)
                                .takeIf { row.overlap && it.size > 1 }?.joinToString("|")
                            if (row.speakerId != speaker || row.speakerCandidates != candidates) {
                                val updated = row.copy(speakerId = speaker, speakerCandidates = candidates)
                                dao.updateUtterance(updated); existing[key] = updated
                            }
                        }
                        val finalWindow = awaitSamples(through + 1) <= through
                        val merged = PhrasePlanner.plan(voices, recognisedThrough, through, finalWindow, speechOnly = true) { from, to ->
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
                                if (end > recognisedThrough) {
                                    val contextStart = (start - 7200).coerceAtLeast(0)
                                    val contextEnd = minOf(through, end + 7200)
                                    val count = (contextEnd - contextStart).toInt()
                                    val bytes = ByteArray(count * 2)
                                    input.seek(contextStart * 2); input.readFully(bytes)
                                    val samples = FloatArray(count) {
                                        (((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f
                                    }
                                    val recognized = asr.transcribeTimed(samples)
                                    coroutine.ensureActive()
                                    val phrases = if (recognized.words.isNotEmpty()) {
                                        WordSpeakerPlanner.plan(recognized.words, contextStart, start, end, voices)
                                    } else if (recognized.text.isNotBlank()) {
                                        val coarse = Utterance(key, id, "speaker_unknown", start / 16, end / 16, recognized.text)
                                        val candidates = overlapCandidates(start, end, voices)
                                    listOf(SpokenPhrase(start, end,
                                        if (candidates.size > 1) "speaker_unknown" else speakerFor(coarse, voices),
                                        recognized.text, voice.overlap, candidates))
                                    } else emptyList()
                                    wordStore.save(WordChunk(start, end, contextStart, recognized))
                                    app.database.withTransaction {
                                        for (phrase in phrases) {
                                            val rowKey = "$key:word:${phrase.startSample}:${phrase.endSample}"
                                            val row = Utterance(rowKey, id, phrase.speakerId,
                                                phrase.startSample / 16, maxOf(phrase.startSample / 16 + 1, phrase.endSample / 16),
                                                phrase.text, provisional = true, sourcePass = "diarized", overlap = phrase.overlap,
                                                speakerCandidates = phrase.candidateSpeakerIds.takeIf { it.size > 1 }?.joinToString("|"))
                                            val previous = existing.values.maxByOrNull { it.endMs }
                                            val joined = previous?.let { UtteranceMerger.merge(it, row) }
                                            if (joined != null) {
                                                dao.updateUtterance(joined); existing[joined.id] = joined
                                            } else {
                                                dao.insertUtterance(row); existing[rowKey] = row
                                            }
                                        }
                                        // Commit raw chunk and complete word rows together; resume only after this core.
                                        dao.insertUtterance(Utterance("$key:raw", id, "speaker_unknown",
                                            start / 16, end / 16, recognized.text, sourcePass = "asr"))
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
        val chunks = wordStore.files()
        app.database.withTransaction {
            dao.archiveFinal(id, "preliminary:" + System.currentTimeMillis())
            var pending: Utterance? = null
            for ((index, file) in chunks.withIndex()) {
                coroutine.ensureActive()
                val chunk = wordStore.read(file)
                val key = "$id:stream:$hash:${chunk.start}:${chunk.end}"
                val phrases = if (chunk.result.words.isNotEmpty()) {
                    WordSpeakerPlanner.plan(chunk.result.words, chunk.offset, chunk.start, chunk.end, turns)
                } else if (chunk.result.text.isNotBlank()) {
                    val coarse = Utterance(key, id, "speaker_unknown", chunk.start / 16, chunk.end / 16, chunk.result.text)
                    val candidates = overlapCandidates(chunk.start, chunk.end, turns)
                    listOf(SpokenPhrase(chunk.start, chunk.end,
                        if (candidates.size > 1) "speaker_unknown" else speakerFor(coarse, turns),
                        chunk.result.text, turns.any { it.overlap && it.startSample < chunk.end && it.endSample > chunk.start },
                        candidates))
                } else emptyList()
                for (phrase in phrases) {
                    val rowKey = "$key:word:${phrase.startSample}:${phrase.endSample}"
                    val previous = existing[rowKey]
                    val row = Utterance(rowKey, id, phrase.speakerId, phrase.startSample / 16,
                        maxOf(phrase.startSample / 16 + 1, phrase.endSample / 16), phrase.text,
                        editedText = previous?.editedText, editedSpeakerId = previous?.editedSpeakerId,
                        provisional = false, sourcePass = "diarized", overlap = phrase.overlap,
                        speakerCandidates = phrase.candidateSpeakerIds.takeIf { it.size > 1 }?.joinToString("|"))
                    val joined = pending?.let { UtteranceMerger.merge(it, row) }
                    if (joined != null) pending = joined else {
                        pending?.let { dao.insertUtterance(it) }
                        pending = row
                    }
                }
                progress(ProcessingState(id, "Уточнение говорящих · ${index + 1}/${chunks.size}", chunk.end / 16, duration))
            }
            pending?.let { dao.insertUtterance(it) }
        }
        duration = pcm.length() * 1000 / 32000
        dao.processingProgress(id, duration, duration)
        dao.processingState(id, "COMPLETE", "Обработка завершена", null)
        progress(ProcessingState(id, "Обработка завершена", duration, duration))
    }
}
