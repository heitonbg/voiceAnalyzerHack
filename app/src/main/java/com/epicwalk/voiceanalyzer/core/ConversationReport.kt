package com.epicwalk.voiceanalyzer.core

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.epicwalk.voiceanalyzer.data.Conversation
import com.epicwalk.voiceanalyzer.data.Utterance
import java.io.File

/** Paginated report generated from actual final rows; audio is not attached. */
object ConversationReport {
    fun timestamp(ms: Long): String = "%02d:%02d:%02d.%03d".format(
        ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)

    fun pdf(conversation: Conversation, rows: List<Utterance>, destination: File,
        sections: List<Pair<String, String>> = emptyList(), name: (String) -> String) {
        require(conversation.status == "COMPLETE" && rows.all { !it.provisional })
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = android.graphics.Color.rgb(30, 42, 36) }
        var page: PdfDocument.Page? = null
        var number = 0
        var y = 0f
        fun nextPage() {
            page?.let(document::finishPage)
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
            y = 48f
            paint.typeface = Typeface.DEFAULT; paint.textSize = 9f
            page!!.canvas.drawText("PrivateScribe · ${conversation.title.take(65)}", 40f, 25f, paint)
            page!!.canvas.drawText(number.toString(), 540f, 815f, paint)
            paint.textSize = 11f
        }
        fun paragraph(value: String, bold: Boolean = false) {
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            for (line in value.split('\n')) {
                var offset = 0
                while (offset < line.length) {
                    if (page == null || y > 785) nextPage()
                    val count = paint.breakText(line, offset, line.length, true, 515f, null).coerceAtLeast(1)
                    val limit = offset + count
                    val space = if (limit < line.length) line.lastIndexOf(' ', limit - 1).takeIf { it > offset } else null
                    val end = space ?: limit
                    page!!.canvas.drawText(line, offset, end, 40f, y, paint)
                    y += 16f
                    offset = end
                    while (offset < line.length && line[offset] == ' ') offset++
                }
                y += 5f
            }
        }
        try {
            nextPage()
            paragraph(conversation.title, true)
            paragraph("Длительность: ${timestamp(conversation.durationMs)}")
            paragraph("Временные метки — оценённые интервалы речи. Пересечения и неопределённые голоса отмечены отдельно.")
            if (sections.isNotEmpty()) {
                paragraph("ИИ-анализ: выбранные цитаты", true)
                paragraph("Категории предложены локальной моделью и требуют проверки по источникам.")
                val labels = mapOf("summary" to "Краткое содержание", "topic" to "Тема", "decision" to "Решение",
                    "task" to "Задача", "deadline" to "Срок", "risk" to "Риск", "question" to "Вопрос", "agreement" to "Договорённость")
                sections.forEach { (kind, quote) -> paragraph(labels[kind] ?: kind, true); paragraph(quote) }
            }
            paragraph("Расшифровка", true)
            for (row in rows.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
                val speaker = row.editedSpeakerId ?: row.speakerId
                paragraph("${timestamp(row.startMs)} — ${timestamp(row.endMs)} · ${name(speaker)}" +
                    if (row.overlap) " · пересечение речи" else "", true)
                paragraph(row.editedText ?: row.rawText)
            }
            page?.let(document::finishPage); page = null
            destination.parentFile?.mkdirs()
            destination.outputStream().use(document::writeTo)
        } finally { document.close() }
    }
}
