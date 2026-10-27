# Задачи — конвейер разработки

> 💡 **Если работаете через Qwen Code (CLI):** промпты ниже не нужны — просто
> пишите «Выполни Задачу N». Промпты предназначены для веб-чата (копируются в
> сообщение вместе с кодом текущих файлов).

Работаем строго по порядку. Перед каждой задачей в Qwen вставляется
[MASTER-CONTEXT.md](MASTER-CONTEXT.md), затем промпт задачи.
После каждой задачи: `./gradlew assembleDebug` → фикс ошибок → коммит.

Пометки: 🧑‍💻 — делает человек, 🤖 — Qwen, 🔧 — вручную/инструментами.

---

## Задача 0. Окружение 🧑‍💻

**Цель:** проект собирается на вашей машине.

1. Установить Android Studio (Ladybug или новее), JDK 17, через SDK Manager: SDK 35, NDK (последний LTS), CMake 3.22.1.
2. Открыть папку проекта, дождаться синхронизации Gradle (Gradle wrapper уже в репозитории).
3. `Build → Build APK(s)` (или `./gradlew assembleDebug`) — первая сборка должна пройти (сейчас собирается «пустой» APK с заглушкой нативной библиотеки).

**Приёмка:** `app-debug.apk` собирается; APK ставится на Samsung и открывается.
**Коммит:** `chore: bootstrap build environment`

---

## Задача 1. UI-каркас и управление сервисом 🤖

**Цель:** кнопка в UI реально запускает/останавливает foreground-сервис.

**Шаги:**
1. `core/ServiceManager.kt`: обёртка над `Context.startForegroundService()` / `stopService()`, `MutableStateFlow<ServiceState>` (Idle/Starting/Active/Error).
2. `core/NotificationUtils.kt`: канал `vpn_service` (IMPORTANCE_LOW), уведомление с PendingIntent на MainActivity.
3. `TunnelVpnService`: `onStartCommand` обрабатывает ACTION_START/STOP, вызывает `startForeground()` (тип `specialUse`), пока больше ничего не делает; статус пишет в общий StateFlow.
4. `MainActivity`: кнопка → `ServiceManager.toggle()`, статус из StateFlow. Запрос `POST_NOTIFICATIONS` (Android 13+).

**Приёмка:** кнопка включает уведомление сервиса и меняет статус; повторное нажатие убирает; не падает при повороте экрана.
**Коммит:** `feat: service lifecycle + UI state`
**Промпт для Qwen:** «Выполни Задачу 1 из docs/qwen/TASKS.md. Файлы: ServiceManager, NotificationUtils, доработка TunnelVpnService и MainActivity. StateFlow — через объект-синглтон или companion, без DI-фреймворков.»

---

## Задача 2. Разрешение VPN и TUN 🤖

**Цель:** сервис поднимает настоящий TUN-интерфейс.

**Шаги:**
1. `VpnService.prepare(context)` в MainActivity: системный диалог при первом запуске (ActivityResultContracts).
2. `startTunnel()`: Builder (см. комментарии в `TunnelVpnService.kt`) → `establish()` → сохранить `ParcelFileDescriptor`. Пока просто держим fd, интернет через VPN работать не будет — это нормально.
3. `onRevoke()` — корректная остановка. Лог всех переходов состояний (в logcat с тегом `DPIBreak`).

**Приёмка:** при включении появляется системный диалог VPN; после согласия в `adb shell ip addr` виден новый tun-интерфейс; интернет «пропадает» при активном сервисе (ожидаемо — трафик попадает в TUN и не обрабатывается) и возвращается после выключения.
**Коммит:** `feat: tun interface establishment`
**Промпт:** «Выполни Задачу 2. Обработай отказ пользователя в диалоге VPN (статус Error), таймаут establish, повторные вызовы start (идемпотентность).»

---

## Задача 3. Подключение ядра byedpi (JNI) 🤖🔧

**Цель:** локальный SOCKS5-прокси движка byedpi запускается в приложении.

