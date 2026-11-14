# AntiSnorr

Открытый Android-компаньон для [Gadgetbridge](https://gadgetbridge.org/): телефон слушает
микрофон, локально распознаёт храп моделью **YAMNet** и заставляет браслет вибрировать,
чтобы человек сменил положение и перестал храпеть.

Весь звук обрабатывается на устройстве. Приложение ничего не записывает и не отправляет в сеть.

> **Статус:** рабочий прототип, проверенный end-to-end на реальном браслете. Проект
> собирается (`assembleDebug`), 9 юнит-тестов логики детекции проходят. Проверено на
> Realme RMX3501 (Android 13): YAMNet грузится, инференс идёт, foreground-сервис живёт,
> а сигнал тревоги доходит по цепочке AntiSnorr → Gadgetbridge → BLE → **Amazfit Band 5
> и браслет реально вибрирует**. Подробности — в разделе «Что уже проверено».

---

## 1. Важное исправление: индекс храпа — 38, а не 418

В популярных инструкциях (и в исходном плане) написано, что класс `Snoring` имеет индекс
**418**. Это неверно для YAMNet. В официальном `yamnet_class_map.csv`:

| Индекс | Класс |
|-------:|-------|
| 36 | Breathing |
| 37 | Wheeze |
| **38** | **Snoring** (`/m/01d3sd`) |
| 41 | Snort |
| **418** | **Power tool** |

Если захардкодить 418, приложение будет реагировать на дрель, а не на храп. Поэтому:

* класс ищется **по имени** в CSV (`ClassMap.load` → `indexOf("Snoring")`);
* константа `38` осталась только как аварийный fallback;
* фактический индекс пишется в logcat при старте модели.

CSV лежит в `app/src/main/assets/yamnet_class_map.csv` и парсится на устройстве.

---

## 2. Как это работает

```
┌──────────────────────────── AntiSnorr ─────────────────────────────┐
│                                                                    │
│  AudioRecord 16 kHz mono                                           │
│        │ 100 ms чанки                                               │
│        ▼                                                           │
│  FloatRingBuffer ──► окно 15 600 сэмплов (0.975 с)                 │
│        │                    │                                      │
│        │                    ▼                                      │
│        │            YAMNet TFLite (assets/yamnet.tflite)           │
│        │            waveform → log-mel → MobileNetV1               │
│        │                    │                                      │
│        │                    ▼                                      │
│        │            521 вероятность, p(Snoring)                    │
│        │                    │                                      │
│        │            SnoreAggregator                                │
│        │            • 2 окна подряд выше порога                    │
│        │            • гистерезис 4 с (короткие паузы не рвут эпизод)│
│        │            • повтор не чаще раза в 30 с                   │
│        │                    │                                      │
│        │            AlertDispatcher                                │
│        │        ┌───────────┼──────────────┐                       │
│        │        ▼           ▼              ▼                       │
│        │   уведомление  GB intent     GB fake call                 │
│        │        └───────────┴──────────────┘                       │
│        │                    │                                      │
└────────┼────────────────────┼──────────────────────────────────────┘
         │                    ▼
         │             Gadgetbridge (отдельное приложение)
         │                    │ BLE
         │                    ▼
         │                 браслет
         │
   Foreground Service (microphone) + WakeLock
```

### Параметры YAMNet, которые использует код

| Параметр | Значение |
|---|---|
| Вход | `float32[15600]`, моно, 16 kHz, значения −1..1 |
| Окно | 0.975 с |
| Шаг инференса | 300 мс (окна перекрываются) |
| Выход | `float32[521]` |
| Класс храпа | 38 (`Snoring`), плюс `Snort` (41), `Wheeze` (37), `Breathing` (36) — берётся максимум |
| Вибрация | 3 × 0.5 с, пауза 2 с, повтор через 30 с |
| Модель | 4.1 МБ, MobileNetV1, AudioSet |

### Почему не `tensorflow-lite-task-audio`

Библиотека `tensorflow-lite-task-audio` (`AudioClassifier`) рассчитана на модели, у которых
во входе PCM-поток, а mel-спектрограмма описана в метаданных. У YAMNet вход — уже готовый
waveform `[15600]`, а STFT/log-mel считаются **внутри графа**. Поэтому используется обычный
`org.tensorflow:tensorflow-lite` + `Interpreter`: меньше зависимостей и никаких сюрпризов с
метаданными. Форма входа/выхода читается в рантайме, а не зашита в код.

---

## 3. Интеграция с Gadgetbridge: что реально возможно

У Gadgetbridge **нет публичного API «вибрировать»**. Это важно понимать: приложение не
может отправить браслету произвольную команду вибрации. Есть три рабочих пути, все
реализованы в `AlertDispatcher`:

### Режим 1 — Notification

AntiSnorr показывает локальное уведомление, Gadgetbridge перехватывает его через
`NotificationListenerService` и пересылает на браслет. Браслет вибрирует по своим настройкам
уведомлений.

Плюсы: не требует debug-настроек Gadgetbridge.
Минусы: нужно дать Gadgetbridge доступ к уведомлениям и включить AntiSnorr в списке
приложений, чьи уведомления пересылаются — иначе в логе будет
`Ignoring notification: dev.antisnorr`.

### Режим 2 — GB intent (`DEBUG_SEND_NOTIFICATION`) — **по умолчанию**

В исходниках Gadgetbridge есть `IntentApiReceiver` (регистрируется в
`DeviceCommunicationService`, exported). Он принимает:

```
nodomain.freeyourgadget.gadgetbridge.command.DEBUG_SEND_NOTIFICATION
    extras: sender, phoneNumber, subject, body, type
```

Требуется один переключатель в Gadgetbridge:

```
Настройки → Для разработчиков → Intent API → Отладка → Разрешить отладочные команды
```

В русской локализации Bangle.js-форка «Intent API» переведён как **«Интернет API»**, так что
путь выглядит как `Настройки → Настройки для разработчиков → Интернет API → (прокрутить вниз)
→ Отладка → Разрешить отладочные команды`. В upstream Gadgetbridge — `Settings → Developer
settings → Intent API → Debug → Allow debug commands`.

Без этого переключателя команда игнорируется с записью в лог. Именно этот режим выбран
по умолчанию: он требует одну галочку и не зависит от фильтра уведомлений, и именно он
проверен на живом браслете.

### Режим 3 — GB fake call (`DEBUG_INCOMING_CALL`)

```
nodomain.freeyourgadget.gadgetbridge.command.DEBUG_INCOMING_CALL   extra: caller
nodomain.freeyourgadget.gadgetbridge.command.DEBUG_END_CALL
```

Gadgetbridge обрабатывает это как входящий звонок — обычно это самая сильная и навязчивая
вибрация, которая есть у браслета. Через 6 секунд AntiSnorr отправляет `DEBUG_END_CALL`,
чтобы «звонок» не висел.

Тоже требует **Allow Debug Commands**.

### Ограничения

* Gadgetbridge должен быть установлен, запущен и держать браслет подключённым.
* Intent API работает, пока жив `DeviceCommunicationService` Gadgetbridge.
* Поддерживаются upstream `nodomain.freeyourgadget.gadgetbridge` и Bangle.js Gadgetbridge
  (`com.espruino.gadgetbridge.banglejs`), у которого тот же Intent API, но другой package id.
  Список форков — в `GadgetbridgeBridge.KNOWN_PACKAGES`.
* Названия action'ов и extras взяты из master-ветки Gadgetbridge
  (`externalevents/IntentApiReceiver.java`). Если в вашей версии их переименуют —
  править `GadgetbridgeBridge.kt`.
* Gadgetbridge распространяется под AGPL-3.0. AntiSnorr **не линкуется** с ним, а только
  отправляет broadcast — это отдельная программа. Если вы форкаете Gadgetbridge и добавляете
  свой API, на форк распространяется AGPL.

---

## 4. Сборка и установка

Требования: JDK 17, Android SDK 34, Gradle wrapper уже в репозитории.

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Юнит-тесты логики детекции:

```bash
./gradlew :app:testDebugUnitTest
```

Модель уже лежит в `app/src/main/assets/yamnet.tflite` (4 126 810 байт, TFL3).
Она скачана с Kaggle (`google/yamnet`, вариант `classification-tflite`) и распакована из
tar.gz. Файл должен лежать **без сжатия** в APK — это настроено через
`androidResources { noCompress += "tflite" }`, иначе `openFd()` не сможет сделать mmap.

Проверить входы/выходы любой tflite-модели без TensorFlow:

```powershell
powershell -File tools/inspect-tflite.ps1 app/src/main/assets/yamnet.tflite
```

Ожидаемый вывод:

```
[0] FLOAT32 [15600] waveform_binary INPUT
[120] FLOAT32 [1x521] tower0/network/layer32/final_output OUTPUT
```

---

## 5. Настройка и калибровка

1. Установите Gadgetbridge, спарьте браслет, убедитесь, что он подключён.
2. Включите в Gadgetbridge `Настройки → Для разработчиков → Intent API → Отладка →
   Разрешить отладочные команды` (режимы «GB intent» и «Фейк-звонок»).
3. Только для режима «Уведомление»: дайте Gadgetbridge доступ к уведомлениям и включите
   AntiSnorr в списке пересылаемых приложений.
4. Выдайте AntiSnorr разрешения на микрофон и уведомления.
5. Отключите оптимизацию батареи для AntiSnorr (кнопка в приложении).
6. Нажмите «Тест вибрации» и проверьте, что браслет реагирует.

**Порог.** По умолчанию 0.35. Если ложные срабатывания — поднимите до 0.5–0.6. Если
пропускает храп — снизьте до 0.25. В карточке «Живой анализ» видно текущий топ-класс:
удобно понять, на что именно реагирует модель (`Snoring`, `Breathing`, `Snort`, `Power tool`).

**Размещение телефона.** 1–2 метра от головы, микрофон не закрыт, телефон не под подушкой.
Микрофон с AGC/шумоподавлением портит спектр, поэтому приоритет источников:
`UNPROCESSED` → `VOICE_RECOGNITION` → `MIC`; 16 kHz запрашивается первым, иначе 48/44.1 kHz
с ресемплингом.

**Повтор.** Пока эпизод храпа продолжается, вибрация повторяется раз в 30 с. `0 с` —
только одно срабатывание на эпизод.

### Если Gadgetbridge не пересылает уведомление

В logcat Gadgetbridge видно:

```
NotificationListener: Notification 1000 posted: packageName=dev.antisnorr ...
NotificationListener: Not forwarding notification, screen seems to be on and settings do not allow this
NotificationListener: Ignoring notification: dev.antisnorr
```

Это значит, что уведомление до Gadgetbridge дошло, но приложение не включено в список
пересылаемых. Откройте Gadgetbridge → Настройки → Уведомления → выберите AntiSnorr и
разрешите пересылку (в том числе при включённом экране, если нужно тестировать днём).

Если в logcat видно:

```
IntentApiReceiver: Intent API Allow Debug Commands not allowed:
nodomain.freeyourgadget.gadgetbridge.command.DEBUG_SEND_NOTIFICATION
```

— broadcast дошёл, но в Gadgetbridge не включён **Настройки → Для разработчиков → Intent API
→ Отладка → Разрешить отладочные команды**.

---

## 6. Ограничения и дисклеймер

* Это **не медицинское устройство** и не средство диагностики апноэ. Храп — симптом;
  если есть остановки дыхания, дневная сонливость и т.п., идите к врачу.
* YAMNet обучен на AudioSet (YouTube-звуки), а не на ночном храпе с телефона на тумбочке.
  Домен отличается, поэтому возможны пропуски и ложные срабатывания.
* Ночью расходуется батарея: foreground-сервис + 3 инференса в секунду + wakelock.
  На среднем телефоне это заметно; ставьте на зарядку (но не под подушку).
* События хранятся только в памяти процесса и исчезают при перезапуске.
* Некоторые OEM-прошивки (Xiaomi, Huawei, Samsung) убивают даже foreground-сервисы.
  Whitelist обязателен, но не гарантирован.
* Приложение не пишет аудио. Для отладки/дообучения модели запись придётся добавлять
  отдельно и явно спрашивать согласие.

---

## 7. Структура проекта

```
app/src/main/java/dev/antisnorr/
├── AntiSnorrApp.kt              Application: каналы уведомлений, настройки
├── MainActivity.kt              Compose UI + разрешения
├── audio/
│   ├── AudioCapture.kt          AudioRecord, выбор источника/частоты
│   ├── FloatRingBuffer.kt       кольцевой буфер 16 kHz
│   └── Resampler.kt             48k→16k box-фильтр, иначе линейный
├── ml/
│   ├── ClassMap.kt              парсер yamnet_class_map.csv
│   └── YamNetClassifier.kt      TFLite Interpreter, поиск p(Snoring)
├── detection/
│   ├── DetectionEngine.kt       цикл захват → инференс → события
│   └── SnoreAggregator.kt       гистерезис, подтверждение, cooldown
├── alert/
│   ├── AlertDispatcher.kt       выбор канала вибрации
│   ├── AlertNotifier.kt         каналы и локальные уведомления
│   └── GadgetbridgeBridge.kt    Intent API Gadgetbridge
├── service/
│   └── DetectionService.kt      foreground service (microphone) + wakelock
├── core/
│   ├── Settings.kt              SharedPreferences + StateFlow
│   └── MonitorBus.kt            состояние для UI
└── ui/
    ├── Theme.kt, MonitorScreen.kt, AppActions.kt
```

---

## 8. Что уже проверено

Проверено на реальном железе: телефон Realme RMX3501 (Android 13 / API 33), браслет
**Amazfit Band 5**, приложение **Bangle.js Gadgetbridge** (`com.espruino.gadgetbridge.banglejs`),
браслет подключён (заряд 97 %).

- [x] `YamNetClassifier: YAMNet ready: input=15600 samples, classes=521, snoreIndex=38,
      output=FLOAT32` — индекс храпа действительно 38.
- [x] Захват: `Capturing from UNPROCESSED, 16000 Hz → 16000 Hz`.
- [x] Инференс работает: в тишине топ-класс `Silence 50%`, уровень −67 dB.
- [x] Энергетический гейт работает: при `minRms=0.004` в тишине инференс пропускается.
- [x] Foreground-сервис стартует из UI, `isForeground=true`, notification id 42.
- [x] «Тест вибрации» создаёт уведомление в канале `snore_alerts`.
- [x] Broadcast `DEBUG_SEND_NOTIFICATION` доходит до `IntentApiReceiver` Bangle.js
      Gadgetbridge (и корректно отклоняется без «Allow Debug Commands»).
- [x] Уведомление AntiSnorr доходит до `NotificationListener` Gadgetbridge.
- [x] Крашей нет за несколько минут работы с непрерывным инференсом.
- [x] **С включённым «Разрешить отладочные команды» цепочка проходит целиком:**

  ```
  AntiSnorr «Тест вибрации»
    → IntentApiReceiver: Triggering Debug Send notification message to all devices
    → DeviceCommunicationService: Service startcommand: ...devices.action.notification
    → BtLEQueue: add: 00:35:20 Transaction with 1 actions for new notification
    → BtLEQueue: characteristic written: 00000020-0000-3512-2118-0009af100700 GATT_SUCCESS
  ```

  В полезной нагрузке GATT-записи — UTF-8 текст `Тест AntiSnorr` / `Если браслет вибрирует,
  всё настроено правильно.` Браслет вибрировал и показал уведомление — подтверждено вручную.
- [x] **Режим «Фейк-звонок» работает:** `Triggering Debug Incoming Call` → `WriteAction
  00002a46-...` с именем `Тест AntiSnorr` → `GATT_SUCCESS`, через `callDurationMs` (6 с)
  уходит `DEBUG_END_CALL` → `Triggering Debug End Call`, браслет не остаётся в состоянии
  «входящий звонок».

Ещё нужно проверить на браслете и за ночь:

- [ ] Режим «Уведомление» (нужен whitelist приложений в Gadgetbridge — без него в логе
      `Ignoring notification: dev.antisnorr`).
- [ ] Ложные срабатывания на реальном храпе при пороге 0.35.
- [ ] Расход батареи за 8 часов и выживание сервиса на вашей OEM-прошивке.
- [ ] Поведение при заблокированном экране и в Doze.

---

## 9. Roadmap

* Запись эпизодов (opt-in) и дообучение модели на своих данных → меньший TFLite.
* Ночная статистика: длительность/индекс храпа, экспорт CSV.
* Умное затухание: не будить, если эпизод короткий; усиливать вибрацию, если не помогло.
* Прямой BLE-режим для отдельных браслетов (без Gadgetbridge) — дорого по протоколам.
* Автоматическая калибровка порога по фону комнаты.
* Wear OS / задача «разбудить, если храп идёт > N минут».

---

## 10. Лицензии

* Код AntiSnorr — MIT (см. `LICENSE`).
* `yamnet.tflite` — © Google, Apache License 2.0 (YAMNet / AudioSet).
* `yamnet_class_map.csv` — AudioSet ontology, CC BY 4.0.
* Подробности — в `NOTICE`.
