# PrivateScribe

Локальное Android-приложение для записи, транскрибации и анализа разговоров.

**Текущий этап: два источника аудио и история работают; ML проверен отдельно на Android,
но ещё не подключён к пользовательскому пайплайну. Это пока не готовое решение хакатона.**

## Работает в приложении

- Material 3 / Compose, светлая, тёмная и системная темы, Dynamic Color.
- Диктофон AudioRecord 16 kHz mono: start/pause/resume/stop, длительность, реальный RMS.
- Foreground microphone service; запись не ожидает ML.
- SAF выбор аудио без широких storage permissions; приватная копия и Android-метаданные.
- Room-история, поиск по названию, переименование, удаление, локальное воспроизведение.
- Пометка прерванных операций и восстановление WAV-header после process death.
- В manifest APK нет INTERNET, резервное копирование данных отключено.

## Подтверждённый настоящий ML

На Android Emulator API 36, native x86_64, запущенном с 4096 MB RAM:

- Silero VAD на реальном WAV.
- GigaAM v3 RNNT INT8: русское аудио 11.29 s, две реплики.
- Pyannote segmentation 3.0 + TitaNet Small: реальные speaker segments.
- Калибровка automatic clustering и режим заданного числа участников.
- Qwen3 0.6B Q8_0 / llama.cpp: отдельный Android CPU executable над реальным ASR-текстом.
  **Это ещё не JNI-интеграция в приложение.**

Сохранённые результаты: [JSON реального inference](docs/results/real-transcript.json),
[отчёт итерации 1](docs/ITERATION_1.md), [отчёт итерации 2](docs/ITERATION_2.md).
ASR inference 588 ms без загрузки модели на этом коротком входе.
Это не оценка скорости Snapdragon и не WER.
Порог кластеризации 0.5 дал 8 голосов вместо 4; 0.8–0.9 дал 4 на одном китайском примере.
Это не DER и не гарантия корректного automatic mode на русских встречах.

## Сборка и установка

Android Studio, JBR 21+, SDK 36. AGP 9.0.1 использует встроенную поддержку Kotlin.
KSP 2.3.13 генерирует Room-код. minSdk 29 (Android 10).
В терминале нужен действительный JAVA_HOME. На проверяемой машине:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
~~~

APK: app/build/outputs/apk/debug/app-debug.apk.
Установить через Android Studio Run или adb install -r.
Микрофон запрашивается только при начале записи; импорт работает без его разрешения.

Требуется app/libs/sherpa-onnx-2.0.1.aar из
[официального релиза](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1).
SHA256: a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7.
Он загружен в рабочем каталоге отдельно и исключён из git вместе с весами.
Обе нужные ABI AAR проверены; APK ограничен arm64-v8a и x86_64.

## Проверки

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.epicwalk.voiceanalyzer.AudioSourcesTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.epicwalk.voiceanalyzer.ModelSmokeTest' --console=plain
~~~

Последние выполненные проверки: 3 JVM tests passed; 2 AudioSourcesTest passed;
4 ModelSmokeTest passed. Последний класс проверяет запуск моделей и один калибровочный пример,
а не общую точность. Артефакты ML нужны только для model/audio instrumentation.
Тесты микрофона используют настоящий AudioRecord на эмуляторе.

Для model/audio tests положить в research/models:

- encoder.int8.onnx, decoder.onnx, joiner.onnx, tokens.txt и example.wav —
  [GigaAM v3 RNNT revision 0424d62](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16/tree/0424d62).
- silero_vad.onnx — asr-models release sherpa.
- titanet.onnx — nemo_en_titanet_small.onnx из speaker-recongition-models.
- four-speakers.wav — 0-four-speakers-zh.wav из speaker-segmentation-models.
- sherpa-onnx-pyannote-segmentation-3-0/model.onnx — распакованный архив того же релиза.

Variant API подключает отфильтрованные generated assets только в androidTest APK.
GGUF, архивы и upstream-скрипты в него не копируются.
Тест сам переносит файлы в приватное хранилище ограниченным буфером.
Простой adb push в external storage оказался неработоспособным для тестового процесса.
Gradle runner удаляет установленные пакеты/результаты после прогона; для сохранения JSON
использован прямой запуск instrumentation с последующим adb pull.

HTML: app/build/reports/androidTests/connected/debug/index.html.
[Стратегия и ограничения тестов](docs/testing.md), [протокол приёмки](docs/TEST_PROTOCOL.md).

## Документация

- [Архитектура и разделение ответственности](docs/ARCHITECTURE.md).
- [Совместимость, источники и лицензии моделей](docs/MODEL_COMPATIBILITY.md).
- [Фактические отчёты и JSON](docs/results/README.md).
- [Сценарий будущей видеодемонстрации](docs/TEST_PROTOCOL.md).

## Ещё не реализовано / не проверено

Нет пользовательской транскрибации, блочного MediaCodec decoder/resampler, глобальных
embeddings между блоками, JSON-экспорта из UI, model manager, JNI LLM и анализа/Q&A из UI.
Пользовательский экран честно обозначает отсутствие расшифровки.

Не проведены WER, DER, ошибка timestamps относительно эталона, 15-минутный разговор,
полная приёмка в авиарежиме, проверка утечек и физического Redmi Note 9 Pro.
PSS в речевых smoke JSON — снимок, не peak RAM.
LLM maxrss измерен отдельно в native executable, не для полного приложения.
Заглушек inference и заранее подготовленных результатов в пользовательском приложении нет.
