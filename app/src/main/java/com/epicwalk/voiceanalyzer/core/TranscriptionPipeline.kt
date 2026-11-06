package com.epicwalk.voiceanalyzer.core

import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.asr.GigaAmRnnt
import com.epicwalk.voiceanalyzer.audio.AudioDecoder
import com.epicwalk.voiceanalyzer.data.Utterance
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class ProcessingState(val id: String? = null, val stage: String = "", val processedMs: Long = 0,
    val totalMs: Long = 0, val cancelling: Boolean = false)

class TranscriptionPipeline(private val app: ScribeApplication) {
    suspend fun process(id: String, progress: (ProcessingState) -> Unit) = withContext(Dispatchers.IO) {
        ModelGate.mutex.withLock {
            app.startup.await()
            val dao = app.database.dao()
            val conversation = dao.conversation(id) ?: error("Разговор не найден")
            check(conversation.status != "RECORDING" && conversation.status != "IMPORTING") { "Аудио ещё сохраняется" }
            check(app.models.state.value.asrReady) { "Сначала установите проверенный комплект моделей в настройках" }
            val source = File(conversation.audioPath)
            check(source.isFile) { "Исходное аудио не найдено" }
            val coroutine = currentCoroutineContext()
            fun cancelled() { coroutine.ensureActive() }
            dao.processingState(id, "PROCESSING", "Подготовка", null)
            try {
                val normalized = File(app.repository.audioDirectory, "$id.pcm")
                val marker = File(app.repository.audioDirectory, "$id.pcm.sha256")
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        cancelled()
                        val count = input.read(bytes)
                        if (count < 0) break
                        digest.update(bytes, 0, count)
                    }
                }
                val fingerprint = digest.digest().joinToString("") { "%02x".format(it) } + ":pcm16k-v1"
                val valid = normalized.isFile && normalized.length() > 0 &&
                    normalized.length() % 2L == 0L && marker.isFile && marker.readText() == fingerprint
                if (!valid) {
                    marker.delete()
                    progress(ProcessingState(id, "Декодирование в PCM 16 кГц", 0, conversation.durationMs))
                    AudioDecoder().decode(source, normalized, ::cancelled) { duration ->
                        progress(ProcessingState(id, "Декодирование в PCM 16 кГц", duration, conversation.durationMs))
                    }
                    marker.writeText(fingerprint)
                }
                val sampleCount = normalized.length() / 2
                val durationMs = sampleCount * 1000 / 16000
                dao.processingState(id, "PROCESSING", "Распознавание речи", null)
                val existing = dao.utterances(id).associateBy { it.id }
                val vad = Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = app.models.file("silero_vad.onnx").absolutePath,
                        minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, maxSpeechDuration = 15f
                    ), numThreads = 1, provider = "cpu"
                ))
                try {
                    GigaAmRnnt(app.models).use { asr ->
                        suspend fun drain() {
                            while (!vad.empty()) {
                                cancelled()
                                val segment = vad.front()
                                try {
                                    val start = segment.start.coerceAtLeast(0)
                                    val end = minOf(sampleCount, start + segment.samples.size)
                                    val key = "$id:$start:$end"
                                    if (end > start && key !in existing) {
                                        val count = (end - start).toInt()
                                        val samples = if (count == segment.samples.size) segment.samples else segment.samples.copyOf(count)
                                        val text = asr.transcribe(samples)
                                        cancelled()
                                        if (text.isNotBlank()) dao.insertUtterance(Utterance(
                                            key, id, "speaker_unknown", start * 1000 / 16000,
                                            end * 1000 / 16000, text, provisional = true
                                        ))
                                    }
                                } finally { vad.pop() }
                            }
                        }
                        RandomAccessFile(normalized, "r").use { input ->
                            val bytes = ByteArray(1024)
                            val samples = FloatArray(512)
                            var processed = 0L
                            var saved = 0L
                            while (processed < sampleCount) {
                                cancelled()
                                val count = minOf(512L, sampleCount - processed).toInt()
                                input.readFully(bytes, 0, count * 2)
                                samples.fill(0f)
                                for (index in 0 until count) {
                                    val sample = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
                                    samples[index] = sample / 32768f
                                }
                                vad.acceptWaveform(samples)
                                drain()
                                processed += count
                                val at = processed * 1000 / 16000
                                progress(ProcessingState(id, "Распознавание речи", at, durationMs))
                                if (at - saved >= 2000) {
                                    dao.processingProgress(id, at, durationMs)
                                    saved = at
                                }
                            }
                        }
                        vad.flush(); drain()
                    }
                } finally { vad.release() }
                dao.processingProgress(id, durationMs, durationMs)
                dao.processingState(id, "TRANSCRIBED", "Текст распознан; диаризация ещё не выполнена", null)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { dao.processingState(id, "CANCELLED", "Обработка отменена", null) }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    dao.processingState(id, "ERROR", "Обработка остановлена", failure.message ?: "Ошибка локального распознавания")
                }
                throw failure
            }
        }
    }
}
