package com.epicwalk.voiceanalyzer.diarization

import com.epicwalk.voiceanalyzer.asr.TimedWord
import com.epicwalk.voiceanalyzer.core.TranscriptText

data class SpokenPhrase(val startSample: Long, val endSample: Long, val speakerId: String,
    val text: String, val overlap: Boolean, val candidateSpeakerIds: List<String> = emptyList())

/** Assign complete recognized words; a diarization boundary never cuts a word's text. */
object WordSpeakerPlanner {
    fun plan(words: List<TimedWord>, offset: Long, coreStart: Long, coreEnd: Long,
        voices: List<VoiceTurn>): List<SpokenPhrase> {
        val result = ArrayList<SpokenPhrase>()
        for (word in words) {
            val start = offset + word.startSample
            val end = offset + word.endSample
            if (start < coreStart || start >= coreEnd || end <= start) continue
            val intersecting = voices.filter { it.startSample < end && it.endSample > start }
            val scores = intersecting.groupBy { it.speakerId }.mapValues { (_, turns) ->
                turns.sumOf { minOf(end, it.endSample) - maxOf(start, it.startSample) }
            }
            // A boundary grazing a word is not evidence that the whole word is mixed.
            val overlapSamples = intersecting.filter { it.overlap }
                .maxOfOrNull { minOf(end, it.endSample) - maxOf(start, it.startSample) } ?: 0L
            val overlap = overlapSamples * 4 >= end - start
            val candidates = if (overlap) scores.entries
                .filter { it.key != "speaker_unknown" && it.value > 0 }
                .sortedByDescending { it.value }
                .take(2).map { it.key } else emptyList()
            // The transcript schema only allows one speaker_id. Keep it unknown when
            // two local lanes overlap; the candidate IDs are stored separately for UI/PDF.
            val speaker = if (overlap) "speaker_unknown"
                else scores.maxByOrNull { it.value }?.key ?: "speaker_unknown"
            val previous = result.lastOrNull()
            val maximumGap = when {
                speaker != "speaker_unknown" -> 24000L
                candidates.size >= 2 -> 24000L
                else -> 3200L // Keep unknown spans local; don't bridge a likely turn boundary.
            }
            val maximumSpan = when {
                speaker != "speaker_unknown" -> 90L * 16000
                candidates.size >= 2 -> 30L * 16000
                else -> 4L * 16000
            }
            if (previous != null && previous.speakerId == speaker && previous.overlap == overlap &&
                previous.candidateSpeakerIds == candidates &&
                start - previous.endSample <= maximumGap && end - previous.startSample <= maximumSpan) {
                result[result.lastIndex] = previous.copy(endSample = maxOf(previous.endSample, end),
                    text = TranscriptText.join(previous.text, word.text))
            } else result += SpokenPhrase(start, end, speaker, word.text, overlap, candidates)
        }
        return result
    }
}
