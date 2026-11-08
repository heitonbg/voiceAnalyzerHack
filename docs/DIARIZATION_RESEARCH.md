# Диаризация: проверка профессиональных подходов на PrivateScribe

Дата: 2026-11-07. Production остаётся v25 (GigaAM 2 потока, diarizer 1 поток).

## Архитектура и источники

- `BoundedDiarization.analyze` вызывает `OfflineSpeakerDiarization.process(samples)` отдельно для каждого блока до 30 секунд, с контекстом по 1 секунде на краях. Это независимые вызовы полной native diarization, а не только внутренние окна Pyannote. Возвращённые native speaker labels локальны вызову.
- Для каждого чистого span длительностью не менее 0.6 секунды извлекается отдельный TitaNet embedding (до 3 секунд). Исходные vectors, localKey, интервалы и overlap сохраняются в checkpoint; `GlobalSpeakers.assign` нормирует vectors и связывает дорожки между блоками. Ключ localKey включает номер 30-секундного блока. ASR слова также сохраняются отдельно.
- Native wrapper использует Pyannote segmentation 3.0 + TitaNet + auto FastClustering, но из Kotlin API возвращает уже сегменты с локальными speaker labels. Raw logits и дорожки до native clustering в checkpoint отсутствуют. Поэтому текущий файл не позволяет независимо измерить pre-clustering Pyannote.
- [pyannote-audio](https://github.com/pyannote/pyannote-audio) предоставляет segmentation, overlap, embeddings и полные pipelines; Community-1 нельзя объявлять Android-совместимой заменой существующего ONNX без конверсии и runtime измерений.
- [VBx](https://github.com/BUTSpeechFIT/VBx) использует AHC и VB-HMM поверх x-vectors с подготовленной моделью. TitaNet-векторы и cosine-centroids приложения не являются готовыми входами этого recipe. Перенос HMM без сопоставимой модели/калибровки не обоснован.
- [NeMo](https://github.com/NVIDIA-NeMo/Speech/tree/main/examples/speaker_tasks/diarization) предлагает multiscale embeddings. Здесь дополнительные короткие окна увеличат число native вызовов и требуют отдельной A/B оценки RTF.
- [SpeechBrain AMI](https://github.com/speechbrain/speechbrain/tree/develop/recipes/AMI/Diarization) — reference recipe и оценка на AMI, не готовый Android runtime.
- [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html) документирует Pyannote + TitaNet и 3D-Speaker ERes2Net. В проекте сейчас нет совместимых весов ERes2Net; одно и то же публичное 56.86 s demo аудио и значения из документации не заменяют локальное сравнение на русской записи. В основной APK модель не добавлена.

## Измеренный replay без повторного ASR

`ReferenceEvaluationTest` читает сохранённые Android embeddings и слова, заново исполняет `GlobalSpeakers.assign`, `WordSpeakerPlanner` и `UtteranceMerger`. Instrumentation завершился `OK (1 test)`. Результат в `docs/results/reference-replay.json`.

| Конфигурация | Onset hits / 127 | Clusters | Unknown rows | Unknown duration |
|---|---:|---:|---:|---:|
| auto cosine 0.35 | 44 | 3 | 84 | 81,758 ms |
| auto cosine 0.40 | 45 | 4 | 83 | 80,998 ms |
| auto cosine 0.45 | 45 | 4 | 83 | 80,998 ms |
| auto cosine 0.50 | 45 | 5 | 83 | 80,998 ms |
| fixed K=4 | 44 | 4 | 80 | 80,878 ms |

Replay не включает точную production fallback-ветку для четырёх ASR chunks без native word timestamps, поэтому его 45/127 при auto 0.40 не надо приравнивать к полному v25 46/127. Все replay варианты сравнимы между собой, но ни один не демонстрирует устойчивого выигрыша. Порог 0.35 объединяет эталонных четырёх участников в три кластера; 0.50 уже даёт пять.

## Решение

Production v25 сохраняется: WER 14.31%, onset 46/127, 82 unknown/235, RTF 0.2008, 2 ASR / 1 diar CPU thread на x86_64 emulator 4 GB. В этой работе изменён только Android диагностический тест и сохранён его результат. Нет доказательства качества ERes2Net, VBx или multiscale на русской записи; включение их в APK было бы неподтверждённым. Независимой интервальной разметки нет, поэтому DER/JER/WDER не измерены. ARM64 runtime не проверен.

Следующий измеримый gate: получить RTTM для независимой русской многоголосой записи, затем на одинаковых Pyannote spans сравнить TitaNet и ERes2Net на Android, сохранив embedding cache и полный RTF. Требуется также raw segmentation API, если нужно отделить ошибку Pyannote от локального native clustering.
