package com.epicwalk.voiceanalyzer.diarization

import android.util.AtomicFile
import com.epicwalk.voiceanalyzer.asr.AsrResult
import com.epicwalk.voiceanalyzer.asr.TimedWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WordChunk(val start: Long, val end: Long, val offset: Long, val result: AsrResult)

/** Private on-device word evidence allows final global IDs without another ASR pass. */
class WordCheckpoint(private val directory: File) {
    fun save(chunk: WordChunk) {
        check(directory.mkdirs() || directory.isDirectory)
        val words = JSONArray()
        chunk.result.words.forEach { word ->
            words.put(JSONObject().put("text", word.text).put("start", word.startSample).put("end", word.endSample))
        }
        val json = JSONObject().put("start", chunk.start).put("end", chunk.end)
            .put("offset", chunk.offset).put("text", chunk.result.text).put("words", words)
        val file = AtomicFile(File(directory, "${chunk.start}-${chunk.end}.json"))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    fun files(): List<File> = directory.listFiles()?.filter { it.extension == "json" }
        ?.sortedBy { it.name.substringBefore('-').toLongOrNull() ?: Long.MAX_VALUE }.orEmpty()

    fun read(file: File): WordChunk {
        check(file.length() in 1..(1024 * 1024)) { "Некорректный checkpoint слов" }
        val json = JSONObject(file.readText())
        val words = json.getJSONArray("words")
        val result = AsrResult(json.getString("text"), (0 until words.length()).map { index ->
            val word = words.getJSONObject(index)
            TimedWord(word.getString("text"), word.getLong("start"), word.getLong("end"))
        })
        return WordChunk(json.getLong("start"), json.getLong("end"), json.getLong("offset"), result)
    }
}
