package com.epicwalk.voiceanalyzer.asr

data class TimedWord(val text: String, val startSample: Long, val endSample: Long)
data class AsrResult(val text: String, val words: List<TimedWord> = emptyList())

/** RNNT emission times are estimates, not forced alignment or exact acoustic boundaries. */
object TokenTiming {
    fun decode(text: String, tokens: Array<String>, timestamps: FloatArray, sampleCount: Int): AsrResult {
        if (tokens.isEmpty() || tokens.size != timestamps.size ||
            timestamps.any { !it.isFinite() || it < 0 } ||
            timestamps.indices.drop(1).any { timestamps[it] < timestamps[it - 1] }) return AsrResult(text)
        val characters = StringBuilder()
        val times = ArrayList<Long>()
        for (index in tokens.indices) {
            val token = tokens[index].replace('▁', ' ')
            for (character in token) {
                characters.append(character)
                times.add((timestamps[index] * 16000).toLong().coerceIn(0, sampleCount.toLong()))
            }
        }
        fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ")
        if (normalized(characters.toString()) != normalized(text)) return AsrResult(text)
        val words = Regex("\\S+").findAll(characters).map { word ->
            val start = times[word.range.first]
            val end = (times[word.range.last] + 1280).coerceAtMost(sampleCount.toLong())
            TimedWord(word.value, start, end)
        }.toList()
        if (words.any { it.endSample <= it.startSample }) return AsrResult(text)
        return AsrResult(text, words)
    }
}
