package com.epicwalk.voiceanalyzer.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Bounded Android decoder to headerless PCM16 mono 16 kHz, stored on disk. */
class AudioDecoder {
    fun decode(source: File, destination: File, checkCancelled: () -> Unit, progress: (Long) -> Unit): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиоформат")
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingResampler? = null
            var frames = 0L
            var lastReported = -16000L
            RandomAccessFile(destination, "rw").use { target ->
                target.setLength(0)
                val bytes = ByteArray(8192)
                val mono = FloatArray(4096)
                fun output(samples: FloatArray, count: Int) {
                    checkCancelled()
                    for (index in 0 until count) {
                        val sample = (samples[index].coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)
                        bytes[index * 2] = sample.toByte()
                        bytes[index * 2 + 1] = (sample shr 8).toByte()
                    }
                    target.write(bytes, 0, count * 2)
                    frames += count
                    if (frames - lastReported >= 16000) {
                        progress(frames * 1000 / 16000); lastReported = frames
                    }
                }
                fun consume(buffer: ByteBuffer) {
                    check(rate in 8000..192000 && channels in 1..8) { "Неподдерживаемая частота или число каналов" }
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        "Декодер вернул неподдерживаемый PCM-формат: $encoding"
                    }
                    if (resampler == null) resampler = StreamingResampler(rate, output = ::output)
                    val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    check(buffer.remaining() % (sampleBytes * channels) == 0) { "Неполный PCM-кадр" }
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    while (buffer.hasRemaining()) {
                        checkCancelled()
                        val count = minOf(4096, buffer.remaining() / (sampleBytes * channels))
                        for (frame in 0 until count) {
                            var sum = 0f
                            repeat(channels) {
                                sum += if (sampleBytes == 4) buffer.float.coerceIn(-1f, 1f) else buffer.short / 32768f
                            }
                            mono[frame] = sum / channels
                        }
                        resampler!!.accept(mono, count)
                    }
                }
                if (mime == "audio/raw") {
                    val buffer = ByteBuffer.allocate(262144)
                    while (true) {
                        checkCancelled()
                        check(extractor.sampleSize <= buffer.capacity()) { "Слишком большой raw PCM пакет" }
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) break
                        buffer.position(0); buffer.limit(count)
                        consume(buffer)
                        extractor.advance()
                    }
                } else {
                    val decoder = MediaCodec.createDecoderByType(mime)
                    codec = decoder
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start(); started = true
                    var inputEnded = false
                    var outputEnded = false
                    var lastActivity = android.os.SystemClock.elapsedRealtime()
                    val info = MediaCodec.BufferInfo()
                    while (!outputEnded) {
                        checkCancelled()
                        if (!inputEnded) {
                            val index = decoder.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val buffer = decoder.getInputBuffer(index) ?: error("Нет входного буфера декодера")
                                buffer.clear()
                                check(extractor.sampleSize <= buffer.capacity()) { "Аудиопакет превышает буфер декодера" }
                                val count = extractor.readSampleData(buffer, 0)
                                if (count < 0) {
                                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEnded = true
                                } else {
                                    decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                                lastActivity = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                        val index = decoder.dequeueOutputBuffer(info, 10000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val format = decoder.outputFormat
                            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(frames == 0L || (rate == newRate && channels == newChannels)) {
                                "Смена PCM-формата внутри записи не поддерживается"
                            }
                            rate = newRate; channels = newChannels
                            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            lastActivity = android.os.SystemClock.elapsedRealtime()
                        } else if (index >= 0) {
                            try {
                                if (info.size > 0) {
                                    val buffer = decoder.getOutputBuffer(index) ?: error("Нет PCM-буфера")
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    consume(buffer)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(index, false) }
                            lastActivity = android.os.SystemClock.elapsedRealtime()
                        }
                        check(android.os.SystemClock.elapsedRealtime() - lastActivity < 15000) { "Декодер перестал отдавать данные" }
                    }
                }
                resampler?.finish()
                target.fd.sync()
                check(frames > 0) { "Декодер не вернул аудио" }
                progress(frames * 1000 / 16000)
            }
            return frames
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
