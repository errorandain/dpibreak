package com.dpibreak.core.tunnel

import android.util.Log
import java.io.File

/**
 * Мост «TUN → SOCKS5» на базе hev-socks5-tunnel (tun2socks).
 * https://github.com/heiher/hev-socks5-tunnel (MIT)
 *
 * Реализация — vendored-копия в app/src/main/jni/hev-socks5-tunnel,
 * собирается ndk-build'ом вместе с нашей библиотекой.
 *
 * JNI-методы регистрируются нативной стороной при загрузке библиотеки
 * (JNI_OnLoad) на ЭТОТ класс: имена пакета и класса зашиваются через
 * -DPKGNAME / -DCLSNAME в app/src/main/jni/Application.mk.
 * При переименовании класса/пакета — обновить Application.mk!
 */
object TunSocksBridge {

    private const val TAG = "DPIBreak"

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Запустить туннель: читает конфиг из configPath, работает с TUN по fd. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    /** Остановить туннель (блокирующий — зовуть из фонового потока). */
    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    @Suppress("unused")
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Suppress("unused")
    external fun TProxyGetStats(): LongArray

    /**
     * Запустить туннель: TUN(fd) → SOCKS5(socksHost:socksPort).
     * Конфиг (YAML) создаётся автоматически во временной папке cacheDir.
     *
     * @return true при успешном запуске
     */
    fun start(
        tunFd: Int,
        cacheDir: File,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 1080,
    ): Boolean {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("socks5:")
            appendLine("  address: $socksHost")
            appendLine("  port: $socksPort")
            appendLine("  udp: udp")
        }
        val configFile = File.createTempFile("hev-config", ".tmp", cacheDir)
        configFile.writeText(config)
        Log.d(TAG, "Starting tun2socks: fd=$tunFd, socks5=$socksHost:$socksPort")
        return TProxyStartService(configFile.absolutePath, tunFd)
    }

    /** Остановить туннель. Безопасно при незапущенном туннеле. */
    fun stop() {
        Log.d(TAG, "Stopping tun2socks")
        runCatching { TProxyStopService() }
            .onFailure { Log.e(TAG, "Failed to stop tun2socks: ${it.message}") }
    }
}
