# Проверки и воспроизведение

Все ML integration tests ниже выполняют настоящие модели на Android emulator/device.
Не являются тестами точности без эталонной разметки: количество кластеров не равно DER,
распознаваемость кириллицы не равна WER, а одинаковая речь, повторённая много раз,
не заменяет независимую длинную встречу.

## Проверка 2026-11-08

Android Emulator API 36 x86_64, 4096 MB: BundledOfflineTest — OK (1 test), 44.233 s. Тест выполнил локальные модели из APK на коротком русском WAV, проверил JSON и открыл сформированный PDF через PdfRenderer, затем проверил Qwen и ссылки на существующие реплики. BoundedDiarizationTest и GrowingAudioTest — OK (2 tests), 47.889 s. Сборка app и androidTest и задача unit-тестов завершились BUILD SUCCESSFUL. Это функциональные smoke-тесты, не оценка WER/DER и не проверка интерфейса отправки файлов. Улучшение диаризации на эталонной встрече не измерено: исходное аудио и checkpoints отсутствуют на текущем AVD.

## Проверка ограничения одновременных голосов

Android Emulator API 36 x86_64: после исправления проверки cannot-link при финальном назначении GlobalSpeakers тесты BundledOfflineTest и BoundedDiarizationTest прошли: OK (2 tests), 25.127 s. Полный набор unit-тестов и сборки app/androidTest APK — BUILD SUCCESSFUL. Новый unit-тест проверяет совпадающие эмбеддинги разных одновременных локальных дорожек. Это синтетическое подтверждение исправления конкретного случая, а не измерение улучшения на записи «Подорожник»; исходные аудио и checkpoints в текущем AVD отсутствуют.

## Требования

Windows PowerShell, Android Studio JBR 21+, Android SDK 36, NDK 27.1.12297006,
CMake 3.22.1; Android emulator API 36 x86_64, настроенный на 4096 MB RAM.
Рабочая станция используется только для сборки и доступных публичных fixtures/весов.
Аудио и инференс для device tests остаются на Android.

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
~~~

## Android instrumented tests

Установить app и androidTest APK из app/build/outputs/apk, затем запустить выбранные
классы через Android Studio или adb instrumentation runner. Примеры:

~~~powershell
adb shell am instrument -w -r -e class com.epicwalk.voiceanalyzer.BundledOfflineTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.epicwalk.voiceanalyzer.BoundedDiarizationTest,com.epicwalk.voiceanalyzer.GrowingAudioTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner
~~~

Основные реальные Android tests:
- `BundledOfflineTest`: автономная установка моделей из app APK, русский RNNT ASR,
  JSON-контракт/raw-текст, JNI Qwen-анализ и вопрос-ответ.
- `BoundedDiarizationTest`: настоящие Pyannote/TitaNet embeddings на многоголосом fixture,
  глобальные ID при переходе окна, ручное ожидаемое число и восстановление checkpoint.
- `GrowingAudioTest`: настоящий ASR на русском fixture; PCM-производитель задерживает
  хвост до появления уже распознанной и сохранённой реплики. Проверяет прогрессивную выдачу,
  не скорость кодека на реальной двухчасовой записи.
- `TranscriptionPipelineTest`, `FinalPipelineTest`: импорт/прерывание/повтор и сохранность
  первичного ASR при глобальном проходе.
- `LongAudioTest`: stress-повтор публичного WAV до примерно 15 минут. Это не независимая
  встреча и не тест WER/DER.
- `MeetingRegressionTest`: опционально повторяет встречу около 22 минут, уже импортированную
  в локальные данные эмулятора. Тест не выгружает речь; пишет только техническую сводку
  во внешний app files directory. Запустить с аргументом:
  `adb shell am instrument -w -r -e reprocessExisting true -e class com.epicwalk.voiceanalyzer.MeetingRegressionTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner`.
  Этот тест зависит от наличия такой записи и пропускается без неё. Перед ним убедитесь,
  что эмулятор располагает личным разрешённым тестовым файлом.
- `SpeakerAuditTest`: только диагностический пересчёт эмбеддингов/порогов; никаких
  содержательных выводов о точности без разметки.
