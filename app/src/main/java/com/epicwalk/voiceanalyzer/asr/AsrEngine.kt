package com.epicwalk.voiceanalyzer.asr

import com.epicwalk.voiceanalyzer.core.ModelStore
import com.k2fsa.sherpa.onnx.*

interface AsrEngine : AutoCloseable {
    /** Mono float PCM at 16 kHz; the caller limits each utterance to 15 seconds. */
    fun transcribe(samples: FloatArray): String
}

class GigaAmRnnt(models: ModelStore, punctuated: Boolean = true) : AsrEngine {
    private val prefix = if (punctuated) "e2e/" else ""
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = models.file(prefix + "encoder.int8.onnx").absolutePath,
                decoder = models.file(prefix + "decoder.onnx").absolutePath,
                joiner = models.file(prefix + "joiner.onnx").absolutePath
            ),
            tokens = models.file(prefix + "tokens.txt").absolutePath,
            modelType = "nemo_transducer", provider = "cpu", numThreads = 2
        )
    ))
    override fun transcribe(samples: FloatArray): String = transcribeTimed(samples).text

    fun transcribeTimed(samples: FloatArray): AsrResult {
        require(samples.size <= 25 * 16000)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return TokenTiming.decode(result.text.trim(), result.tokens, result.timestamps, samples.size)
        } finally { stream.release() }
    }
    override fun close() { recognizer.release() }
}
