# Итерация 2 — аудио и базовое приложение

Реализованы Compose/Material 3 интерфейс, светлая/тёмная/системная тема, Dynamic Color,
Room-история, поиск по названию, переименование/удаление, воспроизведение локального аудио.
Импорт выбирается через SAF без широких storage permissions и копируется небольшим буфером.
Метаданные читаются Android MediaMetadataRetriever.

Диктофон: AudioRecord PCM16 mono 16 kHz, отдельная IO-корутина, foreground microphone service,
пауза/продолжение/остановка, длительность по записанным samples, реальный RMS.
WAV header checkpoint каждые 5 s; при следующем запуске после process death восстанавливается
размер data из файла, состояние отмечается INTERRUPTED.
Микрофон запрашивается через permission contract с rationale и настройками при постоянном отказе.
RecordingService не экспортируется. Запись не зависит от ML-очереди.

Фактические проверки на эмуляторе API 36 x86_64 / 4096 MB:

- assembleDebug и testDebugUnitTest — успешны.
- Исходный arithmetic test + два Wav16Test прошли.
- AudioSourcesTest: 2 tests passed, Gradle BUILD SUCCESSFUL in 27 s.
- Настоящий AudioRecord: запись, пауза без увеличения сохранённого времени,
  продолжение, остановка, READY в Room, проверка Android MediaExtractor 16 kHz mono.
- Импорт реального публичного WAV: длительность ≈11.29 s, hash оригинала и копии совпал.
- Главный экран и детали открывались на эмуляторе; полноценные screenshot/regression tests не проведены.

Ограничения этого этапа: ML-пайплайн ещё не подключён к пользовательскому экрану,
нет экспорта из UI, model manager, обработки MP3/M4A в PCM, анализа из UI.
На экранах это явно обозначено; демонстрационные расшифровки не показываются.
15 минут реального разговора, background/process death end-to-end и физический ARM пока не проверены.
SAF picker подключён; instrumentation проверил repository через file URI, а не полный системный picker сценарий.

Следующий этап: bounded MediaCodec decoder/resampler, подготовка моделей и настоящий ASR
для обоих источников, checkpoint и отмена. Далее глобальные embeddings/диаризация и LLM JNI.