- `ReferenceEvaluationTest`: opt-in сравнение с локальным эталоном пользователя для уже
  импортированной записи «Подорожник». Требует положить разрешённый эталон в
  `/sdcard/Android/data/com.epicwalk.voiceanalyzer/files/reference.txt` и запустить:
  `adb shell am instrument -w -r -e evaluateReference true -e class com.epicwalk.voiceanalyzer.ReferenceEvaluationTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner`.
  Тест повторно обрабатывает аудио на Android, временно задаёт число участников по эталону,
  а наружу сохраняет только агрегаты. Сам текст эталона не копируется в репозиторий/отчёт.
  Полученные WER и speaker-onset match — только метрики одного файла, не обучение,
  независимый тест или DER. Число участников вручную известное; это не автоматическое
  обнаружение числа голосов.

Полный v6 проход на этой записи: 1,313,024 ms, elapsed 79,805 ms, первая реплика
через 1,908 ms, 268 реплик, 3 кластера и 75 speaker_unknown.
Полный v7 проход с короткой continuity-коррекцией: 79,878 ms, первая реплика через
3,362 ms, также 268 реплик / 3 кластера, unknown уменьшились до 63. Изменение версии
архивирует старые финальные строки; метрики относятся к единому свежему проходу.
BoundedDiarizationTest: четырёхголосый fixture и checkpoint прошли; вместе с
MeetingRegressionTest — 2 instrumented tests OK. Эти результаты для одного файла/fixture
на emulator x86_64, 4096 MB не являются DER. Атрибуция остаётся неточной.
`docs/results/meeting-regression.json` хранит метрики без речи.

ReferenceEvaluationTest успешно завершился на эмуляторе после принудительного сброса
финальных строк и diarization checkpoint: полный проход 21:53 записи с ожидаемым числом
участников 4 занял 218.879 s; первая реплика появилась через 5.232 s. На 2,830 эталонных
словах получено 622 словесные правки / WER 21.98%; распознано 2,843 слова и 274 реплики.
По 127 эталонным началам speaker-onset match после оптимального переименования ID — 38.58%;
средняя ошибка ближайшего старта — 3,021 ms. Найдено 4 кластера, 63 строки speaker_unknown.
Матрица ошибок: эталонный speaker_2 → ID1:16, ID2:32, unknown:6, ID3:1; speaker_1 → ID1:5,
ID2:7, ID3:1; speaker_3 → ID2:31, ID1:16, unknown:8; speaker_4 → ID1:2, ID3:1,
unknown:1. Это указывает на сильное смешение speaker_2/speaker_3 и недостаток уверенных
примеров speaker_4 на началах фраз. Результат показывает плохую диаризацию на этом аудио,
а не прохождение проверки точности. Onset match чувствителен к грубой секундной разметке
и не заменяет DER; WER измерен только на одном эталоне. Отчёт хранит агрегаты, без текста.

## Проверка модельного пайплайна и сравнение голосовых embeddings

Компоненты выполняют разные задачи: Silero VAD оценивает речевые области; Pyannote segmentation
на коротких окнах оценивает локальную активность говорящих/overlap; TitaNet строит speaker
embeddings по чистым дорожкам; GigaAM v3 RNNT распознаёт речь и выдаёт approximate token times.
Эти результаты нельзя смешивать в один показатель. Текущий основной diarization-путь использует
Pyannote как источник speech/non-speech и локальных дорожек, потому что отдельный Silero gate
может выбросить тихую речь; Silero остаётся в ASR-only fallback. GigaAM не является diarizer.

### Полный свежий тест на одном reference-файле

2026-11-07, Android Emulator API36 x86_64, configured 4096 MB, аудио 21:53, 4 speaker labels
в reference. Каждый запуск повторно выполнил inference на Android и сохранил только aggregate JSON.

| Embedding модель | Wall | PSS sample | Гипотезные ID / unknown | Speaker-onset match | Ошибка старта | WER |
|---|---:|---:|---:|---:|---:|---:|
| TitaNet Small | 267.9 s | 802,032 KiB | 4 / 80 | 36.22% | 3,674 ms | 14.31% |
| WeSpeaker ResNet34-LM | 474.4 s | 593,576 KiB | 3 / 3 | 43.31%* | 7,494 ms | 14.52% |

