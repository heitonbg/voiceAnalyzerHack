package com.epicwalk.voiceanalyzer.data

import com.epicwalk.voiceanalyzer.core.TranscriptText

/** Recognition chunks are implementation details, not automatic paragraph boundaries. */
object UtteranceMerger {
    fun merge(previous: Utterance, next: Utterance): Utterance? {
        val hasStableOverlapCandidates = previous.speakerCandidates
            ?.split('|')?.filter(String::isNotBlank)?.distinct()?.size == 2
        val maximumGapMs = when {
            previous.speakerId != "speaker_unknown" -> 1_500
            previous.overlap && hasStableOverlapCandidates -> 1_500
            else -> 200
        }
        val maximumSpanMs = when {
            previous.speakerId != "speaker_unknown" -> 90_000
            previous.overlap && hasStableOverlapCandidates -> 30_000
            else -> 4_000
        }
        if (previous.conversationId != next.conversationId || previous.sourcePass != next.sourcePass ||
            (previous.speakerId == "speaker_unknown" &&
                (!previous.overlap || !hasStableOverlapCandidates)) ||
            previous.speakerId != next.speakerId || previous.speakerCandidates != next.speakerCandidates ||
            previous.overlap != next.overlap || next.startMs - previous.endMs !in -200..maximumGapMs ||
            next.endMs - previous.startMs > maximumSpanMs ||
            previous.editedText != null || next.editedText != null ||
            previous.editedSpeakerId != null || next.editedSpeakerId != null) return null
        return previous.copy(endMs = maxOf(previous.endMs, next.endMs),
            rawText = TranscriptText.join(previous.rawText, next.rawText),
            provisional = previous.provisional || next.provisional)
    }
}
