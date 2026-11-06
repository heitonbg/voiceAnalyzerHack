# Проверки и воспроизведение

Все ML integration tests ниже выполняют настоящие модели на Android emulator/device.
Не являются тестами точности без эталонной разметки: количество кластеров не равно DER,
распознаваемость кириллицы не равна WER, а одинаковая речь, повторённая много раз,
не заменяет независимую длинную встречу.

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

Последний зафиксированный прогон MeetingRegressionTest на существующей записи:
1,313,024 ms, elapsed 74,911 ms, первая реплика через 2,091 ms, 268 реплик,
3 speaker clusters, 40 реплик короче 500 ms. Это результат одного файла на emulator
x86_64 с 4096 MB и он не является DER. `docs/results/meeting-regression.json` хранит
только эти метрики, не аудио и не транскрипт.

## Остальные проверенные сценарии

- JVM unit suite: 9 тестов для WAV/resampler, времени chunk и PhrasePlanner.
- Последний сборочный прогон: app + androidTest APK + JVM suite успешны.
- Инструментированный регрессионный прогон: MeetingRegressionTest,
  BoundedDiarizationTest и GrowingAudioTest — OK (3 tests).
- Вся фактическая история и артефакты: `docs/results/`, README и отчёты итераций.

Не записывайте результаты вручную до завершения команды и проверки её лога.
Инструментальный runner может завершиться с exit code 0 и при этом сообщить FAILURES;
проверяйте итоговую строку `OK (n tests)` и отсутствие failure traces.

## Непроведённые тесты

Не измерены WER, DER, независимые временные метки по разметке, двухчасовая непрерывная
запись/обработка, утечки памяти и пиковое PSS полного приложения. Физический Redmi Note 9 Pro
не подключён; наличие ARM64-библиотеки не доказывает её работу на физическом устройстве.
MP3/M4A matrix, overlap transcription и различные реальные русские группы из 2–5 участников
нуждаются в отдельных проверках.