*Score WeSpeaker нельзя принимать как убедительное улучшение: гипотез было 116 вместо 277,
и большая часть reference starts сопоставилась одной длинной строке. Модель не восстановила
4 ожидаемых ID, не сохранила overlap candidates и работала примерно в 1.77 раза медленнее.
Она прошла CPU ONNX inference на x86_64, но исключена из APK. TitaNet оставлен основным.
Ни один вариант не даёт достаточную точность на этом файле.

Дополнительный свежий TitaNet auto-speaker run (`expectedSpeakers=0`, default cosine threshold 0.4):
261.8 s, 4 ID, 81/281 unknown rows, speaker-onset match 37.01%, средняя ошибка 3,527 ms,
WER 14.31%, sampled PSS 807,452 KiB. Автоматический режим не показывает убедительный выигрыш
против вручную заданных четырёх участников. Порог 0.4 выбран по offline sweep на том же файле;
это не независимая оценка. Метрики onset coarse и не DER.

Aggregate reports: `docs/results/reference-evaluation-titanet.json`,
`docs/results/reference-evaluation-wespeaker.json`,
`docs/results/reference-evaluation-titanet-auto.json`. Оценки относятся к одному reference.
PSS — sampled process PSS, не полный RAM budget; ARM64 устройство не тестировалось.

Финальная проверка после отклонения WeSpeaker из основного APK: `:app:testDebugUnitTest`,
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, install app/test APK — BUILD SUCCESSFUL.
`BoundedDiarizationTest` + `GrowingAudioTest` на x86_64 emulator: `OK (2 tests)`.
Двухчасовой файл, Redmi Note 9 Pro/arm64 и контроль ошибок на независимых записях не проверялись.

## Предыдущий полный эталонный прогон (v17)

2026-11-07, emulator API36 x86_64, configured 4096 MB. ReferenceEvaluationTest выполнил
свежий ASR/diarization на Android с ожидаемыми 4 участниками; OK (1 test).

- 1,313,024 ms audio; elapsed 256,389 ms (RTF 0.195); CPU 371,653 ms.
- Первая фраза: 6,382 ms. 129 recognition chunks; 125 с native word times, 2,734 timed words.
- WER тот же: 405 edits / 2,830 words = 14.31%; punctuation отдельно не оценивалась.
- 277 строк; 4 speaker IDs; 80 unknown. Из 83 overlap rows у 61 есть несколько candidates.
- Speaker-onset match 46/127 = 36.22%; средняя ошибка nearest-row start 3,674 ms.
  Метрики coarse, не DER и не оценка границ.
- Sampled PSS 799,770 KiB, sampling period 200 ms.
- По сравнению с v15 candidate coverage на overlap выросла (4/68 → 61/83), но onset match
  упал (41.73% → 36.22%), а unknown rows выросли (26 → 80). Это осознанное сохранение
  неопределённости на одновременных голосах, не общая точность лучше.
- Unit tests: 26 tests passed при полном rerun; сборка app/androidTest APK завершилась
  BUILD SUCCESSFUL. BoundedDiarizationTest + GrowingAudioTest: OK (2 tests).
- Новая диагностика ambiguity-margin после v16 — offline reassignment существующих
  embeddings/reference starts, не повторный ASR и не независимая проверка. Margin 0 дал
  41.73% в этом sweep, но свежий полный v17 дал 36.22%; sweep не гарантирует улучшение.
- Сводные метрики без расшифровки: docs/results/reference-evaluation.json.

## Предыдущий полный эталонный прогон (v15)

2026-11-07, emulator API36 x86_64, 4096 MB. `ReferenceEvaluationTest` принудительно
архивировал прежние строки и checkpoint, затем заново выполнил модели на Android.
Тест завершился `OK (1 test)`. Второй запуск с seed cosine .92 также завершился OK и дал
те же метрики; изменение отменено как бесполезное.

- 1,313,024 ms аудио; elapsed 256,128 ms (RTF 0.195); CPU elapsed 370,442 ms.
- Первая фраза за 5,102 ms. 129 recognition chunks, 125 с валидированными native word times,
  всего 2,734 timed words. RNNT token times approximate; forced alignment отсутствует.
