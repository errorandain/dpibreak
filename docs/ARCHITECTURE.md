# Архитектура DPIBreak

## Слои и поток данных

```
┌────────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose)                                          │
│  MainActivity: статус, кнопка, выбор стратегии, лог,           │
│  per-app исключения                                            │
└──────────────────────────┬─────────────────────────────────────┘
                           │ start/stop Intent, StateFlow статуса
┌──────────────────────────▼─────────────────────────────────────┐
│  core/vpn — TunnelVpnService (Foreground, тип specialUse)      │
│  • Builder.establish() → fd TUN-интерфейса                     │
│  • оркестрация: движок → мост → TUN                            │
│  • protect() для исходящих сокетов (анти-петля)                │
└──────────────────────────┬─────────────────────────────────────┘
                           │ fd TUN
┌──────────────────────────▼─────────────────────────────────────┐
│  core/tunnel — TunSocksBridge (hev-socks5-tunnel, C, MIT)      │
│  читает IP-пакеты из TUN, TCP → SOCKS5, UDP → Fullcone NAT     │
└──────────────────────────┬─────────────────────────────────────┘
                           │ SOCKS5 127.0.0.1:1080
┌──────────────────────────▼─────────────────────────────────────┐
│  core/engine — DesyncEngine (byedpi, C, MIT, через JNI)        │
│  применяет стратегию: split / fake+TTL / disorder / tlsrec …   │
└──────────────────────────┬─────────────────────────────────────┘
                           │ protect()-ed сокеты
                    ┌──────▼──────┐
                    │   Интернет  │  (IP не меняется)
                    └─────────────┘
```

## Карта кода

| Файл / пакет | Ответственность |
|---|---|
| `MainActivity.kt` | Compose-UI, экран статуса и управления |
| `domain/Strategy.kt` | модель стратегии + пресеты (YT/Discord/TG/общий) |
| `domain/ProxyMode.kt` | режимы: VPN_ALL / VPN_SELECTED / LOCAL_PROXY |
| `core/vpn/TunnelVpnService.kt` | TUN-интерфейс, foreground, оркестрация запуска |
| `core/engine/DesyncEngine.kt` | контракт движка десинка |
| `core/engine/ByedpiJniEngine.kt` | реализация через JNI над C-ядром byedpi |
| `core/tunnel/TunSocksBridge.kt` | обёртка hev-socks5-tunnel (TUN→SOCKS5) |
| `app/src/main/jni/` | нативная часть (ndk-build): JNI-мост, ядро byedpi, hev-socks5-tunnel |
| `assets/hostlists/*.txt` | списки доменов по сервисам |

Планируемые добавления (см. ROADMAP): `core/dns/` (DoH-резолвер), `ui/` (экраны),
`data/prefs/` (DataStore настроек), `receiver/BootReceiver.kt`, `services/QuickTile.kt`.

## Поток запуска (последовательность)

1. UI → `startForegroundService(Intent(ACTION_START))`
2. Сервис: `startForeground()` → уведомление
3. Сервис: `Builder().establish()` → `tunFd` (система показывает диалог VPN при первом запуске — `VpnService.prepare()`)
4. Сервис: `ByedpiJniEngine.start(args)` → SOCKS5 слушает `127.0.0.1:1080` (в фоне, отдельный поток)
5. Сервис: `TunSocksBridge.start(tunFd, 1080)` → трафик пошёл
6. Статус → `StateFlow` → UI

## Ключевые инварианты (нарушение = баг)

1. **Анти-петля:** любой сокет, через который движок/туннель ходит в интернет, обязан пройти `VpnService.protect()`. Также само приложение можно исключить: `Builder.addDisallowedApplication(packageName)`.
2. **Foreground всегда:** VPN без foreground-уведомления убивается системой (Android 14+ — тип `specialUse`).
3. **Остановка в обратном порядке:** мост → движок → `tunInterface.close()` → `stopSelf()`.
4. **Никаких сетевых запросов, кроме:** пользовательского трафика через туннель. Никакой телеметрии.
5. **Блокирующие вызовы (JNI) — только в фоновых потоках.**

## Зависимости от сторонних проектов

| Проект | Роль | Лицензия | Способ подключения |
|---|---|---|---|
| [hufrea/byedpi](https://github.com/hufrea/byedpi) | движок десинка (SOCKS5 + split/fake/disorder/tlsrec) | MIT | vendored-копия в `jni/byedpi/` + ndk-build |
| [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | tun2socks | MIT | vendored-копия в `jni/hev-socks5-tunnel/` + ndk-build, JNI через -DPKGNAME |

Оба — MIT: допустимо использование в проекте с лицензией MIT при сохранении копирайт-уведомлений (файлы `LICENSE*` этих проектов нельзя удалять из сабмодулей; при форке их кода — сохранять заголовки).
