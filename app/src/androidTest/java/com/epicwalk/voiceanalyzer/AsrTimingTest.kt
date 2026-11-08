package com.epicwalk.voiceanalyzer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epicwalk.voiceanalyzer.asr.GigaAmRnnt
import com.epicwalk.voiceanalyzer.audio.AudioDecoder
import com.epicwalk.voiceanalyzer.core.ModelGate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AsrTimingTest {
    @Test fun realRnntReturnsUsableWholeWordEmissionTimes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ScribeApplication
        app.startup.await(); assertTrue(app.modelPreparation.await())
        val prefix = UUID.randomUUID().toString()
        val wav = File(app.cacheDir, "$prefix.wav")
        val pcm = File(app.cacheDir, "$prefix.pcm")
        try {
            instrumentation.context.assets.open("example.wav").use { input ->
                wav.outputStream().use { output -> input.copyTo(output) }
            }
            AudioDecoder().decode(wav, pcm, {}, {})
            check(pcm.length() <= 25 * 32000)
            val bytes = pcm.readBytes()
            val samples = FloatArray(bytes.size / 2) {
                (((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f
            }
            ModelGate.mutex.withLock {
                GigaAmRnnt(app.models).use { asr ->
                    val result = asr.transcribeTimed(samples)
                    assertTrue("Native RNNT did not provide usable word emission times", result.words.isNotEmpty())
                    assertEquals(result.text.trim().replace(Regex("\\s+"), " "),
                        result.words.joinToString(" ") { it.text })
                    assertTrue(result.words.all { it.startSample >= 0 && it.endSample > it.startSample &&
                        it.endSample <= samples.size })
                    File(app.getExternalFilesDir(null), "asr-timing.json").writeText(JSONObject()
                        .put("words", result.words.size).put("audio_ms", samples.size / 16)
                        .put("first_word_start_ms", result.words.first().startSample / 16)
                        .put("last_word_end_ms", result.words.last().endSample / 16)
                        .put("note", "Real RNNT emission times on public Russian fixture; not forced alignment or timestamp accuracy.")
                        .toString(2))
                }
            }
        } finally { wav.delete(); pcm.delete() }
    }
}