- 2,830 эталонных слов, 2,802 гипотезных, 405 edit operations, WER 14.31%.
  WER нормализует регистр, ё/е и пунктуацию, поэтому оценка знаков препинания отдельно не сделана.
- 264 финальные строки, 4 speaker ID (число 4 было задано вручную), 54 unknown rows.
  Speaker-onset match after optimal ID permutation 48/127 = 37.80%; mean nearest hypothesis
  row start difference 4,209 ms. Это диагностическая метрика, не DER и не точность границ.
  Основная ошибка: эталонные speaker_2 и speaker_3 преимущественно попали в один ID.
- Sampled process PSS: 788,919 KiB при периоде 200 ms; тестовый sampler, не точный peak.
  Испытан только emulator, не Redmi Note 9 Pro. Подробные агрегаты —
  `docs/results/reference-evaluation.json`; transcript/reference text туда не включён.
- `AsrTimingTest` и `BundledOfflineTest`: OK (2 tests) на публичном русском WAV; проверен
  реальный GigaAM v3 E2E RNNT, punctuation output, bounded native word timings, JSON contract
  и local Qwen. `GrowingAudioTest` проверил появление реальных сохранённых фраз до того,
  как PCM producer закончил выдачу данных.
- Последняя `:app:testDebugUnitTest :app:installDebug :app:installDebugAndroidTest`
  завершилась BUILD SUCCESSFUL. Полный reference instrumentation и тесты
  `BoundedDiarizationTest` + `GrowingAudioTest` дали OK (3 tests).

Дополнительный offline threshold sweep на сохранённых embeddings (без повторного inference)
с автоматическим числом участников дал: cosine .35 → 2 кластера / 91 unknown voice span /
37.0% onset match; .45 → 3 / 104 / 42.5%; .60 → 14 / 110 / 44.1%; .70 → 54 / 132 /
22.0%. Он не даёт устойчивого рабочего режима: четырёх говорящих надёжно восстановить
не удалось. Эти threshold-onset числа посчитаны на том же эталоне и не являются независимым
тестом или DER.

## Полнофайловая диаризация (opt-in)

Эксперимент повторно использует ASR checkpoint и один раз вызывает sherpa-onnx diarizer
для всех 21 008 384 PCM samples. Запускать только после локального полного прохода
ReferenceEvaluationTest. Команды для двух раздельных прогонов:

~~~powershell
adb shell am instrument -w -r -e evaluateReference true -e freshReference false -e expectedSpeakers 0 -e wholeFileExperiment true -e wholeFileAutoOnly true -e class com.epicwalk.voiceanalyzer.ReferenceEvaluationTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e evaluateReference true -e freshReference false -e expectedSpeakers 0 -e wholeFileExperiment true -e wholeFileFixedOnly true -e class com.epicwalk.voiceanalyzer.ReferenceEvaluationTest com.epicwalk.voiceanalyzer.test/androidx.test.runner.AndroidJUnitRunner
~~~

Каждый прогон пишет `whole-file-diarization.json` и native-сегменты в app external files;
сохраняйте отчёт перед следующим запуском. Исправленный auto завершился OK за
252.739 s инструментального теста, native process 189.063 s, 85 ID и 13/127 onset.
Исправленный K=4 завершился OK за 212.911 s, native process 183.903 s,
4 ID и 47/127 onset. Разница тестового wall и native process включает оценку,
загрузку checkpoint и postprocessing; это не полный RTF. Подробности и решение:
[полнофайловый эксперимент](WHOLE_FILE_DIARIZATION_EXPERIMENT.md).

## Остальные проверенные сценарии

- JVM unit suite прошёл повторно в последней сборке; включает WAV/resampler, chunk planning, native word timings, global speaker clustering и merge invariants.
- Последний сборочный прогон: app + androidTest APK + JVM suite успешны.
- Инструментированный регрессионный прогон: MeetingRegressionTest,
  BoundedDiarizationTest и GrowingAudioTest — OK (3 tests).
- Вся фактическая история и артефакты: `docs/results/`, README и отчёты итераций.

## Настройка склейки и пунктуации (v19)

