package com.epicwalk.voiceanalyzer.llm

import android.content.Context
import com.epicwalk.voiceanalyzer.data.Utterance
import org.json.JSONObject
import java.io.File

object AnalysisStore {
    fun read(context: Context, id: String, rows: List<Utterance>): AnalysisResult? {
        val file = File(context.filesDir, "$id.analysis.json")
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val source = root.getJSONArray("source")
            val expected = rows.map { it.id + ":" + (it.editedText ?: it.rawText) }
            check((0 until source.length()).map(source::getString) == expected)
            val values = root.getJSONArray("facts")
            val validIds = rows.map { it.id }.toSet()
            val facts = (0 until values.length()).map { index ->
                val fact = values.getJSONObject(index)
                val array = fact.getJSONArray("ids")
                val ids = (0 until array.length()).map(array::getString)
                check(ids.isNotEmpty() && ids.all { it in validIds })
                Evidence(fact.getString("kind"), ids)
            }
            AnalysisResult(facts, elapsedMs = root.getLong("elapsedMs"))
        }.getOrNull()
    }
}