**Шаги:**
1. 🔧 `git submodule add https://github.com/hufrea/byedpi app/src/main/cpp/byedpi` (+ закоммитить `.gitmodules`).
2. 🤖 Изучить сборку byedpi (Makefile): определить, как собрать объектники как статическую библиотеку для Android NDK (исходники: main.c, proxy.c, conev.c, desync.c, packets.c, extend.c, mpool.c; для Windows-кода — исключить).
3. 🤖 `CMakeLists.txt`: `add_library(byedpi STATIC …)` + линковка в `libdpibreak.so`.
4. 🤖 JNI-мост в `native-lib.c` по образцу в комментариях `ByedpiJniEngine.kt`: `jniStartProxy(args)`, `jniStopProxy()`, `jniForceClose()` + `#define __linux__`-совместимость: главное — `optind = 1` перед `main()` и перевод логов ядра в `__android_log_print`.
5. 🤖 `ByedpiJniEngine.start()`: поток (или coroutine Dispatchers.IO) → `jniStartProxy(arrayOf("-p","1080", "-l","127.0.0.1", *args))`; дождаться прослушивания порта (retry connect на 127.0.0.1:1080); вернуть порт.

**Приёмка:** с включённым VPN-сервисом (или без него, см. задачу 4 для полного цикла) `adb forward tcp:1080 tcp:1080` и `curl --proxy socks5h://127.0.0.1:1080 https://example.com` с хоста возвращает страницу.
**Коммит:** `feat: byedpi engine via JNI`
**Промпт:** «Выполни Задачу 3. Сначала покажи план сборки byedpi под NDK (какие файлы, какие флаги), потом код. Важно: ядро блокируется в main() — вызов только из фонового потока; повторный старт требует optind=1 и shutdown(server_fd) в stop.»

---

## Задача 4. Подключение hev-socks5-tunnel (TUN→SOCKS5) 🤖🔧

**Цель:** сквозной трафик: все приложения ходят в интернет через движок.

**Шаги:**
1. 🔧 `git submodule add https://github.com/heiher/hev-socks5-tunnel app/src/main/cpp/hev-socks5-tunnel` (+ его сабмодули рекурсивно).
2. 🤖 Сборка в CMake/ndk-build по README hev-socks5-tunnel (раздел Android). Уточнить JNI-подобный API или CLI-режим запуска с fd.
3. 🤖 `TunSocksBridge.start(tunFd, socksPort)`: запуск туннеля с конфигом: tun fd = наш fd, socks5 = 127.0.0.1:1080, DNS-обработка — по умолчанию.
4. 🤖 **Критично:** исходящие сокеты туннеля → `VpnService.protect()`. У hev-socks5-tunnel есть поддержка protect-колбэка (проверить API; если нет — исключить своё приложение через `addDisallowedApplication(packageName)` и защитить сокеты движка отдельно).
5. `TunnelVpnService.startTunnel()`: полная цепочка establish → engine.start → bridge.start.

**Приёмка:** 🏁 **ГЛАВНЫЙ РУБЕЖ.** Кнопка «Включить» → все сайты открываются (пока без обхода — просто сквозной трафик), YouTube-страница открывается (видео — как повезёт, обход на следующей задаче). Выключение возвращает обычный интернет.
**Коммит:** `feat: end-to-end tun2socks pipeline`
**Промпт:** «Выполни Задачу 4. Сначала план интеграции hev-socks5-tunnel (как передаётся tun fd, как задать socks5, как сделать protect сокетов), после моего "ок" — код.»

---

## Задача 5. Стратегии и hostlists 🤖

**Цель:** выбор пресета реально влияет на трафик.

**Шаги:**
1. Копирование hostlist из assets в filesDir при первом запуске (`AssetUtils`).
2. `Strategy.byedpiArgs` + `-H <путь к hostlist>` при старте движка.
3. UI: RadioGroup/Chips выбора пресета (Presets.all), выбранный сохраняется (SharedPreferences/DataStore), передаётся в сервис через Intent-экстра.
4. Экран «Лог»: TextView с выводом движка (per-строки из JNI-лога, кольцевой буфер ~500 строк).

**Приёмка:** переключение пресетов меняет поведение; с пресетом YouTube страница YouTube и Discord открываются хотя бы на одном пресете (если нет — Задача 6, тюнинг).
**Коммит:** `feat: strategy presets + hostlists + log view`
**Промпт:** «Выполни Задача 5. Пресеты уже описаны в domain/Strategy.kt — только подключи их. UI — Material3, без сторонних библиотек.»

---

## Задача 6. Тюнинг под провайдера + QUIC + DNS 🤖🧑‍💻

**Цель:** стабильный YouTube/Discord на вашем Samsung и вашем операторе.

