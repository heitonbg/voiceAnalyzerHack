package com.epicwalk.voiceanalyzer.asr

import com.epicwalk.voiceanalyzer.core.ModelStore
import com.k2fsa.sherpa.onnx.*

interface AsrEngine : AutoCloseable {
    /** Mono float PCM at 16 kHz; the caller limits each utterance to 15 seconds. */
    fun transcribe(samples: FloatArray): String
}

class GigaAmRnnt(models: ModelStore) : AsrEngine {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = models.file("encoder.int8.onnx").absolutePath,
                decoder = models.file("decoder.onnx").absolutePath,
                joiner = models.file("joiner.onnx").absolutePath
            ),
            tokens = models.file("tokens.txt").absolutePath,
            modelType = "nemo_transducer", provider = "cpu", numThreads = 2
        )
    ))
    override fun transcribe(samples: FloatArray): String {
        require(samples.size <= 16 * 16000)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() { recognizer.release() }
}