После изменения группировки слов/реплик и сборки punctuation-aware join выполнен новый
полный `ReferenceEvaluationTest` на API 36 x86_64 эмуляторе с конфигурацией 4096 MB.
`app:testDebugUnitTest`: 31 passed, 0 failed. Сборка и установка app + androidTest APK успешны.
Instrumentation: `OK (1 test)`, время теста 292.193 s; измеренное время повторного inference
внутри теста — 267.259 s для 1,313,024 ms записи, RTF 0.204. Первое сохранённое высказывание
появилось за 5.930 s.

После увеличения максимального межсловного промежутка до 1.5 s и максимальной длины фразы
до 90 s гипотеза уменьшилась с 277 до 241 строки. Это уменьшение дробления на данном файле,
а не независимая оценка правильности границ. WER не изменился: 405 правок / 2,830 слов =
14.31%. Punctuation boundary accuracy составила 2,378 / 2,532 = 93.92% только среди точно
совпавших и выровненных слов; пропуски/замены слов исключены из знаменателя. Этот показатель
оценивает совпадение знака после слова с конкретным форматированием пользовательского
эталона, не качество восприятия интонации в целом.

Число участников было вручную задано как 4. На 127 грубо отмеченных началах реплик
speaker-onset match после перестановки ID — 36.22%; средняя ошибка ближайшего старта
4,827 ms; 80 строк unknown. Это диагностическая оценка, не DER, и она не показывает
улучшения диаризации. Sampled peak PSS — 813,504 KiB (интервал 200 ms), не точный системный
максимум. Полный агрегат без текста: `docs/results/reference-evaluation-titanet-v19.json`.
Сравнение с предыдущей конфигурацией: строки 277 → 241, WER 14.31% → 14.31%, время этапа
267.9 → 267.3 s; скорость инференса практически не изменилась.

### Эксперимент с прямым и обратным проходом

На установленной v19 версии эмулятора выполнен instrumentation `ReferenceEvaluationTest`
с `freshReference=false` и `bidirectionalExperiment=true`; он заново прогнал Pyannote/TitaNet
на PCM, прочитанном от конца, не запускал повторный ASR и сохранил только агрегат.
Обратная диаризация заняла 146.1 s. Прямой проход дал 375 voice turns, обратный — 741.
Наивное объединение обоих наборов и повторная кластеризация дало 6 кластеров, 311 unknown
из 1,116 сегментов и onset match 35.43% против 36.22% у прямого результата. Такой способ
слияния отклонён.

В диагностическом варианте IDs двух направлений сопоставлялись по центроидам TitaNet
embeddings, а reverse использовался только когда прямой pass оставил speaker_unknown.
На 127 reference onsets score составил 42.52% (54/127), unknown был только в одной
reference onset позиция. Требование совпадения speaker ID обоих проходов дало 29.13% и
52 unknown onset. Эти кандидаты вычислены на одном reference; это не независимая оценка,
и неизвестно, сохранится ли выигрыш на других разговорах.

Двунаправленный full-audio кандидат отклонён и удалён из production: обратный проход
занял 146.1 s; наивное слияние дало 35.43% speaker-onset match, 6 кластеров и 311 unknown.
Reverse-fill дал 42.52% лишь на 127 грубых reference onsets одного файла, а обязательное
согласие направлений — 29.13%. Неопределённость обобщения и цена полного повторного прохода
не оправдывают включение. Диагностическая сводка:
`docs/results/reference-evaluation-bidirectional.json`.

## Профиль CPU потоков на Android

`ModelSmokeTest.cpuThreadSweepOnRealPublicFixtures` выполняет настоящие GigaAM v3 RNNT
и Pyannote 3.0 + TitaNet Small на двух публичных WAV на API36 x86_64 эмуляторе с 4096 MB.
Тест завершился `OK (1 test)` за 23.267 s. Инференсные значения:

