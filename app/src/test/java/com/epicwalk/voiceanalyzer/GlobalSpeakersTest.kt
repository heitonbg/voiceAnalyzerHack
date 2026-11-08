package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.diarization.GlobalSpeakers
import com.epicwalk.voiceanalyzer.diarization.VoiceTurn
import org.junit.Assert.*
import org.junit.Test

class GlobalSpeakersTest {
    @Test fun knownSpeakerCountCanSeparateSimilarVoicesBeforeIrreversibleMerge() {
        val first = floatArrayOf(1f, 0f)
        val second = floatArrayOf(0.6f, 0.8f)
        val turns = listOf(first, second, first, second).mapIndexed { index, vector ->
            VoiceTurn(index * 480000L, index * 480000L + 48000, vector)
        }
        val assigned = GlobalSpeakers.assign(turns, expectedSpeakers = 2)
        assertEquals(2, assigned.map { it.speakerId }.distinct().size)
        assertEquals(assigned[0].speakerId, assigned[2].speakerId)
        assertEquals(assigned[1].speakerId, assigned[3].speakerId)
        assertNotEquals(assigned[0].speakerId, assigned[1].speakerId)
        assertTrue(assigned.none { it.speakerId == "speaker_unknown" })
    }

    @Test fun simultaneousPyannoteLanesCannotBeMergedEvenWhenEmbeddingsAreSimilar() {
        val lane1 = 17L
        val lane2 = 18L
        val turns = listOf(
            VoiceTurn(0, 32000, floatArrayOf(1f, 0f), localSpeakerKey = lane1),
            VoiceTurn(32000, 64000, floatArrayOf(0.81f, 0.5864f), localSpeakerKey = lane2),
            VoiceTurn(32000, 40000, null, overlap = true, localSpeakerKey = lane1),
            VoiceTurn(32000, 40000, null, overlap = true, localSpeakerKey = lane2)
        )
        val assigned = GlobalSpeakers.assign(turns, expectedSpeakers = 1)
        val cleanIds = assigned.filter { !it.overlap }.map { it.speakerId }.distinct()
        assertEquals(2, cleanIds.size)
        assertNotEquals(cleanIds[0], cleanIds[1])
        assertEquals(cleanIds.toSet(), assigned.filter { it.overlap }.map { it.speakerId }.toSet())
    }

    @Test fun identicalEmbeddingsDoNotUndoCannotLinkDuringFinalAssignment() {
        val turns = listOf(
            VoiceTurn(0, 32000, floatArrayOf(1f, 0f), localSpeakerKey = 17),
            VoiceTurn(32000, 64000, floatArrayOf(1f, 0f), localSpeakerKey = 18),
            VoiceTurn(64000, 72000, null, overlap = true, localSpeakerKey = 17),
            VoiceTurn(64000, 72000, null, overlap = true, localSpeakerKey = 18)
        )
        val assigned = GlobalSpeakers.assign(turns, expectedSpeakers = 1)
        val clean = assigned.filter { !it.overlap }
        assertNotEquals("speaker_unknown", clean[0].speakerId)
        assertNotEquals(clean[0].speakerId, clean[1].speakerId)
        assertEquals(2, assigned.filter { it.overlap }.map { it.speakerId }.distinct().size)
    }

    @Test fun shortUnembeddedCleanSpanReusesItsWindowScopedPyannoteLaneIdentity() {
        val lane = 4L
        val turns = listOf(
            VoiceTurn(0, 48000, floatArrayOf(1f, 0f), localSpeakerKey = lane),
            VoiceTurn(48000, 56000, null, localSpeakerKey = lane)
        )
        val assigned = GlobalSpeakers.assign(turns, expectedSpeakers = 1)
        assertEquals(assigned[0].speakerId, assigned[1].speakerId)
        assertNotEquals("speaker_unknown", assigned[1].speakerId)
    }

    @Test fun overlappingLocalSpeakerLanesReuseGlobalIdsFromCleanPartsOfTheSameWindow() {
        val lane1 = 7L
        val lane2 = 8L
        val turns = listOf(
            VoiceTurn(0, 32000, floatArrayOf(1f, 0f), localSpeakerKey = lane1),
            VoiceTurn(32000, 64000, floatArrayOf(0.6f, 0.8f), localSpeakerKey = lane2),
            VoiceTurn(64000, 80000, null, overlap = true, localSpeakerKey = lane1),
            VoiceTurn(64000, 80000, null, overlap = true, localSpeakerKey = lane2)
        )
        val assigned = GlobalSpeakers.assign(turns, expectedSpeakers = 2)
        val overlapIds = assigned.filter { it.overlap }.map { it.speakerId }.toSet()
        assertEquals(2, overlapIds.size)
        assertEquals(assigned.first { it.localSpeakerKey == lane1 }.speakerId,
            assigned.first { it.overlap && it.localSpeakerKey == lane1 }.speakerId)
        assertEquals(assigned.first { it.localSpeakerKey == lane2 }.speakerId,
            assigned.first { it.overlap && it.localSpeakerKey == lane2 }.speakerId)
    }
}
