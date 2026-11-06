package com.epicwalk.voiceanalyzer.core

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.epicwalk.voiceanalyzer.MainActivity
import com.epicwalk.voiceanalyzer.ScribeApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var lastNotification = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            mutable.value = mutable.value.copy(cancelling = true, stage = "Останавливаем после текущего вызова модели")
            task?.cancel()
            if (task == null) stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("id")
        if (id == null || task != null) { if (task == null) stopSelf(); return START_NOT_STICKY }
        val app = application as ScribeApplication
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Локальная обработка", NotificationManager.IMPORTANCE_LOW))
            mutable.value = ProcessingState(id, "Подготовка")
            startForeground(12, notification(mutable.value),
                if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            task = scope.launch {
                try {
                    TranscriptionPipeline(app).process(id) { state ->
                        if (!mutable.value.cancelling) mutable.value = state
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotification >= 1000) {
                            getSystemService(NotificationManager::class.java).notify(12, notification(mutable.value))
                            lastNotification = now
                        }
                    }
                } catch (_: CancellationException) {
                    // The pipeline persists cancellation after the native call returns.
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        app.database.dao().processingState(id, "ERROR", "Обработка остановлена", failure.message)
                    }
                } finally {
                    mutable.value = ProcessingState()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (failure: Exception) {
            scope.launch {
                app.database.dao().processingState(id, "ERROR", "Не удалось запустить фоновую обработку", failure.message)
                mutable.value = ProcessingState(); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: ProcessingState): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 3, Intent(this, ProcessingService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PrivateScribe · локальное распознавание").setContentText(state.stage)
            .setContentIntent(open).setOngoing(true)
            .setProgress(1000, if (state.totalMs > 0) (state.processedMs * 1000 / state.totalMs).coerceIn(0, 1000).toInt() else 0, state.totalMs <= 0)
            .addAction(Notification.Action.Builder(null, "Отменить", cancel).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        task?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val START = "scribe.process.START"
        const val CANCEL = "scribe.process.CANCEL"
        private const val CHANNEL = "processing"
        private val mutable = MutableStateFlow(ProcessingState())
        val state = mutable.asStateFlow()
    }
}
