package com.dpibreak.core.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.dpibreak.MainActivity
import com.dpibreak.core.NotificationUtils
import com.dpibreak.core.ServiceManager
import com.dpibreak.core.ServiceState
import com.dpibreak.core.engine.ByedpiJniEngine
import com.dpibreak.core.tunnel.TunSocksBridge
import com.dpibreak.domain.Presets
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DPIBreak"

/**
 * VPN-сервис: собирает конвейер обхода по эталонной схеме ByeByeDPI
 * (`ByeDpiVpnService` + `NativeSession` + `PrivateDnsUtils`):
 *
 *   приложения → TUN-интерфейс → tun2socks (hev-socks5-tunnel, vendored)
 *   → SOCKS5 (byedpi, vendored) → интернет с десинхронизацией DPI.
 *
 * Ключевые решения из эталона ByeByeDPI:
 *  1) По умолчанию IPv6 в TUN ВЫКЛЮЧЕН (`ipv6Enabled = false`): на мобильных
 *     сетях без реального внешнего IPv6 маршрут `::/0` заставлял YouTube/Discord
 *     стучаться по IPv6, `tun2socks` локально завершал TCP-рукопожатие (отменяя
 *     откат Happy Eyeballs на IPv4), после чего `byedpi` получал `ENETUNREACH`.
 *  2) Если в Android настроен «Персональный DNS» (Private DNS / DoT), мы НЕ
 *     перезаписываем его через `addDnsServer("1.1.1.1")` (проверяется ДО `establish()`).
 *  3) В режиме всего трафика приложение исключает само себя на уровне ядра через
 *     `addDisallowedApplication(packageName)` и запускает `byedpi` ДО `establish()`,
 *     чтобы к моменту включения маршрутов прокси уже принимал пакеты. Для будущего
 *     белого списка приложений (Задача 7, `addAllowedApplication`) сохранён
 *     резервный сервер [ProtectSocketServer] (`-P`).
 */
class TunnelVpnService : VpnService() {

    /** Дескриптор TUN. `null` означает «конвейер не собран». */
    @Volatile private var tunInterface: ParcelFileDescriptor? = null

    /** Аргументы движка от выбранной стратегии (приходят с ACTION_START). */
    @Volatile private var engineArgs: List<String>? = null

    @Volatile private var ipv6Enabled: Boolean? = null
    @Volatile private var blockQuic: Boolean? = null

    /** Идёт ли сейчас сборка конвейера (нужно для осмысленных проверок в watchdog). */
    @Volatile private var startingTunnel = false

    /** Должен ли туннель быть запущен согласно последней команде. */
    @Volatile private var desiredRunning = false

    private val engine = ByedpiJniEngine()

