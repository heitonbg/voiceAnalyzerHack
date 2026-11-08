package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.diarization.ExperimentalGlobalClustering
import com.epicwalk.voiceanalyzer.diarization.VoiceTurn
import org.junit.Assert.*
import org.junit.Test

class ExperimentalGlobalClusteringTest {
    @Test fun handlesEmptyAndInvalidVectorsWithoutInventingSpeakers() {
        for (method in ExperimentalGlobalClustering.Method.entries) {
            val result = ExperimentalGlobalClustering.assign(listOf(
                VoiceTurn(0, 16000, floatArrayOf(Float.NaN, 0f)),
                VoiceTurn(16000, 32000, floatArrayOf(0f, 0f))), method)
            assertEquals(0, result.clusters)
            assertTrue(result.turns.all { it.speakerId == "speaker_unknown" })
        }
    }

    @Test fun distinctVoicesRemainDistinctIncludingVoiceAtEnd() {
        for (method in ExperimentalGlobalClustering.Method.entries) {
            val turns = (0 until 12).map { i ->
                val vector = when (i % 3) {
                    0 -> floatArrayOf(1f, 0f, 0f)
                    1 -> floatArrayOf(0f, 1f, 0f)
                    else -> floatArrayOf(0f, 0f, 1f)
                }
                VoiceTurn(i * 48000L, (i + 1) * 48000L, vector)
            }
            val result = ExperimentalGlobalClustering.assign(turns, method)
            assertEquals(3, result.clusters)
            assertEquals(result.turns[0].speakerId, result.turns[9].speakerId)
            assertNotEquals(result.turns[0].speakerId, result.turns[11].speakerId)
        }
    }

    @Test fun overlapIsNeverAssignedFromMixedEmbedding() {
        for (method in ExperimentalGlobalClustering.Method.entries) {
            val result = ExperimentalGlobalClustering.assign(listOf(
                VoiceTurn(0, 48000, floatArrayOf(1f, 0f)),
                VoiceTurn(48000, 96000, floatArrayOf(1f, 0f), overlap = true)), method)
            assertEquals("speaker_unknown", result.turns.last().speakerId)
        }
    }
}
