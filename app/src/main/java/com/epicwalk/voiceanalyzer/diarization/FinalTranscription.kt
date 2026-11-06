package com.epicwalk.voiceanalyzer.diarization

import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.asr.GigaAmRnnt
import com.epicwalk.voiceanalyzer.core.ProcessingState
import com.epicwalk.voiceanalyzer.data.Utterance
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile

/** Invoked after the first ASR session is released, under the caller's ModelGate lock. */
class FinalTranscription(private val app: ScribeApplication) {
    suspend fun process(id: String, pcm: File, fingerprint: String, expectedSpeakers: Int,
        progress: (ProcessingState) -> Unit) {
        val coroutine = currentCoroutineContext()
        fun cancelled() { coroutine.ensureActive() }
        val dao = app.database.dao()
        val totalMs = pcm.length() * 1000 / 32000
        dao.processingState(id, "PROCESSING", "Глобальная диаризация", null)
        val checkpoint = File(app.repository.audioDirectory, "$id.diarization.json")
        val turns = BoundedDiarization(app.models).use { engine ->
            engine.analyze(pcm, checkpoint, fingerprint, expectedSpeakers, ::cancelled, progress = {
                progress(ProcessingState(id, "Сегментация и эмбеддинги голосов", it, totalMs))
            })
        }
        cancelled()
        check(turns.isNotEmpty() || dao.utterances(id).none { it.sourcePass == "asr" }) {
            "Диаризация не вернула интервалов. Предварительный текст сохранён."
        }
        val finalConfig = "$fingerprint:final-diar-v1:$expectedSpeakers"
        val configId = java.security.MessageDigest.getInstance("SHA-256").digest(finalConfig.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val configFile = File(app.repository.audioDirectory, "$id.final-config")
        if (!configFile.isFile || configFile.readText() != finalConfig) {
            dao.archiveFinal(id, "archived:${System.currentTimeMillis()}")
            configFile.writeText(finalConfig)
        }
        val merged = ArrayList<VoiceTurn>()
        for (turn in turns) {
            val last = merged.lastOrNull()
            if (last != null && last.speakerId == turn.speakerId && last.overlap == turn.overlap &&
                turn.startSample - last.endSample in 0..4800 &&
                turn.endSample - last.startSample <= 15 * 16000) {
                merged[merged.lastIndex] = last.copy(endSample = turn.endSample)
            } else merged.add(turn)
        }
        val existing = dao.utterances(id).filter { it.sourcePass == "diarized" }.associateBy { it.id }
        dao.processingState(id, "PROCESSING", "Уточнение текста по говорящим", null)
        GigaAmRnnt(app.models).use { asr ->
            RandomAccessFile(pcm, "r").use { input ->
                for (turn in merged) {
                    var start = turn.startSample
                    while (start < turn.endSample) {
                        cancelled()
                        val end = minOf(turn.endSample, start + 15 * 16000)
                        val key = "$id:final:$configId:$start:$end"
                        val saved = existing[key]
                        if (saved != null) {
                            dao.updateUtterance(saved.copy(speakerId = turn.speakerId, provisional = false, overlap = turn.overlap))
                        } else {
                            val count = (end - start).toInt()
                            val bytes = ByteArray(count * 2)
                            input.seek(start * 2); input.readFully(bytes)
                            val samples = FloatArray(count) {
                                (((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f
                            }
                            val text = asr.transcribe(samples)
                            cancelled()
                            if (text.isNotBlank()) dao.insertUtterance(Utterance(
                                key, id, turn.speakerId, start * 1000 / 16000, end * 1000 / 16000,
                                text, provisional = false, sourcePass = "diarized", overlap = turn.overlap
                            ))
                        }
                        start = end
                        val at = end * 1000 / 16000
                        dao.processingProgress(id, at, totalMs)
                        progress(ProcessingState(id, "Уточнение текста по говорящим", at, totalMs))
                    }
                }
            }
        }
        dao.processingProgress(id, totalMs, totalMs)
        dao.processingState(id, "COMPLETE", "Обработка завершена", null)
    }
}