    /** Единственный рабочий поток: здесь и запуск, и разбор конвейера. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DPIBreak-worker").apply { isDaemon = true }
    }

    /** Поток-смотритель за живостью туннеля и движка. */
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DPIBreak-watchdog").apply { isDaemon = true }
        }

    @Volatile private var watchdogTask: ScheduledFuture<*>? = null

    /** Сигнал отмены для прерывания идущего запуска при нажатии «Выключить». */
    private val stopping = AtomicBoolean(false)

    /** Защита от параллельной разборки конвейера двумя потоками. */
    private val cleaning = AtomicBoolean(false)

    /** Резервный Unix-сокет сервера protect() (для режимов без addDisallowedApplication). */
    @Volatile private var protectServer: ProtectSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!startInForeground()) {
                    return START_NOT_STICKY
                }
                engineArgs = intent.getStringArrayListExtra(EXTRA_ENGINE_ARGS)
                ipv6Enabled = if (intent.hasExtra(EXTRA_IPV6_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_IPV6_ENABLED, false)
                } else {
                    null
                }
                blockQuic = if (intent.hasExtra(EXTRA_BLOCK_QUIC)) {
                    intent.getBooleanExtra(EXTRA_BLOCK_QUIC, false)
                } else {
                    null
                }
                desiredRunning = true
                stopping.set(false)
                worker.execute { startTunnel() }
                return START_STICKY
            }

            null -> {
                if (!startInForeground()) {
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Service redelivered with null intent — resuming tunnel")
                engineArgs = null
                ipv6Enabled = null
                blockQuic = null
                desiredRunning = true
                stopping.set(false)
                worker.execute { startTunnel() }
                return START_STICKY
            }

            ACTION_STOP -> {
                desiredRunning = false
                stopping.set(true)
                worker.execute { stopTunnel() }
                return START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action '${intent.action}' — stopping")
                desiredRunning = false
                stopping.set(true)
                worker.execute { stopTunnel() }
                return START_NOT_STICKY
            }
        }
    }

    private fun startInForeground(): Boolean = try {
        val presetTitle = Presets.getSelected(this).title
        val notification = NotificationUtils.createNotification(this, contentIntent(this), presetTitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NotificationUtils.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground() failed", t)
        desiredRunning = false
        ServiceManager.updateState(
            ServiceState.Error(
                "Система запретила foreground-режим (${t.javaClass.simpleName}). " +
                    "Отключите оптимизацию батареи для DPIBreak и повторите."
            )
        )
        stopSelf()
        false
    }

    /**
     * Проверяет, включён ли в системе «Персональный DNS» (Private DNS / DoT).
     * Вызывать СТРОГО ДО `Builder.establish()`, пока активной сетью является
     * физический мобильный интернет или Wi-Fi (как в `PrivateDnsUtils` у ByeByeDPI).
     */
    private fun isPrivateDnsConfigured(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val linkProps = cm.getLinkProperties(network) ?: return false
            !linkProps.privateDnsServerName.isNullOrBlank()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read Private DNS state", t)
            false
        }
    }

    private fun buildTunnelBuilder(
        enableIpv6: Boolean,
        privateDnsConfigured: Boolean,
    ): Pair<Builder, Boolean> {
        val builder = Builder()
            .setSession("DPIBreak")
            .setConfigureIntent(contentIntent(this))
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (enableIpv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (!privateDnsConfigured) {
            builder.addDnsServer("1.1.1.1")
            if (enableIpv6) {
                builder.addDnsServer("2606:4700:4700::1111")
            }
        } else {
            Log.i(TAG, "Private DNS is configured in Android — skipping VPN DNS override")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Исключаем само приложение из TUN на уровне ядра (как в ByeByeDPI):
        // ни сокеты byedpi, ни системные DNS-запросы нашего процесса не уходят в петлю.
        val selfExcluded = runCatching {
            builder.addDisallowedApplication(packageName)
            true
        }.onFailure {
            Log.w(TAG, "addDisallowedApplication($packageName) failed, falling back to -P", it)
        }.getOrDefault(false)

        return builder to selfExcluded
    }

    /**
     * Поднимает весь конвейер. Выполняется ТОЛЬКО на [worker].
     */
    private fun startTunnel() {
        if (!desiredRunning || stopping.get()) {
            Log.w(TAG, "Stop requested before start — skipping")
            cleanup(restoreIdle = !desiredRunning)
            return
        }
        if (tunInterface != null) {
            Log.w(TAG, "Tunnel already running")
            ServiceManager.updateState(ServiceState.Active)
            return
        }
        startingTunnel = true
        try {
            val useIpv6 = ipv6Enabled ?: Presets.isIpv6Enabled(this)
            val dropQuic = blockQuic ?: Presets.isBlockQuicEnabled(this)
            val presetArgs = engineArgs ?: Presets.resolveEffectiveArgs(this)
            val privateDnsActive = isPrivateDnsConfigured()

            Log.i(
                TAG,
                "Starting tunnel pipeline (ipv6=$useIpv6, dropQuic=$dropQuic, " +
                    "privateDns=$privateDnsActive, args=$presetArgs)…"
            )

            engine.setDropQuic(dropQuic)
            val (builder, selfExcluded) = buildTunnelBuilder(useIpv6, privateDnsActive)

            val ipv6Args = if (!useIpv6 && "-X" !in presetArgs && "--no-ipv6" !in presetArgs) {
                listOf("-X")
            } else {
                emptyList()
            }

            val port: Int
            val fd: ParcelFileDescriptor

            if (selfExcluded) {
                // Эталонный порядок ByeByeDPI (NativeSession.startNative):
                // 1) поднимаем и проверяем SOCKS5 byedpi;
                // 2) открываем TUN-интерфейс (прокси уже слушает);
                // 3) запускаем tun2socks.
                val startPort = engine.start(listOf("-i", "127.0.0.1") + ipv6Args + presetArgs)
                if (startPort == null) {
                    abort(
                        "Движок byedpi не запустился. Проверьте синтаксис строки аргументов " +
                            "(в logcat ищите теги DPIBreak и proxy)."
                    )
                    return
                }
                port = startPort
                if (stopRequestedDuringStart()) return

                val established = runCatching { builder.establish() }
                    .onFailure { Log.e(TAG, "TUN establish threw", it) }
                    .getOrNull()
                if (established == null) {
                    abort("Не удалось создать TUN-интерфейс (разрешение VPN отозвано?)")
                    return
                }
                fd = established
                tunInterface = fd
            } else {
                // Резервный путь через ProtectSocketServer (-P): сначала нужен активный TUN,
                // чтобы Android разрешил VpnService.protect().
                val established = runCatching { builder.establish() }
                    .onFailure { Log.e(TAG, "TUN establish threw", it) }
                    .getOrNull()
                if (established == null) {
                    abort("Не удалось создать TUN-интерфейс (разрешение VPN отозвано?)")
                    return
                }
                fd = established
                tunInterface = fd
                if (stopRequestedDuringStart()) return

                val protectPath = File(cacheDir, PROTECT_SOCKET_NAME).absolutePath
                val server = try {
                    ProtectSocketServer(this, protectPath)
                } catch (t: Throwable) {
                    Log.e(TAG, "Protect socket server failed to start", t)
                    abort("Не удалось поднять сервер protect(): ${t.message}")
                    return
                }
                protectServer = server
                if (stopRequestedDuringStart()) return

                val startPort = engine.start(
                    listOf("-i", "127.0.0.1", "-P", protectPath) + ipv6Args + presetArgs
                )
                if (startPort == null) {
                    abort(
                        "Движок byedpi не запустился. Проверьте синтаксис строки аргументов " +
                            "(в logcat ищите теги DPIBreak и proxy)."
                    )
                    return
                }
                port = startPort
            }

            Log.i(TAG, "TUN established (fd=${fd.fd}), byedpi listening on 127.0.0.1:$port")
            if (stopRequestedDuringStart()) return

            if (!TunSocksBridge.start(fd.fd, cacheDir, "127.0.0.1", port)) {
                abort("Туннель tun2socks не запустился")
                return
            }

            Log.i(TAG, "Pipeline is UP: TUN → tun2socks → byedpi:$port")
            ServiceManager.updateState(ServiceState.Active)
            armWatchdog()
        } finally {
            startingTunnel = false
            if (stopping.get() && !desiredRunning) {
                cleanup(restoreIdle = true)
            }
        }
    }

    private fun stopRequestedDuringStart(): Boolean {
        if (!stopping.get() && desiredRunning) return false
        Log.w(TAG, "Stop requested during start — rolling back the started part")
        cleanup(restoreIdle = !desiredRunning)
        return true
    }

    private fun abort(message: String) {
        Log.e(TAG, message)
        desiredRunning = false
        ServiceManager.updateState(ServiceState.Error(message))
        cleanup(restoreIdle = false)
    }

    private fun stopTunnel() {
        cleanup(restoreIdle = !desiredRunning)
    }

    /**
     * Разбор конвейера в обратном порядке: tun2socks → движок → protectServer → TUN.
     */
    private fun cleanup(restoreIdle: Boolean) {
        if (!cleaning.compareAndSet(false, true)) {
            Log.i(TAG, "Cleanup already in progress — ignoring repeat")
            return
        }
        try {
            Log.i(TAG, "Stopping tunnel…")
            watchdogTask?.cancel(false)
            watchdogTask = null
            runCatching { TunSocksBridge.stop() }
            runCatching { engine.stop() }
            runCatching { protectServer?.shutdown() }
            protectServer = null
            runCatching { tunInterface?.close() }
            tunInterface = null
        } catch (t: Throwable) {
            Log.e(TAG, "cleanup failed", t)
        } finally {
            if (!desiredRunning) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                if (restoreIdle) {
                    ServiceManager.updateState(ServiceState.Idle)
                }
            }
            cleaning.set(false)
            Log.i(TAG, "Tunnel stopped")
        }
    }

    private fun armWatchdog() {
        watchdogTask?.cancel(false)
        watchdogTask = watchdog.scheduleWithFixedDelay({
            if (startingTunnel || stopping.get() || !desiredRunning || tunInterface == null) {
                return@scheduleWithFixedDelay
            }
            try {
                when {
                    !TunSocksBridge.isRunning() ->
                        abortFromWatchdog(
                            "Туннель tun2socks остановлен (поток умер). Соберите logcat с тегом DPIBreak"
                        )

                    !engine.isRunning.value ->
                        abortFromWatchdog(
                            "Движок byedpi остановлен системой. Проверьте оптимизацию батареи для DPIBreak"
                        )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog check failed", t)
            }
        }, 1L, HEALTH_INTERVAL_SEC, TimeUnit.SECONDS)
    }

    private fun abortFromWatchdog(message: String) {
        worker.execute {
            if (!startingTunnel && !stopping.get() && desiredRunning && tunInterface != null) {
                abort(message)
            }
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN permission revoked")
        desiredRunning = false
        stopping.set(true)
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        desiredRunning = false
        stopping.set(true)
        watchdogTask?.cancel(false)
        watchdog.shutdownNow()
        if (tunInterface != null || protectServer != null) {
            Log.w(TAG, "onDestroy with active tunnel — emergency cleanup in worker")
            worker.execute { cleanup(restoreIdle = true) }
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dpibreak.START"
        const val ACTION_STOP = "com.dpibreak.STOP"

        /** StringArrayListExtra: аргументы движка от выбранной стратегии. */
        const val EXTRA_ENGINE_ARGS = "com.dpibreak.ENGINE_ARGS"

        /** BooleanExtra: включать ли IPv6 в TUN (по умолчанию false, как в ByeByeDPI). */
        const val EXTRA_IPV6_ENABLED = "com.dpibreak.IPV6_ENABLED"

        /** BooleanExtra: блокировать ли QUIC (UDP 443) для перехода YouTube на TCP. */
        const val EXTRA_BLOCK_QUIC = "com.dpibreak.BLOCK_QUIC"

        /** Имя Unix-сокета сервера protect() в cacheDir (путь < 108 байт — лимит sun_path). */
        private const val PROTECT_SOCKET_NAME = "pr.sock"

        /** Интервал контроля живости конвейера (в секундах). */
        private const val HEALTH_INTERVAL_SEC = 30L

        fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}

/**
 * Резервный сервер protect() для нативного ядра byedpi (при запуске с `-P <path>`).
 *
 * ВНИМАНИЕ: при переименовании класса/пакета обновить имена JNI-функций в
 * `native-lib.c` и правило в `app/proguard-rules.pro`.
 */
internal class ProtectSocketServer(
    private val vpnService: VpnService,
    private val socketPath: String,
) {

    private val closed = AtomicBoolean(false)
    private val serverFd: Int
    private val acceptThread: Thread

    init {
        val fd = jniCreateSocket(socketPath)
        if (fd < 0) {
            throw IOException("Не удалось создать Unix-сокет protect: $socketPath")
        }
        serverFd = fd
        acceptThread = Thread({ acceptLoop() }, "DPIBreak-protect").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        try {
            while (!closed.get()) {
                val clientFd = jniAcceptClient(serverFd)
                if (clientFd < 0) {
                    if (closed.get()) break
                    continue
                }
                handleClient(clientFd)
            }
        } finally {
            jniCloseFd(serverFd)
            runCatching { File(socketPath).delete() }
        }
    }

    private fun handleClient(clientFd: Int) {
        var ok = false
        try {
            val connFd = jniRecvProtectedFd(clientFd)
            if (connFd >= 0) {
                try {
                    ok = vpnService.protect(connFd)
                    if (!ok) {
                        Log.w(TAG, "VpnService.protect() returned false")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "protect() threw", t)
                } finally {
                    jniCloseFd(connFd)
                }
            } else {
                Log.w(TAG, "no fd received on protect socket")
            }
        } finally {
            jniReplyAndClose(clientFd, ok)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        jniShutdownSocket(serverFd)
        runCatching { File(socketPath).delete() }
        if (acceptThread !== Thread.currentThread()) {
            runCatching { acceptThread.join(1_000) }
        }
    }

    private external fun jniCreateSocket(path: String): Int
    private external fun jniAcceptClient(serverFd: Int): Int
    private external fun jniRecvProtectedFd(clientFd: Int): Int
    private external fun jniReplyAndClose(clientFd: Int, ok: Boolean)
    private external fun jniShutdownSocket(fd: Int)
    private external fun jniCloseFd(fd: Int)

    companion object {
        init {
            System.loadLibrary("dpibreak")
        }
    }
}
