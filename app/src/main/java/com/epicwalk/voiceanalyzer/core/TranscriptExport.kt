package com.epicwalk.voiceanalyzer.core

import com.epicwalk.voiceanalyzer.data.Utterance
import org.json.JSONArray
import org.json.JSONObject

object TranscriptExport {
    fun encode(utterances: List<Utterance>, durationMs: Long): String {
        val rows = JSONArray()
        for (utterance in utterances.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
            require(!utterance.provisional && utterance.sourcePass == "diarized") { "Финальная диаризация не завершена" }
            require(utterance.startMs >= 0 && utterance.endMs > utterance.startMs && utterance.endMs <= durationMs) {
                "Недопустимые временные метки"
            }
            val speaker = utterance.editedSpeakerId ?: utterance.speakerId
            require(Regex("speaker_([1-9][0-9]*|unknown)").matches(speaker)) { "Недопустимый speaker_id" }
            val text = utterance.editedText ?: utterance.rawText
            require(text.isNotBlank()) { "Пустая реплика" }
            rows.put(JSONObject().put("speaker_id", speaker).put("start_ms", utterance.startMs)
                .put("end_ms", utterance.endMs).put("text", text))
        }
        return JSONObject().put("transcript", rows).toString(2)
    }
}
