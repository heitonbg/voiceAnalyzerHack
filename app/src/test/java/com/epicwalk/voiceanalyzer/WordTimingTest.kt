package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.asr.*
import com.epicwalk.voiceanalyzer.diarization.*
import org.junit.Assert.*
import org.junit.Test

class WordTimingTest {
    @Test fun characterTokensProduceWholeWords() {
        val result = TokenTiming.decode("браузера нет", arrayOf("б", "р", "а", "у", "з", "е", "р", "а", " ", "н", "е", "т"),
            FloatArray(12) { it * 0.08f }, 32000)
        assertEquals(listOf("браузера", "нет"), result.words.map { it.text })
        assertTrue(result.words.all { it.endSample > it.startSample })
    }
    @Test fun bpePunctuationIsPreservedWithoutInventingText() {
        val result = TokenTiming.decode("Да, готово.", arrayOf("▁Да", ",", "▁готов", "о", "."),
            floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f, 0.7f), 16000)
        assertEquals(listOf("Да,", "готово."), result.words.map { it.text })
    }
    @Test fun unavailableOrInconsistentTimingKeepsOriginalText() {
        assertTrue(TokenTiming.decode("исходный текст", arrayOf("другой"), floatArrayOf(0.1f), 16000).words.isEmpty())
        assertEquals("исходный текст", TokenTiming.decode("исходный текст", emptyArray(), floatArrayOf(), 16000).text)
    }
    @Test fun speakerBoundaryInsideWordCannotSplitWord() {
        val words = listOf(TimedWord("браузера", 0, 8000), TimedWord("нет", 9000, 12000))
        val voices = listOf(VoiceTurn(0, 6400, null, speakerId = "speaker_1"),
            VoiceTurn(6400, 16000, null, speakerId = "speaker_2"))
        val rows = WordSpeakerPlanner.plan(words, 0, 0, 16000, voices)
        assertEquals(listOf("браузера", "нет"), rows.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2"), rows.map { it.speakerId })
    }
    @Test fun contextWordsAreExcludedByGlobalCoreStart() {
        val words = listOf(TimedWord("раньше", 0, 1000), TimedWord("теперь", 5000, 8000))
        val rows = WordSpeakerPlanner.plan(words, 10000, 14000, 20000,
            listOf(VoiceTurn(0, 30000, null, speakerId = "speaker_1")))
        assertEquals("теперь", rows.single().text)
        assertEquals(15000L, rows.single().startSample)
    }

    @Test fun punctuationIsKeptWhileSameSpeakerSentencesJoinIntoOneTurn() {
        val continuous = listOf(VoiceTurn(0, 40000, null, speakerId = "speaker_1"))
        val sameTurn = WordSpeakerPlanner.plan(
            listOf(TimedWord("Готово.", 0, 1600), TimedWord("Дальше", 4000, 8000),
                TimedWord("Да,", 12000, 13600), TimedWord("точно.", 14400, 18000)),
            0, 0, 25000, continuous)
        assertEquals(1, sameTurn.size)
        assertEquals("Готово. Дальше Да, точно.", sameTurn.single().text)
    }

    @Test fun sameSpeakerPhraseBridgesNaturalPauseUpTo1500ms() {
        val voice = listOf(VoiceTurn(0, 50000, null, speakerId = "speaker_1"))
        val joined = WordSpeakerPlanner.plan(
            listOf(TimedWord("Слушай", 0, 12000), TimedWord("дальше", 33200, 40000)),
            0, 0, 50000, voice)
        assertEquals(1, joined.size)
        assertEquals("Слушай дальше", joined.single().text)
    }

    @Test fun unknownWordsWithoutSpeakerAlternativesDoNotBridgeLongPauses() {
        val unknown = listOf(VoiceTurn(0, 40000, null, speakerId = "speaker_unknown"))
        val phrases = WordSpeakerPlanner.plan(
            listOf(TimedWord("первая", 0, 8000), TimedWord("вторая", 24000, 32000)),
            0, 0, 40000, unknown)
        assertEquals(2, phrases.size)
        assertEquals(listOf("первая", "вторая"), phrases.map { it.text })
    }

    @Test fun overlapWithOnlyOneDetectedLaneRemainsUnknownInsteadOfGuessing() {
        val voices = listOf(VoiceTurn(0, 16000, null, overlap = true, speakerId = "speaker_1"))
        val phrase = WordSpeakerPlanner.plan(
            listOf(TimedWord("возможно", 1000, 9000)), 0, 0, 16000, voices).single()
        assertTrue(phrase.overlap)
        assertEquals("speaker_unknown", phrase.speakerId)
    }

    @Test fun unknownOverlapWithStableAlternativesMayJoinWithinBoundedSpan() {
        val overlap = listOf(
            VoiceTurn(0, 40000, null, overlap = true, speakerId = "speaker_1"),
            VoiceTurn(0, 40000, null, overlap = true, speakerId = "speaker_2")
        )
        val phrases = WordSpeakerPlanner.plan(
            listOf(TimedWord("да", 0, 8000), TimedWord("слышу", 20000, 28000)),
            0, 0, 40000, overlap)
        assertEquals(1, phrases.size)
        assertEquals("speaker_unknown", phrases.single().speakerId)
        assertEquals(setOf("speaker_1", "speaker_2"), phrases.single().candidateSpeakerIds.toSet())
        assertEquals("да слышу", phrases.single().text)
    }

    @Test fun punctuationOnlyTokenAttachesToThePreviousWord() {
        val voice = listOf(VoiceTurn(0, 50000, null, speakerId = "speaker_1"))
        val joined = WordSpeakerPlanner.plan(
            listOf(TimedWord("Готово", 0, 12000), TimedWord(",", 12000, 12400),
                TimedWord("дальше.", 13000, 20000)),
            0, 0, 50000, voice)
        assertEquals(1, joined.size)
        assertEquals("Готово, дальше.", joined.single().text)
    }

    @Test fun overlappedWordKeepsBothGlobalSpeakerCandidates() {
        val words = listOf(TimedWord("да", 1000, 5000))
        val voices = listOf(
            VoiceTurn(0, 6000, null, overlap = true, speakerId = "speaker_1"),
            VoiceTurn(0, 6000, null, overlap = true, speakerId = "speaker_2")
        )
        val phrase = WordSpeakerPlanner.plan(words, 0, 0, 6000, voices).single()
        assertTrue(phrase.overlap)
        assertEquals("speaker_unknown", phrase.speakerId)
        assertEquals(setOf("speaker_1", "speaker_2"), phrase.candidateSpeakerIds.toSet())
    }
}
