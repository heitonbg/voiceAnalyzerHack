package com.epicwalk.voiceanalyzer

import com.epicwalk.voiceanalyzer.core.TranscriptText
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextTest {
    @Test fun closingPunctuationAttachesToPreviousWord() {
        assertEquals("Да,", TranscriptText.join("Да", ","))
        assertEquals("Да, готово.", TranscriptText.join("Да,", "готово."))
    }

    @Test fun quotesStayAdjacentToTheirWords() {
        assertEquals("Он сказал «привет!»", TranscriptText.join("Он сказал", "«привет!»"))
        assertEquals("«привет»", TranscriptText.join("«", "привет»"))
    }
}
