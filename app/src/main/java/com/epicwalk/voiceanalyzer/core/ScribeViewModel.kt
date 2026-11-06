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
        if (!app.models.state.value.asrReady) { error.value = "Выберите локальный комплект моделей в настройках"; return }
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

    fun import(uri: Uri) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { app.startup.await(); app.repository.importAudio(uri) }
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
