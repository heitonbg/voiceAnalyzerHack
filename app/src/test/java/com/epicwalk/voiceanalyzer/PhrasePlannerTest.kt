package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.diarization.*
import org.junit.Assert.*
import org.junit.Test

class PhrasePlannerTest {
    private fun voice(start: Long, end: Long, speaker: String = "speaker_1") =
        VoiceTurn(start * 16000, end * 16000, floatArrayOf(1f, 0f), speakerId = speaker)

    @Test fun adjacentFragmentsOfOneVoiceFormOnePhrase() {
        val rows = PhrasePlanner.plan(listOf(voice(0, 3), voice(3, 4), voice(4, 8)),
            0, 10 * 16000, true) { _, to -> to }
        assertEquals(1, rows.size)
        assertEquals(8 * 16000L, rows.single().endSample)
    }
    @Test fun unfinishedPhraseWaitsForNextWindowAndKeepsOriginalStart() {
        val first = PhrasePlanner.plan(listOf(voice(0, 30)), 0, 30 * 16000, false) { _, to -> to - 8000 }
        assertEquals(14 * 16000L + 8000, first.first().endSample)
        assertTrue(first.last().endSample < 30 * 16000L)
        val next = PhrasePlanner.plan(listOf(voice(0, 30), voice(30, 36)),
            first.last().endSample, 36 * 16000, true) { _, to -> to - 8000 }
        assertEquals(first.last().endSample, next.first().startSample)
        assertEquals(36 * 16000L, next.last().endSample)
        assertTrue(next.none { it.endSample == 30 * 16000L })
    }
    @Test fun differentSpeakersAndOverlapRemainSeparate() {
        val turns = listOf(voice(0, 4), voice(4, 8, "speaker_2"), voice(8, 9).copy(overlap = true))
        assertEquals(3, PhrasePlanner.plan(turns, 0, 9 * 16000, true) { _, to -> to }.size)
    }

    @Test fun overlappingSpeakerLanesBecomeOneAsrCoverageRegion() {
        val lanes = listOf(
            voice(0, 4, "speaker_1").copy(overlap = true),
            voice(2, 6, "speaker_2").copy(overlap = true)
        )
        val regions = PhrasePlanner.plan(lanes, 0, 6 * 16000, true, speechOnly = true) { _, to -> to }
        assertEquals(1, regions.size)
        assertEquals(0L, regions.single().startSample)
        assertEquals(6 * 16000L, regions.single().endSample)
        assertTrue(regions.single().overlap)
    }
    @Test fun briefOverlappedSegmentInTheSameTurnInheritsItsContinuousSpeaker() {
        val turns = listOf(
            voice(0, 4),
            VoiceTurn(4 * 16000L, 4 * 16000L + 3200, null, overlap = true),
            VoiceTurn(4 * 16000L + 3200, 8 * 16000L, floatArrayOf(1f, 0f))
        )
        val assigned = GlobalSpeakers.assign(turns)
        assertEquals("speaker_1", assigned[0].speakerId)
        assertEquals("speaker_1", assigned[1].speakerId)
        assertTrue(assigned[1].overlap)
        assertEquals("speaker_1", assigned[2].speakerId)
    }
    @Test fun briefOutlierDoesNotCreateAnotherParticipant() {
        val stable = listOf(voice(0, 4), voice(30, 34))
        val noise = VoiceTurn(5 * 16000L, 5 * 16000L + 1600, floatArrayOf(0f, 1f))
        val assigned = GlobalSpeakers.assign(stable + noise)
        assertEquals(setOf("speaker_1"), assigned.filter { it.speakerId != "speaker_unknown" }.map { it.speakerId }.toSet())
        assertEquals("speaker_unknown", assigned.first { it.startSample == noise.startSample }.speakerId)
    }
}