**Шаги:**
1. 🧑‍💻 Тестовая матрица: Wi-Fi (домашний) и мобильная сеть. Для каждого пресета — youtube.com (видео 1080p), discord.com, звонок в Discord.
2. 🤖 QUIC: блокировать UDP 443 в туннеле (заставить YouTube использовать TCP) — уточнить способ у hev-socks5-tunnel / движка (byedpi: `-K` протокол-фильтры, `-U`/udp-опции).
3. 🤖 DoH-резолвер: DNS из TUN → DoH (OkHttp или минимальная своя реализация; сервера из настроек, кеширование).
4. 🧑‍💻 Подобрать рабочие параметры (split-позиции, TTL фейка) и обновить `Presets`.

**Приёмка:** YouTube-видео без замедления, Discord-звонок работает, на обоих типах сети.
**Коммит:** `feat: quic blocking + doh + tuned presets`
**Промпт:** «Выполни Задачу 6, пункты 2-3. Требования: без новых тяжёлых зависимостей; DoH-сервер — настраиваемый.»

---

## Задача 7. Per-app исключения 🤖

**Цель:** банки/Госуслуги не идут через обход.

**Шаги:** экран выбора приложений (PackageManager, иконки+имена, чекбоксы) → сохранённый список → `Builder.addDisallowedApplication(pkg)` для каждого (или `addAllowedApplication` при режиме VPN_SELECTED — домен `ProxyMode`).

**Приёмка:** с исключённым браузером сайт не открывается через VPN (проверка что исключение работает), банки не ругаются.
**Коммит:** `feat: per-app exclusion`

---

## Задача 8. Quick Tile + автозапуск 🤖

1. `QuickTileService` (TileService): отражает состояние, тумблер.
2. `BootReceiver` (RECEIVE_BOOT_COMPLETED): автозапуск если включён в настройках (флаг в prefs).
3. Уведомление: кнопка «Стоп» (PendingIntent ACTION_STOP).

**Коммит:** `feat: quick tile + boot receiver + stop action`

---

## Задача 9. Полировка UI 🤖

- Normal-тема, тёмная тема, иконки статуса.
- Экран настроек: DoH-сервер, порт SOCKS, «запуск при загрузке».
- Обработка ошибок: движок не поднялся → человеческое сообщение.
- Диагностика: «Проверить соединение» — последовательность тестов (DNS → TCP → TLS к youtube.com) с результатами.

**Коммит:** `feat: settings + diagnostics + theme`

---

## Задача 10. Тестирование и стабилизация 🤖🧑‍💻

1. 🤖 Unit-тесты: парсер аргументов, пресеты, менеджер состояний.
2. 🧑‍💻 Долгий тест: VPN 8+ часов, сон/пробуждение, переключение Wi-Fi↔LTE, входящий звонок.
3. 🤖 Фиксы найденного: утечки fd, зомби-потоки JNI, рассинхрон статусов.
4. 🧑‍💻 Проверка на другом телефоне (если есть) и другом операторе.

**Коммит:** `fix: stability + tests`

---

## Задача 11. CI: релизная сборка 🧑‍💻🤖

1. 🔧 Сгенерировать keystore: `keytool -genkey -v -keystore dpibreak.jks -keyalg RSA -keysize 2048 -validity 10000 -alias dpibreak`. **Не коммитить!**
2. 🔧 GitHub → Settings → Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. 🤖 Workflow `release.yml`: assembleRelease с подписью из secrets, upload в GitHub Releases по тегу `v*`.
4. Проверить артефакт CI debug-сборки из `build.yml`.

**Приёмка:** по тегу v0.1.0 в Releases появляется подписанный APK, устанавливающийся на Samsung поверх debug (или сносом — подписи разные!).

---

## Задача 12. Публикация 🧑‍💻

1. Иконка приложения (adaptive icon), финальное название (если переименовываем — refactor applicationId).
2. README: скриншоты, GIF-демо, FAQ (у меня не работает → подбор пресета), раздел «Как это работает» вычитать.
3. Проверить сохранение лицензий сабмодулей (MIT-нотисы byedpi/hev).
4. issues-шаблоны (`.github/ISSUE_TEMPLATE`): «Не работает у провайдера X» с полями (оператор, регион, что пробовал).
5. Первый публичный анонс (Habr/4PDA/Telegram) — помня о правовых нюансах (см. README-дисклеймер).

---

## Дальше (после релиза)

Идеи M6+ — см. [ROADMAP.md](../ROADMAP.md): свой Kotlin-движок, автоподбор стратегии, профили по сетям, upstream-прокси для IP-блокировок (Telegram), root-режим с nfqws, F-Droid.

Каждую крупную фичу начинать с текстового плана в Qwen (без кода), ревью плана — потом код.
