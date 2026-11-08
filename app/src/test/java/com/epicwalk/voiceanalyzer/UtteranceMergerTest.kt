package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.data.Utterance
import com.epicwalk.voiceanalyzer.data.UtteranceMerger
import org.junit.Assert.*
import org.junit.Test

class UtteranceMergerTest {
    private fun row(id: String, from: Long, to: Long, speaker: String = "speaker_1") =
        Utterance(id, "meeting", speaker, from, to, id, sourcePass = "diarized")
    @Test fun nativeChunkBoundaryDoesNotCreateAnotherMessage() {
        val joined = UtteranceMerger.merge(row("один", 0, 15000), row("два", 15100, 20000))!!
        assertEquals("один два", joined.rawText)
        assertEquals(20000L, joined.endMs)
        assertEquals("один", joined.id)
    }
    @Test fun realSpeakerChangeAndLongPauseStaySeparate() {
        assertNull(UtteranceMerger.merge(row("a", 0, 1000), row("b", 1000, 2000, "speaker_2")))
        assertNull(UtteranceMerger.merge(row("a", 0, 1000), row("b", 2800, 3000)))
        assertNull(UtteranceMerger.merge(row("a", 0, 1000, "speaker_unknown"),
            row("b", 1000, 2000, "speaker_unknown")))
    }
    @Test fun conversationalPauseUnder1500msCanStayInOneSameSpeakerMessage() {
        assertNotNull(UtteranceMerger.merge(row("a", 0, 1000), row("b", 2400, 3000)))
    }

    @Test fun manualEditsAreNeverSwallowedByGrouping() {
        assertNull(UtteranceMerger.merge(row("a", 0, 1000).copy(editedText = "исправлено"), row("b", 1000, 2000)))
    }

    @Test fun punctuationStaysInMergedSameSpeakerTurn() {
        val joined = UtteranceMerger.merge(row("Готово.", 0, 1000), row("Дальше", 1900, 2500))
        assertEquals("Готово. Дальше", joined?.rawText)
    }

    @Test fun overlapCandidatesCanBeJoinedOnlyWhenThePairMatches() {
        val candidates = row("а", 0, 1000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_2")
        val same = row("б", 1100, 2000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_2")
        val changed = row("в", 1100, 2000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_3")
        assertEquals("а б", UtteranceMerger.merge(candidates, same)?.rawText)
        assertNull(UtteranceMerger.merge(candidates, changed))
    }

    @Test fun unknownOverlapWithoutTwoStableCandidatesIsNotMerged() {
        val first = row("а", 0, 1000, "speaker_unknown").copy(overlap = true)
        val next = row("б", 1100, 2000, "speaker_unknown").copy(overlap = true)
        assertNull(UtteranceMerger.merge(first, next))
    }

    @Test fun unknownOverlapWithTwoStableCandidatesHasBoundedMergeSpan() {
        val first = row("а", 0, 1000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_2")
        val withinLimit = row("б", 1100, 2000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_2")
        val tooLong = row("в", 31000, 32000, "speaker_unknown")
            .copy(overlap = true, speakerCandidates = "speaker_1|speaker_2")
        assertNotNull(UtteranceMerger.merge(first, withinLimit))
        assertNull(UtteranceMerger.merge(first, tooLong))
    }
}
