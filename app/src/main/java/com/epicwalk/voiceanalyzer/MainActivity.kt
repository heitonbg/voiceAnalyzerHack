package com.epicwalk.voiceanalyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epicwalk.voiceanalyzer.audio.RecordingService
import com.epicwalk.voiceanalyzer.audio.RecordingState
import com.epicwalk.voiceanalyzer.core.ScribeViewModel
import com.epicwalk.voiceanalyzer.core.durationLabel
import com.epicwalk.voiceanalyzer.data.Conversation
import com.epicwalk.voiceanalyzer.data.Utterance
import kotlinx.coroutines.flow.flowOf
import com.epicwalk.voiceanalyzer.ui.theme.VoiceAnalyzerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[ScribeViewModel::class.java] }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            android.app.AlertDialog.Builder(this).setTitle("Микрофон отключён")
                .setMessage("Разрешите доступ к микрофону в настройках приложения. Импорт аудио доступен без этого разрешения.")
                .setPositiveButton("Настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.setNegativeButton("Закрыть", null).show()
        } else model.error.value = "Для диктофона нужен доступ к микрофону"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            val dynamic by model.dynamicColor.collectAsStateWithLifecycle()
            VoiceAnalyzerTheme(
                darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() },
                dynamicColor = dynamic
            ) { PrivateScribe(model, ::requestRecording) }
        }
    }
    private fun requestRecording() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> beginRecording()
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                android.app.AlertDialog.Builder(this).setTitle("Запись разговора")
                    .setMessage("Микрофон нужен только для записи. Аудио сохраняется на вашем устройстве.")
                    .setPositiveButton("Продолжить") { _, _ -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                    .setNegativeButton("Отмена", null).show()
            else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun beginRecording() {
        model.pausePlayback()
        model.startRecording()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateScribe(model: ScribeViewModel, record: () -> Unit) {
    val conversations by model.conversations.collectAsStateWithLifecycle()
    val recording by model.recording.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val playing by model.playing.collectAsStateWithLifecycle()
    val processing by model.processing.collectAsStateWithLifecycle()
    val models by model.modelState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var titleDraft by rememberSaveable { mutableStateOf("") }
    val selected = conversations.firstOrNull { it.id == selectedId }
    val transcriptFlow = remember(selectedId) {
        selectedId?.let { model.utterances(it) } ?: flowOf(emptyList<Utterance>())
    }
    val transcript by transcriptFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.import(it) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.pausePlayback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(settings || selectedId != null) {
        model.pausePlayback(); selectedId = null; settings = false
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (settings) "Настройки" else if (selected != null) "Разговор" else "PrivateScribe",
                    fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (selectedId != null) IconButton(onClick = { model.pausePlayback(); selectedId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { titleDraft = selected.title; rename = true }) {
                            Icon(Icons.Rounded.Edit, "Переименовать")
                        }
                        if (selected.status !in listOf("RECORDING", "PROCESSING", "IMPORTING")) IconButton(onClick = { delete = true }) {
                            Icon(Icons.Rounded.DeleteOutline, "Удалить разговор")
                        }
                    } else Icon(Icons.Rounded.Lock, null, Modifier.padding(end = 24.dp).size(20.dp), MaterialTheme.colorScheme.primary)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = !settings, onClick = { settings = false; selectedId = null; model.pausePlayback() },
                    icon = { Icon(Icons.Rounded.GraphicEq, null) }, label = { Text("Разговоры") })
                NavigationBarItem(selected = settings, onClick = { settings = true; selectedId = null; model.pausePlayback() },
                    icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Настройки") })
            }
        }
    ) { padding ->
        val contentPadding = PaddingValues(24.dp, padding.calculateTopPadding() + 12.dp,
            24.dp, padding.calculateBottomPadding() + 24.dp)
        when {
            settings -> SettingsScreen(model, contentPadding)
            selected != null -> LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { Text(selected.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
                item { Text("${durationLabel(selected.durationMs)}  ·  ${dateLabel(selected.createdMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    SectionCard {
                        Icon(Icons.Rounded.AudioFile, null, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
                        Text(statusLabel(selected.status), style = MaterialTheme.typography.titleLarge)
                        Text(selected.error ?: "Оригинал аудио хранится локально.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected.status != "RECORDING" && selected.audioPath.isNotEmpty()) {
                            Button(onClick = { model.togglePlayback(selected.id, selected.audioPath) }) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(8.dp)); Text(if (playing) "Пауза" else "Прослушать")
                            }
                        }
                    }
                }
                if (processing.id == selected.id) item {
                    SectionCard {
                        Text(processing.stage, style = MaterialTheme.typography.titleMedium)
                        if (processing.totalMs > 0) {
                            LinearProgressIndicator(
                                progress = { (processing.processedMs.toFloat() / processing.totalMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text("${durationLabel(processing.processedMs)} из ${durationLabel(processing.totalMs)} аудио",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = model::cancelProcessing, enabled = !processing.cancelling) { Text("Отменить обработку") }
                    }
                }
                if (selected.status in listOf("READY", "ERROR", "CANCELLED", "INTERRUPTED") &&
                    selected.audioPath.isNotEmpty() && processing.id != selected.id) item {
                    SectionCard {
                        Text("Локальное распознавание", style = MaterialTheme.typography.titleLarge)
                        Text("GigaAM v3 · CPU · русский язык", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { model.process(selected.id) },
                            enabled = models.asrReady && !models.installing && processing.id == null) {
                            Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                            Text(if (selected.status == "READY") "Распознать речь" else "Продолжить обработку")
                        }
                        if (!models.asrReady) TextButton(onClick = { settings = true; selectedId = null }) { Text("Установить модели") }
                    }
                }
                item {
                    Text("Расшифровка · ${transcript.size}", style = MaterialTheme.typography.titleLarge)
                    Text("Говорящие пока не определены. Времена — интервалы VAD, а не точные границы слов.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (transcript.isEmpty()) item {
                    SectionCard {
                        Icon(Icons.AutoMirrored.Rounded.TextSnippet, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (selected.status == "TRANSCRIBED") "Текстовых реплик не получено" else "Здесь появятся реплики",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (selected.status == "TRANSCRIBED") "Распознавание завершено. Проверьте аудио и наличие разборчивой речи."
                            else "После установки моделей запустите распознавание. Результаты сохраняются по мере обработки.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(transcript, key = { it.id }) { utterance ->
                    Card(onClick = { model.playFrom(selected.id, selected.audioPath, utterance.startMs) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${durationLabel(utterance.startMs)} — ${durationLabel(utterance.endMs)}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(utterance.editedText ?: utterance.rawText, style = MaterialTheme.typography.bodyLarge)
                            Text("Говорящий не определён · нажмите, чтобы прослушать",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> HomeContent(conversations.filter { it.title.contains(search, true) },
                recording, busy, search, { search = it }, record,
                { import.launch(arrayOf("audio/*")) }, model::recordingAction,
                { selectedId = it }, contentPadding)
        }
    }
    val visibleError = error ?: recording.error
    if (visibleError != null) AlertDialog(
        onDismissRequest = { model.dismissError() },
        title = { Text("Не удалось выполнить действие") }, text = { Text(visibleError) },
        confirmButton = { TextButton(onClick = { model.dismissError() }) { Text("Понятно") } }
    )
    if (rename && selected != null) AlertDialog(onDismissRequest = { rename = false },
        title = { Text("Название разговора") },
        text = { OutlinedTextField(titleDraft, { titleDraft = it }, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { model.rename(selected.id, titleDraft); rename = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена") } })
    if (delete && selected != null) AlertDialog(onDismissRequest = { delete = false },
        title = { Text("Удалить разговор?") }, text = { Text("Аудиозапись и связанные данные будут удалены с устройства.") },
        confirmButton = { TextButton(onClick = { model.delete(selected.id); delete = false; selectedId = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } })
}

@Composable
private fun HomeContent(
    conversations: List<Conversation>, recording: RecordingState, busy: Boolean,
    search: String, onSearch: (String) -> Unit, record: () -> Unit, import: () -> Unit,
    control: (String) -> Unit, select: (String) -> Unit, padding: PaddingValues
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp)); Text("ТОЛЬКО НА УСТРОЙСТВЕ", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                }
                Text("Сохраните\nважное.", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                Text("Ваши разговоры. Ваше устройство.\nАудио остаётся у вас.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            if (recording.id != null) RecorderPanel(recording, control)
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = record, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(10.dp)); Text("Начать запись", fontSize = 16.sp)
                }
                FilledTonalButton(onClick = import, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(10.dp)); Text("Импортировать аудио", fontSize = 16.sp)
                }
            }
        }
        if (busy) item {
            SectionCard {
                Text("Сохраняем аудио", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Файл копируется в приватное хранилище.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Ваши разговоры", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(conversations.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(search, onSearch, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Поиск по названию") }, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
        }
        if (conversations.isEmpty()) item {
            SectionCard {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                Text(if (search.isEmpty()) "Начните с разговора" else "Ничего не найдено",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (search.isEmpty()) "Запишите встречу или выберите аудиофайл. Он появится здесь." else "Попробуйте другое название.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(conversations, key = { it.id }) { conversation ->
            Card(onClick = { select(conversation.id) }, shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(Icons.Rounded.GraphicEq, null, Modifier.padding(12.dp).size(24.dp), MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text("${dateLabel(conversation.createdMs)}  ·  ${durationLabel(conversation.durationMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(statusLabel(conversation.status), style = MaterialTheme.typography.labelMedium,
                            color = if (conversation.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecorderPanel(state: RecordingState, control: (String) -> Unit) {
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.level, state.durationMs) {
        levels.add(if (state.paused) 0f else state.level)
        while (levels.size > 48) levels.removeAt(0)
    }
    val color = MaterialTheme.colorScheme.primary
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, CircleShape))
            Text(if (state.paused) "НА ПАУЗЕ" else "ИДЁТ ЗАПИСЬ", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
        }
        Text(durationLabel(state.durationMs), fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light)
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val step = size.width / 48
            levels.forEachIndexed { index, level ->
                val height = (level * 5).coerceIn(0.04f, 1f) * size.height
                drawLine(color, androidx.compose.ui.geometry.Offset(index * step + step / 2, (size.height - height) / 2),
                    androidx.compose.ui.geometry.Offset(index * step + step / 2, (size.height + height) / 2),
                    strokeWidth = step * 0.4f, cap = StrokeCap.Round)
            }
        }
        Text("Аудио сохраняется на диск. Распознавание ещё не запущено.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { control(if (state.paused) RecordingService.RESUME else RecordingService.PAUSE) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                Spacer(Modifier.width(6.dp)); Text(if (state.paused) "Продолжить" else "Пауза")
            }
            Button(onClick = { control(RecordingService.STOP) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Готово")
            }
        }
    }
}

@Composable
private fun SettingsScreen(model: ScribeViewModel, padding: PaddingValues) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val dynamic by model.dynamicColor.collectAsStateWithLifecycle()
    val models by model.modelState.collectAsStateWithLifecycle()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::installModels)
    }
    var bytes by remember { mutableLongStateOf(0) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        bytes = withContext(Dispatchers.IO) { app.filesDir.walkTopDown().filter { it.isFile }.sumOf(File::length) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Как вам удобно", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        item {
            SectionCard {
                Text("Оформление", style = MaterialTheme.typography.titleLarge)
                listOf("Как в системе", "Светлая тема", "Тёмная тема").forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(theme == index, onClick = { model.setTheme(index) })
                        TextButton(onClick = { model.setTheme(index) }) { Text(label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Цвета устройства", Modifier.weight(1f))
                    Switch(dynamic, onCheckedChange = model::setDynamic)
                }
            }
        }
        item {
            SectionCard {
                Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Text("Локальное хранилище", style = MaterialTheme.typography.titleLarge)
                Text("%.1f МБ в приватных файлах".format(bytes / 1048576.0))
                Text("Проверенный локальный комплект", style = MaterialTheme.typography.titleMedium)
                Text("ASR: ${if (models.asrReady) "готов" else "не установлен"}\nДиаризация: ${if (models.diarizationReady) "веса установлены" else "не установлены"}\nLLM: ${if (models.llmReady) "веса установлены" else "не установлена"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (models.installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Проверяем SHA-256: ${models.file}", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { folder.launch(null) }, enabled = !models.installing) {
                    Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Выбрать папку моделей")
                }
                Text("Нужен подготовленный комплект PrivateScribe. Файлы копируются в приватное хранилище и проверяются по SHA-256. Интернет не используется.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                models.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SectionCard {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Text("Приватность", style = MaterialTheme.typography.titleLarge)
                Text("Приложение не запрашивает доступ к интернету. Записи хранятся в приватном каталоге. Резервное копирование отключено.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PrivateScribe · этап 2", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
private fun statusLabel(status: String): String = when (status) {
    "READY" -> "Аудио сохранено"
    "RECORDING" -> "Записывается"
    "IMPORTING" -> "Импортируется"
    "PROCESSING" -> "Обрабатывается"
    "INTERRUPTED" -> "Операция прервана"
    "ERROR" -> "Нужна проверка"
    "COMPLETE" -> "Расшифровка готова"
    "TRANSCRIBED" -> "Текст готов · говорящие не определены"
    "CANCELLED" -> "Обработка отменена"
    else -> status
}
private fun dateLabel(time: Long): String = SimpleDateFormat("dd MMM · HH:mm", Locale.forLanguageTag("ru")).format(Date(time))

@Preview(name = "Phone", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "Tablet", showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun PrivateScribeHomePreview() {
    VoiceAnalyzerTheme {
        HomeContent(emptyList(), RecordingState(), false, "", {}, {}, {}, {}, {}, PaddingValues(24.dp))
    }
}
