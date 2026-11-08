package com.epicwalk.voiceanalyzer.core

/** Joins model-emitted word tokens while keeping punctuation attached naturally. */
object TranscriptText {
    private val closingPunctuation = setOf(',', '.', ';', ':', '!', '?', '…', ')', ']', '}', '»', '”', '’')
    private val openingPunctuation = setOf('(', '[', '{', '«', '“', '‘')

    fun join(left: String, right: String): String {
        val before = left.trimEnd()
        val next = right.trimStart()
        if (before.isEmpty()) return next
        if (next.isEmpty()) return before
        val noSpace = next.first() in closingPunctuation || before.last() in openingPunctuation
        return before + (if (noSpace) "" else " ") + next
    }
}
