package com.epicwalk.voiceanalyzer.audio

import kotlin.math.*

/**
 * Windowed-sinc polyphase resampler. Output sample 0 represents source time 0.
 * Right-hand filter support delays delivery, not the saved timeline.
 * The callback must consume samples synchronously; its array is reused.
 */
class StreamingResampler(
    private val inputRate: Int,
    private val outputRate: Int = 16000,
    private val output: (FloatArray, Int) -> Unit
) {
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private val divisor = gcd(inputRate, outputRate)
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate) * 0.95
    private val radius = ceil(16 / cutoff).toInt()
    private val phases = outputRate / divisor
    private val kernels = Array(phases) { phase ->
        val fraction = phase.toDouble() / phases
        val weights = DoubleArray(radius * 2 + 1) { index ->
            val x = index - radius - fraction
            if (abs(x) >= radius) 0.0 else {
                val sinc = if (abs(x) < 1e-10) cutoff else sin(PI * cutoff * x) / (PI * x)
                sinc * (0.5 + 0.5 * cos(PI * x / radius))
            }
        }
        val sum = weights.sum()
        FloatArray(weights.size) { (weights[it] / sum).toFloat() }
    }
    private val ring = FloatArray(16384)
    private val result = FloatArray(4096)
    private var head = 0
    private var retained = 0
    private var base = 0L
    private var inputCount = 0L
    private var outputCount = 0L
    private var finished = false

    init { require(inputRate in 8000..192000 && outputRate == 16000) }

    fun accept(samples: FloatArray, count: Int = samples.size) {
        check(!finished)
        require(count in 0..minOf(samples.size, 4096))
        if (inputRate == outputRate) {
            output(samples, count); inputCount += count; outputCount += count; return
        }
        check(retained + count <= ring.size)
        for (index in 0 until count) ring[(head + retained + index) % ring.size] = samples[index]
        retained += count
        inputCount += count
        drain(false)
    }

    fun finish() {
        check(!finished); finished = true
        if (inputRate != outputRate) drain(true)
    }

    private fun drain(flush: Boolean) {
        var ready = 0
        val maximum = inputCount * outputRate / inputRate
        while (outputCount < maximum) {
            val scaled = outputCount * inputRate
            val center = scaled / outputRate
            if (!flush && center + radius >= inputCount) break
            val phase = ((scaled % outputRate) / divisor).toInt()
            val weights = kernels[phase]
            var sum = 0.0
            for (tap in weights.indices) {
                val index = center + tap - radius
                if (index >= 0 && index < inputCount) {
                    val offset = index - base
                    check(offset >= 0 && offset < retained)
                    sum += ring[(head + offset.toInt()) % ring.size] * weights[tap]
                }
            }
            result[ready++] = sum.toFloat().coerceIn(-1f, 1f)
            outputCount++
            if (ready == result.size) { output(result, ready); ready = 0 }
        }
        if (ready > 0) output(result, ready)
        val nextCenter = outputCount * inputRate / outputRate
        val discard = (nextCenter - radius - base).coerceIn(0, retained.toLong()).toInt()
        head = (head + discard) % ring.size
        retained -= discard
        base += discard
    }
}
