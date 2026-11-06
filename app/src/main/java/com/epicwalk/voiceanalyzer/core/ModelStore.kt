package com.epicwalk.voiceanalyzer.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object ModelGate { val mutex = Mutex() }

data class ModelSpec(val path: String, val sha256: String, val role: String)
data class ModelState(val installing: Boolean = false, val file: String = "",
    val completed: Int = 0, val asrReady: Boolean = false, val diarizationReady: Boolean = false,
    val llmReady: Boolean = false, val error: String? = null)

/** Only local assets or a user-selected SAF tree. No network transport. */
class ModelStore(private val context: Context) {
    val directory = File(context.filesDir, "models").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("verified-models", 0)
    private val mutable = MutableStateFlow(snapshot())
    val state = mutable.asStateFlow()

    fun file(path: String): File = File(directory, path).also {
        check(verified(specs.first { spec -> spec.path == path })) {
            "Модель $path не установлена или не прошла проверку. Выберите комплект в настройках."
        }
    }
    private fun verified(spec: ModelSpec): Boolean {
        val file = File(directory, spec.path)
        return file.isFile && file.length() > 0 &&
            preferences.getString(spec.path, null) == spec.sha256 &&
            preferences.getLong(spec.path + ".bytes", -1) == file.length()
    }
    private fun snapshot() = ModelState(
        asrReady = specs.filter { it.role == "ASR" }.all(::verified),
        diarizationReady = specs.filter { it.role == "DIAR" }.all(::verified),
        llmReady = specs.filter { it.role == "LLM" }.all(::verified)
    )

    suspend fun installTree(tree: Uri) = install { path ->
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.split('/')) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            id = context.contentResolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == part) { found = cursor.getString(0); break }
                found
            } ?: error("В выбранной папке отсутствует $path")
        }
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
            ?: error("Не удалось открыть $path")
    }

    suspend fun install(roles: Set<String> = setOf("ASR", "DIAR", "LLM"), open: (String) -> InputStream) = withContext(Dispatchers.IO) {
        ModelGate.mutex.withLock {
            mutable.value = snapshot().copy(installing = true)
            try {
                for ((index, spec) in specs.filter { it.role in roles }.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    mutable.value = snapshot().copy(installing = true, file = spec.path, completed = index)
                    if (verified(spec)) continue
                    val destination = File(directory, spec.path)
                    check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                    val partial = File(destination.parentFile, destination.name + ".part")
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        open(spec.path).use { source ->
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    check(directory.usableSpace > count + 8L * 1024 * 1024) { "Недостаточно места для моделей" }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        check(actual == spec.sha256) { "SHA-256 не совпал для ${spec.path}. Нужен проверенный комплект моделей." }
                        android.system.Os.rename(partial.absolutePath, destination.absolutePath)
                        preferences.edit().putString(spec.path, spec.sha256)
                            .putLong(spec.path + ".bytes", destination.length()).commit()
                    } finally { partial.delete() }
                }
                mutable.value = snapshot().copy(completed = specs.size)
            } catch (failure: Exception) {
                mutable.value = snapshot().copy(error = failure.message ?: "Установка прервана")
                throw failure
            }
        }
    }

    companion object {
        val specs = listOf(
            ModelSpec("encoder.int8.onnx", "0ec54e5f130a91c0f228d21795ede10b31c992a1f73e45dcd827e843a71a9b50", "ASR"),
            ModelSpec("decoder.onnx", "5d1cfef155d8e07f213bd2f3229b5f6c5c29355c7fa5c1b9eb62cb1925725254", "ASR"),
            ModelSpec("joiner.onnx", "fd1d02f45c2ad3d6b67cc149811ad794ab4b020ed49a0a9e2790a8619d1cddd8", "ASR"),
            ModelSpec("tokens.txt", "17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2", "ASR"),
            ModelSpec("silero_vad.onnx", "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", "ASR"),
            ModelSpec("titanet.onnx", "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e", "DIAR"),
            ModelSpec("sherpa-onnx-pyannote-segmentation-3-0/model.onnx", "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", "DIAR"),
            ModelSpec("Qwen3-0.6B-Q8_0.gguf", "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "LLM")
        )
    }
}
