package com.epicwalk.voiceanalyzer.diarization

/** Carries unfinished speech across processing windows. No fabricated text or word alignment. */
object PhrasePlanner {
    fun plan(voices: List<VoiceTurn>, after: Long, through: Long, finalWindow: Boolean, speechOnly: Boolean = false,
        quietCut: (Long, Long) -> Long): List<VoiceTurn> {
        val merged = ArrayList<VoiceTurn>()
        for (turn in voices) {
            if (turn.endSample <= after) continue
            val voice = turn.copy(startSample = maxOf(turn.startSample, after))
            val previous = merged.lastOrNull()
            val joinsSpeechCoverage = previous != null &&
                if (speechOnly) voice.startSample <= previous.endSample + 8000
                else voice.startSample - previous.endSample in 0..8000
            if (previous != null && joinsSpeechCoverage && (speechOnly || (previous.speakerId != "speaker_unknown" &&
                previous.speakerId == voice.speakerId && previous.overlap == voice.overlap))) {
                merged[merged.lastIndex] = previous.copy(endSample = maxOf(previous.endSample, voice.endSample),
                    overlap = previous.overlap || voice.overlap,
                    speakerId = if (speechOnly) "speaker_unknown" else previous.speakerId)
            } else merged += voice
        }
        val result = ArrayList<VoiceTurn>()
        for (voice in merged) {
            var start = voice.startSample
            while (voice.endSample - start > 15 * 16000) {
                val end = quietCut(start + 11 * 16000, start + 15 * 16000)
                    .coerceIn(start + 11 * 16000, start + 15 * 16000)
                result += voice.copy(startSample = start, endSample = end)
                start = end
            }
            // The last second remains pending until the next window proves a pause/speaker change.
            if (finalWindow || voice.endSample < through - 16000)
                if (voice.endSample > start) result += voice.copy(startSample = start)
        }
        return result
    }
}
