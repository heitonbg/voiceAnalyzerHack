package com.epicwalk.voiceanalyzer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ConversationRepository(private val context: Context, val dao: ScribeDao) {
    val audioDirectory = File(context.filesDir, "audio").apply { mkdirs() }

    suspend fun importAudio(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Аудиозапись"
        var conversation = Conversation(id, title)
        dao.insert(conversation)
        val partial = File(audioDirectory, "$id.importing")
        val destination = File(audioDirectory, "$id.audio")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Не удалось открыть выбранный файл")
            check(partial.length() > 0) { "Выбран пустой файл" }
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(partial.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("Android не смог прочитать длительность аудио")
            } finally { metadata.release() }
            check(duration > 0) { "В файле не найдено аудио" }
            check(partial.renameTo(destination)) { "Не удалось сохранить файл" }
            conversation = conversation.copy(audioPath = destination.absolutePath,
                durationMs = duration, status = "READY")
            dao.update(conversation)
            id
        } catch (error: Exception) {
            partial.delete()
            withContext(kotlinx.coroutines.NonCancellable) {
                dao.update(conversation.copy(status = "ERROR",
                    error = error.message ?: "Не удалось импортировать аудио"))
            }
            throw error
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val conversation = dao.conversation(id) ?: return@withContext
        if (conversation.status in listOf("RECORDING", "PROCESSING", "IMPORTING")) return@withContext
        audioDirectory.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.deleteRecursively() }
        dao.delete(id)
    }
}