| Модель / fixture | Потоки | Загрузка | Инференс | RTF | PSS после инференса |
|---|---:|---:|---:|---:|---:|
| GigaAM v3, 11.29 s | 1 | 992 ms | 1,077 ms | 0.095 | 386,830 KiB |
| GigaAM v3, 11.29 s | 2 | 1,116 ms | 818 ms | 0.072 | 394,100 KiB |
| GigaAM v3, 11.29 s | 4 | 899 ms | 688 ms | 0.061 | 394,488 KiB |
| Pyannote + TitaNet, 56.86 s | 1 | 224 ms | 4,606 ms | 0.081 | 326,544 KiB |
| Pyannote + TitaNet, 56.86 s | 2 | 197 ms | 3,796 ms | 0.067 | 325,637 KiB |
| Pyannote + TitaNet, 56.86 s | 4 | 199 ms | 3,519 ms | 0.062 | 325,933 KiB |

PSS здесь снят после каждого отдельного инференса, а не является пиковым потреблением.
Модели запускались отдельно, не параллельно. Diarizer во всех режимах выдал 8 IDs на fixture;
это сигнал over-clustering, не quality pass. Разметки transcript/RTTM нет, поэтому WER/DER/JER
не измерены. Результат не переносится на ARM64 или 21-минутную запись. Полный агрегат:
`docs/results/cpu-thread-profile.json`.

Четыре потока — кандидат для более широкого Android-сравнения, но короткий smoke не
подтверждает качество и полный pipeline RTF. Production settings потоков не изменены.
Обновлённый APK не установлен: свободно около 1.6 GB, установка завершалась
INSTALL_FAILED_INSUFFICIENT_STORAGE.

### A/B baseline v19 и production v22 на 21:53 reference

Обе версии прогнаны на одном Android Emulator API 36 x86_64 с 4096 MB, на одной записи
1,313,024 ms и одной эталонной разметке; v22 — свежий локальный ASR + diarization проход,
instrumentation завершился `OK (1 test)`. Baseline берётся из фактического v19 отчёта.

| Метрика | Baseline v19 | Production v22 | Изменение |
|---|---:|---:|---:|
| Время полного прохода | 267,259 ms | 273,747 ms | +6,488 ms (+2.43%) |
| RTF | 0.2035 | 0.2085 | +0.0050 |
| Первая фраза | 5,930 ms | 6,369 ms | +439 ms |
| WER | 405/2,830 = 14.31% | 405/2,830 = 14.31% | без изменения |
| Speaker-onset после оптимальной перестановки ID | 46/127 = 36.22% | 45/127 = 35.43% | −0.79 п.п. |
| Средняя ошибка ближайшего начала | 4,827 ms | 4,886 ms | +59 ms |
| Unknown строки | 80/241 | 84/232 | +4 строки |
| Overlap строки / с >1 кандидатом | 83 / 61 | 81 / 62 | −2 / +1 |
| Sampled peak PSS, шаг 200 ms | 813,504 KiB | 805,648 KiB | −7,856 KiB |
| CPU elapsed | 388,285 ms | 394,087 ms | +5,802 ms |

WER, количество слов и число распознавательных блоков совпали. v22 не улучшил точность
speaker onset и оказался немного медленнее; небольшое снижение sampled PSS нельзя считать
гарантией меньшего реального пика RAM. Это регрессия по двум главным метрикам качества/
скорости, а не успешное улучшение. Отчёты без текста: `docs/results/reference-evaluation-titanet-v19.json`
и `docs/results/reference-evaluation-v22.json`.

Дополнительный полный свежий прогон v22 с автоматическим числом участников
(`expectedSpeakers=0`) завершился `OK (1 test)`: 267,205 ms, RTF 0.2035, первая фраза
5,602 ms, WER 14.31%, onset 46/127 = 36.22%, 86 unknown из 238 строк, sampled PSS
806,428 KiB. Он совпадает с v19 по WER/onset и быстрее фиксированного K=4 v22 на 6.5 s;
неопределённых строк стало на 6 больше, чем в v19. В выборке эталона автоматический режим
лучше фиксированного v22 K=4 по onset на 0.79 п.п., но это всё ещё слабая абсолютная
точность и один reference. Отчёт: `docs/results/reference-evaluation-auto-v22.json`.

