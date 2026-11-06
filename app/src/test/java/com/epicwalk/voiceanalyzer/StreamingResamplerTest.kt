package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.audio.StreamingResampler
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StreamingResamplerTest {
    private fun resample(rate: Int, tone: Double, chunk: Int): FloatArray {
        val collected = ArrayList<Float>()
        val engine = StreamingResampler(rate) { values, count ->
            for (index in 0 until count) collected.add(values[index])
        }
        val source = FloatArray(rate) { sin(2 * PI * tone * it / rate).toFloat() }
        var position = 0
        while (position < source.size) {
            val end = minOf(source.size, position + chunk)
            engine.accept(source.copyOfRange(position, end)); position = end
        }
        engine.finish()
        return collected.toFloatArray()
    }
    @Test fun chunkBoundariesDoNotChangeTimelineOrWaveform() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000)) {
            val small = resample(rate, 1000.0, 137)
            val large = resample(rate, 1000.0, 4096)
            assertEquals(16000, small.size)
            assertArrayEquals(large, small, 0.000001f)
            val error = (200 until 15800).map {
                abs(small[it] - sin(2 * PI * 1000 * it / 16000))
            }.average()
            assertTrue("Passband/time error at $rate: $error", error < 0.005)
        }
    }
    @Test fun suppressesAliasingAboveOutputNyquist() {
        val samples = resample(48000, 12000.0, 4096)
        val rms = sqrt((200 until 15800).map { samples[it].toDouble().pow(2) }.average())
        assertTrue("Aliasing RMS $rms", rms < 0.005)
    }
}
