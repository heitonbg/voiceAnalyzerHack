# Итерация 3 — настоящий ASR в приложении

Фактический integration test TranscriptionPipelineTest прошёл на эмуляторе API 36 x86_64:
BUILD SUCCESSFUL in 21 s, 1 test passed. Также прошли 5 JVM tests
(исходный arithmetic, 2 WAV, 2 resampler).

Реализовано:

- ModelStore: выбор локальной SAF-папки, потоковое копирование, фиксированные SHA-256 всех 8 файлов.
- Последовательный доступ к тяжёлым моделям через Mutex.
- MediaExtractor/MediaCodec → mono → stateful windowed-sinc resampler → PCM16 16 kHz spool.
- Raw PCM/WAV bypass без попытки запускать отсутствующий codec для audio/raw.
- Ограниченные PCM-буферы и нативные ASR-вызовы до 15 s речи.
- Silero VAD и GigaAM v3 RNNT CPU threads=2, настоящий текст сохраняется в Room по репликам.
- Обработка обоих источников через один pipeline, foreground service с уведомлением.
- Отмена после текущего нативного вызова, освобождение native handles в finally.
- Повтор после отмены: SHA исходника/checkpoint PCM, существующие реплики не перезаписываются.
- UI прогресса по обработанному времени текущего этапа, реальные реплики и переход к аудио.
- speaker_unknown / provisional=true: диаризация ещё не подключена, это явно указано.

Что проверял integration test: установка настоящих ASR-весов из тестовых assets с SHA,
импорт публичного WAV, Android decoder, отмена после 1 s продвижения VAD, CANCELLED в Room,
повтор с сохранённым PCM и настоящий русский ASR, 11290 ms длительности и допустимые intervals.

Ресемплер: 8/16/22.05/44.1/48 kHz; инвариант waveform и sample count при блоках 137/4096,
ошибка 1 kHz в passband <0.005 и RMS alias для 12 kHz →16 kHz <0.005.
Это DSP unit tests, не измерение качества ASR на перекодированных MP3/M4A.

Ограничения: интеграция проверена пока на WAV; MP3/M4A кодируются через общий Android path,
но отдельные прогоны ещё не выполнены. Timeline контейнеров с timestamp gaps, сменой формата,
encoder delay/padding требует отдельной проверки. Не объявлять их точность подтверждённой.
Нет финальных speakers, экспорта из UI, JNI LLM, 15-minute и physical ARM acceptance.
Model manager устанавливает только подготовленный комплект, ничего не скачивает из приложения.
