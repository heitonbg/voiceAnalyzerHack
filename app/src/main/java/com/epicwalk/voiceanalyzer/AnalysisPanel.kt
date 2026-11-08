package com.epicwalk.voiceanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epicwalk.voiceanalyzer.core.*
import com.epicwalk.voiceanalyzer.data.*

@Composable
fun AnalysisPanel(model: ScribeViewModel, conversation: Conversation, rows: List<Utterance>) {
    val progress by model.analysisProgress.collectAsStateWithLifecycle()
    val result by model.analysis.collectAsStateWithLifecycle()
    val resultId by model.analysisId.collectAsStateWithLifecycle()
    var question by rememberSaveable(conversation.id) { mutableStateOf("") }
    var pendingQuestion by rememberSaveable(conversation.id) { mutableStateOf("") }
    var answerDialog by rememberSaveable(conversation.id) { mutableStateOf(false) }
    LaunchedEffect(conversation.id, rows.hashCode()) { model.loadAnalysis(conversation.id) }
    val analysisError by com.epicwalk.voiceanalyzer.llm.AnalysisService.error.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ИИ-анализ · Qwen3", style = MaterialTheme.typography.titleLarge)
            if (resultId == conversation.id && analysisError != null) Text(analysisError.orEmpty(), color = MaterialTheme.colorScheme.error)
            Text("Выбор важных цитат на устройстве. Категории предложены моделью — сверяйте их с источниками.",
                style = MaterialTheme.typography.bodySmall)
            if (progress.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress)
                TextButton(onClick = model::cancelAnalysis) { Text("Отменить анализ") }
            } else {
                Button(onClick = { model.analyse(conversation.id) }) { Text("Проанализировать разговор") }
                OutlinedTextField(question, { question = it.take(400) }, Modifier.fillMaxWidth(), label = { Text("Вопрос по разговору") })
                TextButton(onClick = {
                    pendingQuestion = question.trim()
                    answerDialog = true
                    model.analyse(conversation.id, pendingQuestion)
                }, enabled = question.isNotBlank()) { Text("Спросить Qwen") }
            }
            if (resultId == conversation.id) result?.let { analysis ->
                if (analysis.facts.isEmpty()) Text("Подходящих цитат не найдено.")
                val labels = mapOf("summary" to "Краткое содержание", "topic" to "Тема", "decision" to "Решение",
                    "task" to "Задача", "deadline" to "Срок", "risk" to "Риск", "question" to "Открытый вопрос",
                    "agreement" to "Договорённость", "answer" to "Источник ответа")
                analysis.facts.forEach { fact ->
                    Text(labels[fact.kind] ?: fact.kind, style = MaterialTheme.typography.titleSmall)
                    fact.ids.forEach { id ->
                        rows.firstOrNull { it.id == id }?.let { row ->
                            TextButton(onClick = { model.playFrom(conversation.id, conversation.audioPath, row.startMs) }) {
                                Text("${durationLabel(row.startMs)} · ${row.editedText ?: row.rawText}")
                            }
                        }
                    }
                }
                Text("Локальный анализ: ${analysis.elapsedMs / 1000} с", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (answerDialog) AlertDialog(
        onDismissRequest = { answerDialog = false },
        title = { Text("Ответ Qwen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pendingQuestion, style = MaterialTheme.typography.titleSmall)
                if (progress.isNotEmpty() && result?.question != pendingQuestion) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Qwen анализирует расшифровку на устройстве…")
                } else if (resultId == conversation.id && result?.question == pendingQuestion) {
                    val answer = result
                    if (answer?.facts.isNullOrEmpty()) Text("В расшифровке не нашлось подходящей цитаты.")
                    answer?.facts?.forEach { fact ->
                        fact.ids.forEach { id ->
                            rows.firstOrNull { it.id == id }?.let { row ->
                                TextButton(onClick = {
                                    answerDialog = false
                                    model.playFrom(conversation.id, conversation.audioPath, row.startMs)
                                }) {
                                    Text("${durationLabel(row.startMs)} · ${row.editedText ?: row.rawText}")
                                }
                            }
                        }
                    }
                } else if (analysisError != null) {
                    Text(analysisError.orEmpty(), color = MaterialTheme.colorScheme.error)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Подготовка локальной модели…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { answerDialog = false }) { Text("Закрыть") }
        }
    )
}
