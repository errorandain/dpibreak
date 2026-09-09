package com.dpibreak.core.tunnel

/**
 * Мост «TUN → SOCKS5»: читает IP-пакеты из TUN-интерфейса и заворачивает
 * TCP-соединения (и UDP) в локальный SOCKS5-прокси движка десинка.
 *
 * Реализация — hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel, MIT):
 * быстрый C tun2socks, собирается NDK, API принимает fd TUN-интерфейса и адрес
 * SOCKS5-прокси. Используется актуальными DPI-приложениями (ByeByeDPI и др.).
 *
 * Альтернатива (roadmap M6+): собственный парсер IP/TCP на Kotlin.
 *
 * TODO(Задача 5): интеграция
 *  1. git submodule add https://github.com/heiher/hev-socks5-tunnel app/src/main/cpp/hev-socks5-tunnel
 *  2. собрать через ndk-build/CMake (см. README проекта: раздел "Android")
 *  3. JNI-функции запуска: передать tunFd и "127.0.0.1:1080"
 *  4. ВАЖНО: исходящие сокеты туннеля защищать VpnService.protect(),
 *     иначе петля трафика!
 */
object TunSocksBridge {

    /** Запустить мост. @return true при успехе. */
    fun start(tunFd: Int, socksHost: String = "127.0.0.1", socksPort: Int = 1080): Boolean {
        // TODO(Задача 5): вызов нативного API hev-socks5-tunnel
        TODO("Задача 5: интеграция hev-socks5-tunnel")
    }

    fun stop() {
        // TODO(Задача 5)
        TODO("Задача 5: интеграция hev-socks5-tunnel")
    }
}
