# PrivateScribe — исследование совместимости (итерация 1)

Дата: 2026-11-06. «Поддерживается runtime» не означает «проверено в PrivateScribe».
Инференс выполняется только на Android; рабочая станция используется для сборки и загрузки публичных весов.

| Компонент | Веса / формат | Лицензия | arm64-v8a | x86_64 | Память и статус |
|---|---|---|---|---|---|
| Silero VAD | ONNX из релиза sherpa; точный размер и SHA после загрузки | MIT (snakers4) | sherpa CPU | sherpa CPU | Не измерена; требуется smoke test |
| GigaAM v3 RNNT | encoder.int8.onnx ≈225 MB, decoder ≈3.33 MB, joiner ≈1.44 MB; tokens | MIT в конкретном v3-репозитории | sherpa NeMo transducer | sherpa NeMo transducer | Кандидат основной модели; не подтверждено инференсом |
| GigaAM v3 CTC | model.int8.onnx ≈225 MB + tokens | MIT в конкретном v3-репозитории | sherpa NeMo CTC | sherpa NeMo CTC | Кандидат альтернативы; сравнение WER не выполнено |
| GigaAM v2 CTC | ONNX INT8; размер уточняется до выбора | Проверить LICENSE конкретного экспорта | sherpa NeMo CTC | sherpa NeMo CTC | Резерв, не интегрирован |
| Pyannote segmentation 3.0 | sherpa ONNX ≈5.7 MiB FP32 / ≈1.5 MiB INT8 | MIT; исходные веса gated на HF | Есть официальный APK sherpa | Есть официальный APK sherpa | Только локальные окна 10 секунд; нужны глобальные embeddings |
| NeMo TitaNet Small | nemo_en_titanet_small.onnx; оригинал NGC 35.77 MB compressed | NGC ссылается на NeMo Toolkit license; проверить NOTICE конкретного ONNX | Есть официальный APK sherpa pyannote+nemo | Есть официальный APK sherpa pyannote+nemo | Нужна оценка на русских голосах; размер ONNX и RAM не измерены |
| Qwen3 0.6B | Официальный GGUF Q8_0 ≈639 MB | Apache-2.0 | llama.cpp Android CPU | llama.cpp Android CPU build | Не загружен и не измерен; Q4 нельзя называть официальным Qwen-экспортом без проверки |

Размеры округлены по страницам поставщиков, это НЕ измерения RAM. ABI относится к нативной библиотеке, веса ONNX/GGUF не зависят от ABI.
Фактический состав AAR и загрузку JNI необходимо проверить отдельно.

## Первичные источники

- [GigaAM авторов: экспорт ONNX, версии, MIT](https://github.com/salute-developers/GigaAM)
- [v3 RNNT: экспорт сопровождающего sherpa](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16/tree/main)
- [v3 CTC: экспорт сопровождающего sherpa](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/tree/main)
- [v2 CTC Kotlin-конфигурация](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)
- [Silero upstream / MIT](https://github.com/snakers4/silero-vad)
- [Pyannote upstream: окно 10 s, до 3 локальных speakers, до 2 одновременно](https://huggingface.co/pyannote/segmentation-3.0)
- [ONNX-экспорт Pyannote и запуск с TitaNet](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)
- [Готовые Android APK обеих ABI](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html)
- [TitaNet Small NGC, license section](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small)
- [Qwen официальный GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF)
- [llama.cpp Android: JNI, NDK, CPU, ограничения контекста](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [sherpa 2.0.1, AAR и SHA256](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)

## Найденные риски

1. Старый экспорт GigaAM 2024-10-24 содержит NC-лицензию. Не переносить MIT актуального upstream на этот архив. Для v3 хранить LICENSE вместе с конкретными весами.
2. Наличие ONNX-экспорта не гарантирует правильную frontend-нормализацию, blank ID, декодер и временные метки. Проверять на реальном wav, включая тишину.
3. Kotlin OfflineSpeakerDiarization.process принимает весь FloatArray. Простое разбиение на независимые вызовы уничтожит глобальные speaker_id. Для production требуется оконная сегментация + embeddings на диске + глобальная кластеризация.
4. До 3 speakers на окно Pyannote — не предел количества участников всей записи.
5. ASR и диаризация не восстанавливают раздельный текст двух одновременно звучащих голосов сами по себе. Нужна честная маркировка overlap и неопределённости.
6. Длина контекста Qwen 32768 не является разумным мобильным бюджетом по умолчанию. Начать измерения с 2048, CPU threads=2, ограничить output tokens, отключить thinking шаблоном.
7. Ни RAM, ни RTF, ни качество нельзя вывести из размера файла или чужого desktop benchmark.

## Критерий завершения исследования

Для каждого выбранного runtime/весов: pinned revision, SHA256, license/NOTICE, ABI inspection, Android CPU smoke test на реальном входе, корректное освобождение ресурсов, время загрузки и обработки, PSS/RSS.
Отдельные gates: native x86_64, физическое arm64, 15 минут / 4 GB.
Не переходить к продуктовой интеграции неисправной модели и не подменять её результатом из примера upstream.
