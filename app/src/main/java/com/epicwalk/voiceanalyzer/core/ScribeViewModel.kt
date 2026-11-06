package com.epicwalk.voiceanalyzer.core

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.audio.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScribeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ScribeApplication
    val conversations = app.repository.dao.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    private val speakerPreferences = app.getSharedPreferences("speaker-names", 0)
    val speakerNamesVersion = MutableStateFlow(0)
    val expectedSpeakers = MutableStateFlow(app.getSharedPreferences("processing", 0).getInt("expected-speakers", 0))
    fun setExpectedSpeakers(count: Int) {
        require(count in 0..32)
        expectedSpeakers.value = count
        app.getSharedPreferences("processing", 0).edit().putInt("expected-speakers", count).apply()
    }
    fun speakerName(id: String, speaker: String): String =
        speakerPreferences.getString("$id/$speaker", null)
            ?: if (speaker == "speaker_unknown") "Говорящий не определён" else "Говорящий " + speaker.removePrefix("speaker_")
    fun nameSpeaker(id: String, speaker: String, name: String) {
        speakerPreferences.edit().putString("$id/$speaker", name.trim().take(100)).apply()
        speakerNamesVersion.value++
    }
    fun editUtterance(id: String, text: String, speaker: String) = viewModelScope.launch {
        if (text.isBlank() || !Regex("speaker_([1-9][0-9]*|unknown)").matches(speaker)) return@launch
        val item = app.repository.dao.utterance(id) ?: return@launch
        if (analysisProgress.value.isNotEmpty()) { error.value = "Дождитесь завершения анализа перед правкой"; return@launch }
        app.repository.dao.updateUtterance(item.copy(editedText = text.trim().take(10000), editedSpeakerId = speaker))
        withContext(Dispatchers.IO) { java.io.File(app.filesDir, "${item.conversationId}.analysis.json").delete() }
        if (analysisId.value == item.conversationId) analysis.value = null
    }
    val analysis = com.epicwalk.voiceanalyzer.llm.AnalysisService.result
    val analysisId = com.epicwalk.voiceanalyzer.llm.AnalysisService.id
    val analysisProgress = com.epicwalk.voiceanalyzer.llm.AnalysisService.progress
    fun loadAnalysis(id: String) = viewModelScope.launch {
        if (analysisProgress.value.isNotEmpty()) return@launch
        val saved = withContext(Dispatchers.IO) {
            val rows = app.database.dao().utterances(id).filter { it.sourcePass == "diarized" }
            com.epicwalk.voiceanalyzer.llm.AnalysisStore.read(app, id, rows)
        }
        if (analysisProgress.value.isEmpty()) { analysisId.value = id; analysis.value = saved }
    }
    fun analyse(id: String, question: String = "") {
        if (analysisProgress.value.isNotEmpty() || processing.value.id != null) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, com.epicwalk.voiceanalyzer.llm.AnalysisService::class.java)
                .putExtra("id", id).putExtra("question", question))
        } catch (failure: Exception) { error.value = failure.message }
    }
    fun cancelAnalysis() {
        app.startService(Intent(app, com.epicwalk.voiceanalyzer.llm.AnalysisService::class.java)
            .setAction(com.epicwalk.voiceanalyzer.llm.AnalysisService.CANCEL))
    }
    fun prepareModels() = viewModelScope.launch {
        try { app.models.install { app.assets.open(it) } }
        catch (failure: Exception) { error.value = failure.message }
    }
    fun export(id: String, destination: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val conversation = app.database.dao().conversation(id) ?: error("Разговор не найден")
                check(conversation.status == "COMPLETE") { "Сначала завершите диаризацию" }
                val text = TranscriptExport.encode(app.database.dao().utterances(id)
                    .filter { it.sourcePass == "diarized" }, conversation.durationMs)
                app.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(text) } ?: error("Не удалось открыть файл экспорта")
            }
            message.value = "JSON сохранён"
        } catch (failure: Exception) { error.value = failure.message ?: "Экспорт не удался" }
    }
    fun shareReport(id: String, shared: (Uri) -> Unit) = viewModelScope.launch {
        try {
            val uri = withContext(Dispatchers.IO) {
                val conversation = app.database.dao().conversation(id) ?: error("Разговор не найден")
                val rows = app.database.dao().utterances(id).filter { it.sourcePass == "diarized" }
                val file = java.io.File(app.cacheDir, "reports/$id.pdf")
                val report = com.epicwalk.voiceanalyzer.llm.AnalysisStore.read(app, id, rows)
                val sections = report?.facts?.map { fact ->
                    fact.kind to fact.ids.mapNotNull { source -> rows.firstOrNull { it.id == source } }
                        .joinToString("\n") { row -> "[${ConversationReport.timestamp(row.startMs)}] ${row.editedText ?: row.rawText}" }
                }.orEmpty()
                ConversationReport.pdf(conversation, rows, file, sections) { speakerName(id, it) }
                androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".reports", file)
            }
            shared(uri)
        } catch (failure: Exception) { error.value = failure.message ?: "Не удалось подготовить отчёт" }
    }
    fun repeat(id: String, opened: (String) -> Unit) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val next = app.repository.duplicate(id)
            opened(next)
            process(next)
        } catch (failure: Exception) { error.value = failure.message ?: "Повторная обработка не запущена" }
        finally { busy.value = false }
    }
    val recording = RecordingService.state
    val processing = ProcessingService.state
    val modelState = app.models.state
    fun utterances(id: String) = app.repository.dao.observeUtterances(id)
    fun installModels(uri: Uri) {
        if (app.models.state.value.installing) return
        viewModelScope.launch {
            try { app.models.installTree(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message ?: "Не удалось установить модели" }
        }
    }
    fun process(id: String) {
        if (!app.models.state.value.asrReady) { error.value = "Дождитесь подготовки встроенных моделей в настройках"; return }
        if (processing.value.id != null) { error.value = "Другая запись уже обрабатывается"; return }
        try {
            ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java)
                .setAction(ProcessingService.START).putExtra("id", id))
        } catch (failure: Exception) { error.value = failure.message ?: "Не удалось начать обработку" }
    }
    fun cancelProcessing() {
        app.startService(Intent(app, ProcessingService::class.java).setAction(ProcessingService.CANCEL))
    }
    fun playFrom(id: String, path: String, atMs: Long) {
        closePlayer()
        try {
            playbackId = id
            preparing = true
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener {
                    preparing = false
                    it.seekTo(atMs.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
                    it.start(); playing.value = true
                }
                setOnCompletionListener { playing.value = false }
                setOnErrorListener { _, _, _ -> error.value = "Не удалось воспроизвести аудио"; closePlayer(); true }
                prepareAsync()
            }
        } catch (failure: Exception) { closePlayer(); error.value = failure.message }
    }
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val playing = MutableStateFlow(false)
    private var player: MediaPlayer? = null
    private var playbackId: String? = null
    private var preparing = false
    fun dismissError() { error.value = null; RecordingService.dismissError() }
    private val preferences = app.getSharedPreferences("appearance", 0)
    val theme = MutableStateFlow(preferences.getInt("theme", 0))
    val dynamicColor = MutableStateFlow(preferences.getBoolean("dynamic", false))

    fun setTheme(value: Int) { theme.value = value; preferences.edit().putInt("theme", value).apply() }
    fun setDynamic(value: Boolean) { dynamicColor.value = value; preferences.edit().putBoolean("dynamic", value).apply() }

    fun import(uri: Uri, opened: (String) -> Unit = {}) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                app.startup.await()
                val id = app.repository.importAudio(uri)
                opened(id)
                app.modelPreparation.await()
                process(id)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message ?: "Не удалось импортировать файл" }
            finally { busy.value = false }
        }
    }

    fun startRecording() {
        try { ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java).setAction(RecordingService.START)) }
        catch (failure: Exception) { error.value = failure.message ?: "Не удалось начать запись" }
    }
    fun recordingAction(action: String) {
        try { app.startService(Intent(app, RecordingService::class.java).setAction(action)) }
        catch (failure: Exception) { error.value = failure.message ?: "Команда записи не выполнена" }
    }
    fun rename(id: String, title: String) = viewModelScope.launch {
        val item = app.repository.dao.conversation(id) ?: return@launch
        app.repository.dao.update(item.copy(title = title.trim().take(200).ifEmpty { item.title }))
    }
    fun delete(id: String) = viewModelScope.launch {
        if (analysisId.value == id && analysisProgress.value.isNotEmpty()) { error.value = "Сначала остановите анализ"; return@launch }
        if (playbackId == id) closePlayer()
        app.repository.remove(id)
    }
    fun togglePlayback(id: String, path: String) {
        if (playbackId == id && preparing) return
        if (playbackId == id && player != null) {
            if (playing.value) pausePlayback() else runCatching { player?.start(); playing.value = true }
                .onFailure { error.value = "Не удалось воспроизвести аудио" }
            return
        }
        closePlayer()
        try {
            playbackId = id
            preparing = true
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { preparing = false; it.start(); playing.value = true }
                setOnCompletionListener { playing.value = false }
                setOnErrorListener { _, _, _ -> error.value = "Android не смог воспроизвести файл"; closePlayer(); true }
                prepareAsync()
            }
        } catch (failure: Exception) {
            closePlayer()
            error.value = failure.message ?: "Файл недоступен для воспроизведения"
        }
    }
    fun pausePlayback() { runCatching { player?.pause() }; playing.value = false }
    private fun closePlayer() { player?.release(); player = null; playbackId = null; preparing = false; playing.value = false }
    override fun onCleared() { closePlayer(); super.onCleared() }
}

fun durationLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
