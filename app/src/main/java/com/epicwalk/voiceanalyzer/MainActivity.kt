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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    var followTranscript by rememberSaveable { mutableStateOf(false) }
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
    var editRow by remember { mutableStateOf<Utterance?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var editSpeaker by rememberSaveable { mutableStateOf("") }
    var nameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var transcriptSearch by rememberSaveable { mutableStateOf("") }
    var speakerFilter by rememberSaveable(selectedId) { mutableStateOf("Все") }
    val namesVersion by model.speakerNamesVersion.collectAsStateWithLifecycle()
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) selectedId?.let { model.export(it, uri) }
    }
    val selected = conversations.firstOrNull { it.id == selectedId }
    val transcriptFlow = remember(selectedId) {
        selectedId?.let { model.utterances(it) } ?: flowOf(emptyList<Utterance>())
    }
    val transcript by transcriptFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val visibleTranscript = remember(transcript, transcriptSearch, speakerFilter) {
        transcript.filter { row ->
            val speaker = row.editedSpeakerId ?: row.speakerId
            val candidateIds = row.speakerCandidates?.split("|").orEmpty()
            (speakerFilter == "Все" || speaker == speakerFilter || speakerFilter in candidateIds) &&
                (row.editedText ?: row.rawText).contains(transcriptSearch, true)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.import(it) { id -> selectedId = id; settings = false; followTranscript = false } }
    }
    LaunchedEffect(selectedId, transcript.size, followTranscript) {
        if (followTranscript && processing.id == selectedId && transcript.isNotEmpty()) {
            withFrameNanos { }
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
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
            selected != null -> LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding), state = listState,
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
                        if (!models.asrReady) TextButton(onClick = { settings = true; selectedId = null }) { Text("Подготовка моделей") }
                    }
                }
                if (selected.status == "COMPLETE") item {
                    SectionCard {
                        Text("Готово к работе", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = {
                            model.shareReport(selected.id) { uri ->
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = android.content.ClipData.newRawUri("Отчёт PrivateScribe", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Поделиться отчётом"))
                            }
                        }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Поделиться PDF") }
                        Button(onClick = { exportFile.launch("PrivateScribe-${selected.id}.json") }) {
                            Icon(Icons.Rounded.IosShare, null); Spacer(Modifier.width(8.dp)); Text("Экспорт JSON")
                        }
                        TextButton(onClick = { model.repeat(selected.id) { selectedId = it } }, enabled = !busy && processing.id == null) {
                            Text("Обработать новую копию")
                        }
                        Text("Слишком много говорящих?", style = MaterialTheme.typography.titleSmall)
                        Text("Если число участников известно, укажите его для глобального сопоставления голосов.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3, 4, 5).forEach { count ->
                                OutlinedButton(enabled = processing.id == null, onClick = {
                                    model.setExpectedSpeakers(count); model.process(selected.id)
                                }) { Text("$count") }
                            }
                        }
                        Text("Участники · нажмите для переименования", style = MaterialTheme.typography.labelLarge)
                        transcript.flatMap { row ->
                            listOf(row.editedSpeakerId ?: row.speakerId) + row.speakerCandidates?.split("|").orEmpty()
                        }.distinct().forEach { speaker ->
                            TextButton(onClick = { nameTarget = speaker; nameDraft = model.speakerName(selected.id, speaker) }) {
                                Text(model.speakerName(selected.id, speaker))
                            }
                        }
                    }
                }
                if (selected.status == "COMPLETE") item { AnalysisPanel(model, selected, transcript) }
                item {
                    OutlinedTextField(transcriptSearch, { transcriptSearch = it }, Modifier.fillMaxWidth(),
                        label = { Text("Поиск в расшифровке") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    Text("Расшифровка · ${visibleTranscript.size} / ${transcript.size}", style = MaterialTheme.typography.titleLarge)
                    if (transcript.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { speakerFilter = "Все" }, label = { Text("Все") },
                            leadingIcon = if (speakerFilter == "Все") ({ Icon(Icons.Rounded.Check, null) }) else null)
                        transcript.flatMap { row ->
                            listOf(row.editedSpeakerId ?: row.speakerId) + row.speakerCandidates?.split("|").orEmpty()
                        }.distinct().forEach { speaker ->
                            AssistChip(onClick = { speakerFilter = speaker },
                                label = { Text(model.speakerName(selected.id, speaker)) },
                                leadingIcon = if (speakerFilter == speaker) ({ Icon(Icons.Rounded.Check, null) }) else null)
                        }
                    }
                    if (processing.id == selected.id) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Автопрокрутка к новым репликам", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(followTranscript, onCheckedChange = { followTranscript = it })
                        }
                        if (!followTranscript) TextButton(onClick = { followTranscript = true }) {
                            Icon(Icons.Rounded.South, null)
                            Spacer(Modifier.width(6.dp))
                            Text("К новым репликам")
                        }
                    }
                    Text(if (selected.status == "COMPLETE") "Глобальные участники. Времена — границы сегментов речи, не слов." else "Предварительные реплики. Говорящие уточняются после глобальной диаризации.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (transcript.isEmpty()) item {
                    SectionCard {
                        Icon(Icons.AutoMirrored.Rounded.TextSnippet, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (selected.status == "TRANSCRIBED") "Текстовых реплик не получено" else "Здесь появятся реплики",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (selected.status == "TRANSCRIBED") "Распознавание завершено. Проверьте аудио и наличие разборчивой речи."
                            else "Запустите распознавание. Результаты сохраняются по мере обработки.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visibleTranscript, key = { it.id }) { utterance ->
                    Card(onClick = { model.playFrom(selected.id, selected.audioPath, utterance.startMs) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${durationLabel(utterance.startMs)} — ${durationLabel(utterance.endMs)}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(utterance.editedText ?: utterance.rawText, style = MaterialTheme.typography.bodyLarge)
                                                        val speaker = utterance.editedSpeakerId ?: utterance.speakerId
                            val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
                            val candidateNames = if (utterance.editedSpeakerId == null)
                                utterance.speakerCandidates?.split("|")?.distinct()
                                    ?.joinToString(" + ") { model.speakerName(selected.id, it) }.orEmpty()
                            else ""
                            val speakerLabel = when {
                                candidateNames.isNotEmpty() -> "Пересечение · возможно: $candidateNames"
                                utterance.overlap -> "Пересечение · говорящий не установлен"
                                utterance.provisional -> "${model.speakerName(selected.id, speaker)} · предварительно"
                                else -> model.speakerName(selected.id, speaker)
                            }
                            Text(speakerLabel, style = MaterialTheme.typography.labelLarge,
                                color = palette[(speaker.hashCode() and Int.MAX_VALUE) % palette.size])
                            TextButton(onClick = { editRow = utterance; editText = utterance.editedText ?: utterance.rawText; editSpeaker = speaker }) {
                                Text("Исправить текст / говорящего")
                            }
                        }
                    }
                }
            }
            else -> HomeContent(conversations.filter { it.title.contains(search, true) },
                recording, busy, search, { search = it }, record,
                { import.launch(arrayOf("audio/*")) }, model::recordingAction,
                { selectedId = it; followTranscript = false }, contentPadding)
        }
    }
    val message by model.message.collectAsStateWithLifecycle()
    if (message != null) AlertDialog(onDismissRequest = { model.message.value = null },
        title = { Text("Экспорт") }, text = { Text(message.orEmpty()) },
        confirmButton = { TextButton(onClick = { model.message.value = null }) { Text("Готово") } })
    if (editRow != null) AlertDialog(onDismissRequest = { editRow = null },
        title = { Text("Исправление реплики") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editText, { editText = it }, label = { Text("Текст") }, maxLines = 8)
            OutlinedTextField(editSpeaker, { editSpeaker = it }, label = { Text("speaker_1 или speaker_unknown") })
            Text("Исходный текст ASR сохраняется отдельно.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = editText.isNotBlank() && Regex("speaker_([1-9][0-9]*|unknown)").matches(editSpeaker),
            onClick = { model.editUtterance(editRow!!.id, editText, editSpeaker); editRow = null }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editRow = null }) { Text("Отмена") } })
    if (nameTarget != null && selected != null) AlertDialog(onDismissRequest = { nameTarget = null },
        title = { Text("Имя участника") },
        text = { OutlinedTextField(nameDraft, { nameDraft = it }, label = { Text("Имя") }) },
        confirmButton = { TextButton(onClick = { model.nameSpeaker(selected.id, nameTarget!!, nameDraft); nameTarget = null }) { Text("Сохранить") } })
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
                Text("Из аудио —\nв разговор.", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                Text("Загрузите встречу или интервью.\nГолоса, реплики и время — на устройстве.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            if (recording.id != null) RecorderPanel(recording, control)
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = import, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(10.dp)); Text("Загрузить аудио", fontSize = 16.sp)
                }
                Text("WAV, MP3, M4A · обработка блоками", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = record, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(10.dp)); Text("Записать разговор", fontSize = 16.sp)
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
    val expected by model.expectedSpeakers.collectAsStateWithLifecycle()
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
                if (models.error != null) Button(onClick = { model.prepareModels() }, enabled = !models.installing) {
                    Text("Повторить подготовку")
                }
                Text("Все модели встроены в APK. При первом запуске автоматически копируются и проверяются по SHA-256. Интернет не требуется.",
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
                Text("PrivateScribe · полностью локальный режим", style = MaterialTheme.typography.labelMedium)
                Text("Ожидаемое число участников: ${if (expected == 0) "автоматически" else expected}")
                Row {
                    TextButton(onClick = { model.setExpectedSpeakers((expected - 1).coerceAtLeast(0)) }) { Text("−") }
                    TextButton(onClick = { model.setExpectedSpeakers((expected + 1).coerceAtMost(32)) }) { Text("+") }
                    TextButton(onClick = { model.setExpectedSpeakers(0) }) { Text("Авто") }
                }
                Text("Параметр применяется при следующей обработке. Пересекающаяся речь может остаться без определённого участника.",
                    style = MaterialTheme.typography.bodySmall)
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
