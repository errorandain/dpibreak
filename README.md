# DPIBreak

> Локальный обход DPI-блокировок (YouTube, Discord, Telegram) на Android **без root**.
> Мобильный аналог zapret / GoodbyeDPI: весь трафик обрабатывается **на устройстве**, без внешних серверов, без аккаунтов и логов.

![status](https://img.shields.io/badge/статус-альфа:_туннель_работает-yellowgreen)
![platform](https://img.shields.io/badge/platform-Android%206.0%2B-green)
![root](https://img.shields.io/badge/root-не_требуется-brightgreen)
![license](https://img.shields.io/badge/license-MIT-blue)

---

## ⚠️ Статус проекта

Каркас собран и **рабочий конвейер уже запущен**: TUN → tun2socks → byedpi поднимается
на реальном телефоне, YouTube и Discord обходятся (см. статус вех в [Roadmap](docs/ROADMAP.md)).
Дорожная карта и пошаговые задачи — в [`docs/qwen/TASKS.md`](docs/qwen/TASKS.md).

Уже готово: foreground-VPN-сервис без root, JNI-мост к ядру byedpi, мост tun2socks,
7 пресетов обхода с выбором в UI. В работе: передача hostlists (`-H`), блокировка QUIC,
DoH, экран логов, per-app исключения.

## Как это работает

```
┌──────────────────────────────────────────────────────┐
│  Все приложения телефона                             │
│        ↓ (весь трафик устройства)                    │
│  VpnService → локальный TUN-интерфейс (без root)     │
│        ↓                                             │
│  tun2socks (hev-socks5-tunnel) → SOCKS5 127.0.0.1    │
│        ↓                                             │
│  Движок десинка (byedpi): split / fake / disorder /  │
│  tlsrec — ТСПУ видит «мусор», сервер — оригинал      │
│        ↓                                             │
│  Интернет (IP не меняется, трафик не шифруется)     │
└──────────────────────────────────────────────────────┘
```

Принцип — **десинхронизация DPI**: исходящие пакеты (TLS ClientHello) модифицируются и дробятся так, что оборудование фильтрации провайдера (ТСПУ) не распознаёт запрещённый SNI и пропускает соединение, а сервер получает корректные данные.

Подробно: [`docs/DPI-TECHNIQUES.md`](docs/DPI-TECHNIQUES.md) · Архитектура кода: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

## Что умеет / чего НЕ делает (честно)

**Умеет:**
- обходить блокировки и замедления, основанные на DPI (анализ SNI в TLS): YouTube, Discord — целевые сценарии;
- работать полностью локально: без VPN-серверов, без передачи трафика третьим лицам;
- исключать приложения из обхода (банки, Госуслуги и т.п.).

**Не делает:**
- **не скрывает IP-адрес** и не шифрует трафик (это не VPN);
- **не обходит блокировки по IP**. Если сервис заблокирован по IP-адресам, а не по DPI — десинк бессилен. **Telegram** в РФ в большинстве сетей и так доступен напрямую; там, где он резится по IP, поможет только прокси (поддержка прокси — в планах, см. Roadmap);
- не является гарантией для всех провайдеров: стратегии **требуют подбора** под конкретного оператора (в приложении будут пресеты + тест).

## Сборка

Требования: JDK 17, Android SDK (API 35), Android NDK.
CMake не нужен — нативная часть собирается через **ndk-build** (`app/src/main/jni/Android.mk`).
Зависимости byedpi и hev-socks5-tunnel лежат в репозитории (vendored), клонировать сабмодули не нужно.

```bash
git clone https://github.com/<ваш-логин>/dpibreak.git
cd dpibreak
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Или просто откройте папку в **Android Studio** (она сама доустановит SDK/NDK).

Установка на телефон: «Настройки → Безопасность → Установка приложений из неизвестных источников», затем открыть APK. Никакого root не нужно — приложение запросит только разрешение VPN при первом включении.

CI: на каждый push GitHub Actions собирает debug-APK (артефакт в разделе Actions) — см. [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Структура проекта

```
dpibreak/
├── app/
│   └── src/main/
│       ├── java/com/dpibreak/
│       │   ├── App.kt                  # Application: уведомления, инициализация
│       │   ├── MainActivity.kt         # UI (Jetpack Compose)
│       │   ├── core/
│       │   │   ├── engine/             # движок десинка (интерфейс + JNI-мост к byedpi)
│       │   │   ├── tunnel/             # мост TUN → SOCKS5 (hev-socks5-tunnel)
│       │   │   └── vpn/                # VpnService: TUN-интерфейс, foreground-сервис
│       │   └── domain/                 # модели: стратегии, пресеты, режимы
│       ├── assets/hostlists/           # списки доменов YouTube / Discord / Telegram
│       ├── jni/                        # нативная часть (ndk-build): native-lib.c + vendored byedpi и hev-socks5-tunnel
│       └── res/                        # ресурсы, иконка, строки
├── docs/
│   ├── ARCHITECTURE.md                 # архитектура приложения
│   ├── DPI-TECHNIQUES.md               # теория DPI-обхода и переносимость техник
│   ├── ROADMAP.md                      # этапы разработки
│   └── qwen/                           # конвейер разработки с ИИ-ассистентом
│       ├── MASTER-CONTEXT.md           # контекст-промпт для Qwen
│       └── TASKS.md                    # пошаговые задачи с критериями приёмки
├── .github/workflows/build.yml         # CI: автосборка APK
├── build.gradle.kts · settings.gradle.kts · gradle/libs.versions.toml
├── LICENSE                             # MIT
└── README.md
```

## Дорожная карта

- [x] **M1** — VPN-сервис поднимает TUN, вкл/выкл из UI
- [x] **M2** — сквозной трафик: byedpi + hev-socks5-tunnel, сайты открываются
- [ ] **M3** *(в работе)* — пресеты готовы; hostlists, QUIC, DoH — в процессе
- [ ] **M4** — per-app исключения, DoH, блокировка QUIC, quick-tile, автозапуск
- [ ] **M5** — релиз: подписанные APK, GitHub Releases, публичный анонс

Полный план: [`docs/ROADMAP.md`](docs/ROADMAP.md)

## Участие в разработке

Проект пишется конвейером «человек + ИИ-ассистент (Qwen)». Как это устроено и как добавлять фичи — [`docs/qwen/README`](docs/qwen/TASKS.md).

## Благодарности и лицензии

- [hufrea/byedpi](https://github.com/hufrea/byedpi) — ядро десинка, **MIT**
- [heiyehack/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — tun2socks, **MIT**
- Вдохновлено: [bol-van/zapret](https://github.com/bol-van/zapret), [Flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube), [ValdikSS/GoodbyeDPI](https://github.com/ValdikSS/GoodbyeDPI), [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI)

Лицензия проекта — **MIT** (см. [LICENSE](LICENSE)). При использовании кода byedpi и hev-socks5-tunnel сохраняются их копирайт-уведомления.

## Частые вопросы

**— Нужен ли сервер?**
Нет. Приложение полностью локальное («serverless»). `VpnService` — это просто название Android API для перехвата трафика: весь обход происходит внутри телефона, пакеты уходят в интернет напрямую через вашего оператора. Ничего не арендуется, IP не меняется, трафик не шифруется — это не VPN в классическом смысле.

**— Чем это отличается от VPN?**
VPN гоняет ваш трафик через внешний сервер (меняется IP, всё шифруется, скорость упирается в сервер). Здесь трафик только «правится» на лету: провайдер продолжает видеть ваши соединения, но его DPI-фильтр не распознаёт запрещённые домены и пропускает их.

**— Это легально?**
Утилита не является VPN-сервисом и не предназначена для доступа к материалам, запрещённым законом. Правовой статус инструментов обхода зависит от юрисдикции — см. дисклеймер ниже.

## Дисклеймер

Проект предназначен для восстановления **доступа к сервисам** и борьбы с замедлениями трафика. Используйте в соответствии с законодательством вашей страны. Авторы не несут ответственности за использование инструмента.
