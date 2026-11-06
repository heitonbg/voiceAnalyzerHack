package com.epicwalk.voiceanalyzer.llm

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.epicwalk.voiceanalyzer.MainActivity
import com.epicwalk.voiceanalyzer.ScribeApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            progress.value = "Останавливаем после текущего вызова модели"
            task?.cancel()
            if (task == null) { progress.value = ""; stopSelf() }
            return START_NOT_STICKY
        }
        val conversation = intent?.getStringExtra("id")
        if (conversation == null || task != null) { if (task == null) stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("analysis", "Локальный ИИ-анализ", NotificationManager.IMPORTANCE_LOW))
            id.value = conversation; result.value = null; error.value = null
            progress.value = "Загрузка Qwen3 · CPU"
            startForeground(13, notification(), if (Build.VERSION.SDK_INT >= 35)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            task = scope.launch {
                try {
                    result.value = LocalAnalysis(application as ScribeApplication).run(conversation,
                        intent.getStringExtra("question").orEmpty()) { done, total ->
                        progress.value = "Проанализировано фрагментов: $done / $total"
                        getSystemService(NotificationManager::class.java).notify(13, notification())
                    }
                } catch (_: CancellationException) { }
                catch (failure: Exception) { error.value = failure.message ?: "Ошибка локального анализа" }
                finally { progress.value = ""; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        } catch (failure: Exception) {
            error.value = failure.message; progress.value = ""; stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7, Intent(this, AnalysisService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "analysis").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PrivateScribe · Qwen3 на устройстве").setContentText(progress.value)
            .setContentIntent(open).setOngoing(true).setProgress(0, 0, true)
            .addAction(Notification.Action.Builder(null, "Отменить", stop).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val CANCEL = "scribe.analysis.CANCEL"
        val result = MutableStateFlow<AnalysisResult?>(null)
        val id = MutableStateFlow<String?>(null)
        val progress = MutableStateFlow("")
        val error = MutableStateFlow<String?>(null)
    }
}
