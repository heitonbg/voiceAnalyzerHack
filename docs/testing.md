# Тестовая инфраструктура

## Аудит исходного проекта

- Один app, Compose + Material 3, Activity, без DI, ViewModel, repository или базы.
- JUnit 4.13.2 для JVM; исходный ExampleUnitTest проверяет только арифметику.
- androidx.test.ext:junit 1.3.0 и AndroidJUnitRunner для instrumentation.
- Espresso 3.7.0 и Compose ui-test-junit4 подключены шаблоном.
- Нет MockK/Mockito, Robolectric, screenshot framework, JaCoCo и end-to-end suite.
- Нет продуктовой бизнес-логики для покрытия unit tests.
- На итерации 1 приоритет пользователя — настоящий Android CPU inference без заглушек.
  Новые DI/UI/screenshot зависимости откладываются до продуктовой архитектуры второй итерации;
  модели в smoke tests не подменяются fake/mock.

## ModelSmokeTest

Три Android-теста: Silero на настоящем WAV; GigaAM RNNT + Pyannote/TitaNet
и JSON из inference; автоматическое различение голосов на настоящем многоголосом WAV.
Первый прогон через Gradle выявил проблему очистки externally pushed fixtures.
Прямой runner также не смог читать перенесённые через shell файлы.
Текущая доставка: research/models упаковывается **только в androidTest APK**,
тест копирует нужные файлы из test AssetManager в приватный filesDir небольшим буфером.
Рабочий APK не содержит эти assets. Это исследовательские данные, не model manager продукта.

Воспроизводимый запуск после загрузки артефактов из README:

~~~powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.epicwalk.voiceanalyzer.ModelSmokeTest' --console=plain
~~~

HTML-отчёт: app/build/reports/androidTests/connected/debug/index.html.
Машинные результаты: app/build/outputs/androidTest-results/connected.
JSON inference/метрики: externalFilesDir приложения (если runner не удалил пакет после прогона).
Сохранить результаты до удаления приложения; никогда не выдавать прежние файлы за новый прогон.

Fixture reader ограничен WAV PCM16 mono 16 kHz и 60 секундами.
Это не implementation импорта и не тест длинного streaming pipeline.
PSS snapshot не является peak; детектирование кириллицы не является WER;
число кластеров не является DER. См. TEST_PROTOCOL.md.

## Следующие уровни

В продуктовых итерациях: unit tests времени/resampler/checkpoints/JSON/evidence validation;
Room in-memory instrumentation; реальные ML integration tests отдельно от быстрых UI tests;
Compose semantics и восстановление состояния; screenshots тем и размеров;
минимальный E2E import → process → export в авиарежиме.
Не создавать бессодержательные tests, копирующие implementation, ради процента покрытия.