Полный 4-поточный кандидат на том же x86_64 эмуляторе, с автоматическим числом говорящих,
тоже завершился `OK (1 test)`: 279,583 ms / RTF 0.2130, первая фраза 6,951 ms,
WER 14.31%, onset 36.22%, 86 unknown из 238 строк, sampled PSS 814,506 KiB,
CPU elapsed 918,168 ms. По сравнению с auto v22 при 2 ASR / 1 diar потоках он медленнее
на 12,378 ms (+4.63%), CPU elapsed вырос примерно в 2.4 раза, качество не изменилось.
Поэтому 4 потока откатил; production оставляет 2 ASR / 1 Pyannote / 1 TitaNet.
Измерение не подтверждает преимущества четырёх потоков для полной записи.
Отчёт: `docs/results/reference-evaluation-auto-v23-threads4.json`.

Smoke с 8 кластерами показывает over-clustering на публичном fixture, но без разметки
фактическое число голосов неизвестно. Код нормирует TitaNet vectors; global cosine
порог по умолчанию 0.4, native sherpa clustering — auto (`numClusters=-1, threshold=0.5`).
На эталоне threshold sweep дал 3 кластера и 43.31% coarse onset при 0.35; но это
диагностическая переразметка saved embeddings, причём оценка берет голос с максимальным
перекрытием строки, а не повторяет word-level planner/merger. Значение 0.55 создало 10
кластеров; выбирать его нельзя. Локальный Pyannote ID не напрямую используется как global
speaker ID: соответствие строится по чистым embedding spans с ключом окна, а overlap lane
участвует только в ограничениях и кандидатах.

Неизвестная длительность строк не записана; DER/JER недоступны без эталонной интервальной
разметки. Onset — грубая метрика по 127 reference starts, не оценка точности границ.
Свободно 8.2 GB из 16 GB /data после удаления только копируемых моделей; аудиозаписи
сохранены. ARM64 устройство не подключено, поэтому AArch64 runtime не проверен.

Полные независимые таймеры VAD, Pyannote, TitaNet, clustering и final assembly всё ещё
не снимаются. Общее время pipeline измерено на Android, а short-fixture CPU sweep не
заменяет полный тест; данные 4 потоков выше относятся только к x86_64 эмулятору.

### v25: пограничное overlap у распознанных слов

Полный новый Android-прогон на той же записи, auto speakers, 2 ASR / 1 diar CPU thread:
`OK (1 test)`, 263,711 ms (RTF 0.2008), WER 405/2,830 = 14.31%,
speaker-onset 46/127 = 36.22%, unknown 82/235 строк, sampled PSS 811,069 KiB.
У v22 auto: 267,205 ms, RTF 0.2035, WER/onset те же, unknown 86/238,
sampled PSS 806,428 KiB. Первая фраза v25 появилась позже: 7,939 против
5,602 ms. В production v25 сохранён учет overlap только когда он покрывает
не менее 25% длительности слова; касание границы слова больше не превращает
его автоматически в unknown. Четыре unknown строки исчезли, но подтверждённого
роста точности speaker_id нет. Отчёт: `docs/results/reference-evaluation-v25.json`.

Причины остальных ошибочных ID и доли девяти категорий не установлены: evaluator
сохраняет только агрегаты; интервальной эталонной разметки и поэтапного аудита
для всех 127 onsets нет. DER/JER и unknown duration остаются неизмеренными.
Независимая запись с размеченными speaker turns не была доступна. Эти ограничения
нельзя подменять оценкой по одному reference.

Не записывайте результаты вручную до завершения команды и проверки её лога.
Инструментальный runner может завершиться с exit code 0 и при этом сообщить FAILURES;
проверяйте итоговую строку `OK (n tests)` и отсутствие failure traces.

## Непроведённые тесты

WER измерен только для одного эталона; DER, независимые временные границы,
двухчасовая запись/обработка, утечки памяти и точный peak PSS не проверены. Есть только
приблизительный sampled process PSS одного emulator run. Физический Redmi Note 9 Pro не
подключён; наличие ARM64-библиотеки не доказывает запуск на устройстве. MP3/M4A matrix,
overlap transcription и независимые русские записи с 2–5 участниками нуждаются в отдельных
проверках. Текущая диаризация на эталоне неудовлетворительна и требует замены/существенной
переработки, прежде чем заявлять требуемое качество.
