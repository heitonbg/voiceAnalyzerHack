package com.epicwalk.voiceanalyzer.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.IBinder
import android.os.Build
import androidx.core.content.ContextCompat
import com.epicwalk.voiceanalyzer.MainActivity
import com.epicwalk.voiceanalyzer.ScribeApplication
import com.epicwalk.voiceanalyzer.data.Conversation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class RecordingState(val id: String? = null, val paused: Boolean = false,
    val durationMs: Long = 0, val level: Float = 0f, val error: String? = null)

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (job == null) start()
            PAUSE -> { paused.set(true); mutableState.value = mutableState.value.copy(paused = true, level = 0f) }
            RESUME -> { paused.set(false); mutableState.value = mutableState.value.copy(paused = false) }
            STOP -> running.set(false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = RecordingState(error = "Нет разрешения на микрофон")
            stopSelf(); return
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Запись разговоров", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("PrivateScribe записывает разговор")
                .setContentText("Аудио сохраняется только на устройстве")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
            startForeground(11, notification, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            running.set(true)
            job = scope.launch { capture() }
        } catch (error: Exception) {
            mutableState.value = RecordingState(error = error.message ?: "Не удалось начать запись")
            stopSelf()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun capture() {
        val app = application as ScribeApplication
        var conversation: Conversation? = null
        var recorder: AudioRecord? = null
        var wave: RandomAccessFile? = null
        var samples = 0L
        var failure: String? = null
        try {
            app.startup.await()
            val id = UUID.randomUUID().toString()
            val file = File(app.repository.audioDirectory, "$id.wav")
            conversation = Conversation(id, "Запись ${java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date())}",
                audioPath = file.absolutePath, status = "RECORDING")
            app.database.dao().insert(conversation)
            wave = RandomAccessFile(file, "rw")
            Wav16.header(wave, 0)
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Микрофон не поддерживает PCM 16 кГц" }
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон недоступен" }
            val pcm = ShortArray(1600)
            val bytes = ByteArray(3200)
            var savedAt = 0L
            mutableState.value = RecordingState(id)
            while (running.get()) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                check(count >= 0) { "Ошибка микрофона: $count" }
                if (count == 0) continue
                if (paused.get()) {
                    mutableState.value = mutableState.value.copy(paused = true, level = 0f)
                    continue
                }
                var energy = 0.0
                for (index in 0 until count) {
                    val sample = pcm[index].toInt()
                    bytes[index * 2] = sample.toByte()
                    bytes[index * 2 + 1] = (sample shr 8).toByte()
                    energy += sample.toDouble() * sample
                }
                wave.write(bytes, 0, count * 2)
                samples += count
                val duration = samples * 1000 / 16000
                mutableState.value = RecordingState(id, false, duration,
                    (sqrt(energy / count) / 32768).toFloat().coerceIn(0f, 1f))
                if (duration - savedAt >= 5000) {
                    Wav16.header(wave, samples)
                    app.database.dao().update(conversation.copy(durationMs = duration))
                    savedAt = duration
                }
            }
        } catch (error: Exception) {
            failure = if (error is CancellationException) "Запись прервана" else error.message ?: "Ошибка записи"
        } finally {
            running.set(false)
            runCatching { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder?.release()
            wave?.let { runCatching { Wav16.header(it, samples) }; runCatching { it.close() } }
            withContext(NonCancellable) {
                conversation?.let {
                    app.database.dao().update(it.copy(durationMs = samples * 1000 / 16000,
                        status = if (failure == null && samples > 0) "READY" else "ERROR",
                        error = failure ?: if (samples == 0L) "Запись пуста" else null))
                }
            }
            mutableState.value = RecordingState(error = failure)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val START = "scribe.record.START"
        const val PAUSE = "scribe.record.PAUSE"
        const val RESUME = "scribe.record.RESUME"
        const val STOP = "scribe.record.STOP"
        private const val CHANNEL = "recording"
        private val mutableState = MutableStateFlow(RecordingState())
        val state = mutableState.asStateFlow()
        fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }
    }
}
