package com.dpibreak.core.tunnel

import android.util.Log
import java.io.File

/**
 * Мост «TUN → SOCKS5» на базе hev-socks5-tunnel (tun2socks).
 * https://github.com/heiher/hev-socks5-tunnel (MIT)
 *
 * Реализация — vendored-копия в app/src/main/jni/hev-socks5-tunnel,
 * собирается ndk-build'ом как отдельная библиотека `libhev-socks5-tunnel.so`.
 *
 * JNI-методы регистрируются нативной стороной при загрузке библиотеки
 * (JNI_OnLoad) на ЭТОТ класс: имена пакета и класса зашиваются через
 * -DPKGNAME / -DCLSNAME в app/src/main/jni/Application.mk.
 * При переименовании класса/пакета — обновить Application.mk И keep-правило
 * в app/proguard-rules.pro (иначе в release FindClass не найдёт класс →
 * UnsatisfiedLinkError).
 */
object TunSocksBridge {

    private const val TAG = "DPIBreak"

    /** Служебный DNS-адрес внутри туннеля: его перехватывает mapdns. */
    const val MAPDNS_ADDRESS = "198.18.0.2"

    /**
     * Файл конфига. Имя фиксированное (не tempFile): файл перезаписывается при
     * каждом старте и удаляется при остановке — иначе в cacheDir копился бы мусор.
     */
    private const val CONFIG_FILE_NAME = "tun2socks.yml"

    @Volatile private var configFile: File? = null

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Запустить туннель: читает конфиг из configPath, работает с TUN по fd. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    /** Остановить туннель (блокирующий — звать только из фонового потока). */
    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    @Suppress("unused")
    external fun TProxyGetStats(): LongArray

    /**
     * Запустить туннель: TUN(fd) → SOCKS5(socksHost:socksPort).
     * Конфиг (YAML) формируется автоматически и кладётся во временную папку [cacheDir].
     *
     * Поле `tunnel.mtu` = 8500 НЕ задаёт MTU интерфейса: при переданном извне fd
     * туннель использует его только как размер буфера чтения (см. tunnel_init() в
     * src/hev-socks5-tunnel.c). Уменьшать до 1500 не стоит — Android-сеть иногда
     * отдаёт пакеты крупнее, и буфер должен влезать.
     *
     * @return true при успешном запуске
     */
    fun start(
        tunFd: Int,
        cacheDir: File,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 1080,
    ): Boolean {
        if (isRunning()) {
            Log.e(TAG, "tun2socks is already running — stop it first")
            return false
        }
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            // MapDNS (Задача 6): DNS-запросы приложений к MAPDNS_ADDRESS:53
            // перехватываются внутри туннеля и отвечаются «фейковыми» IP из
            // сети 100.64.0.0/10. Когда приложение обращается к такому IP,
            // в SOCKS5 уходит ДОМЕН (обратный lookup) — движок byedpi видит
            // домен, работают фильтры -H, а DNS жив даже при -U (без UDP).
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS_ADDRESS")
            appendLine("  port: 53")
            appendLine("  network: 100.64.0.0")
            appendLine("  netmask: 255.192.0.0")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("socks5:")
            appendLine("  address: $socksHost")
            appendLine("  port: $socksPort")
            appendLine("  udp: udp")
        }
        val file = File(cacheDir, CONFIG_FILE_NAME)
        return try {
            file.writeText(config)
            configFile = file
            Log.d(TAG, "Starting tun2socks: fd=$tunFd, socks5=$socksHost:$socksPort")
            val started = TProxyStartService(file.absolutePath, tunFd)
            if (!started) {
                // Причины печатает собственный логгер туннеля: уровень и файл
                // настраиваются в конфиге (misc: log-level / log-file).
                Log.e(TAG, "TProxyStartService returned false — детали в логе tun2socks (misc.log-level)")
                deleteConfig()
            }
            started
        } catch (t: Throwable) {
            Log.e(TAG, "tun2socks start failed", t)
            deleteConfig()
            false
        }
    }

    /** Остановить туннель. Безопасно при незапущенном туннеле. */
    fun stop() {
        Log.d(TAG, "Stopping tun2socks")
        runCatching { TProxyStopService() }
            .onFailure { Log.e(TAG, "Failed to stop tun2socks: ${it.message}") }
        deleteConfig()
    }

    /** Жив ли поток туннеля (для watchdog-проверок). */
    fun isRunning(): Boolean = runCatching { TProxyIsRunning() }
        .onFailure { Log.e(TAG, "TProxyIsRunning failed: ${it.message}") }
        .getOrDefault(false)

    private fun deleteConfig() {
        configFile?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }
        configFile = null
    }
}
