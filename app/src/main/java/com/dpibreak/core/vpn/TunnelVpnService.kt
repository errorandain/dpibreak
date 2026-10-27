package com.dpibreak.core.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * VPN-сервис: собирает конвейер обхода.
 *
 *   приложения → TUN-интерфейс → tun2socks (hev-socks5-tunnel, vendored)
 *   → SOCKS5 (byedpi, vendored) → интернет с десинхронизацией DPI.
 *
 * Порядок запуска: startForeground → TUN → ProtectSocketServer → движок byedpi → tun2socks.
 * Порядок остановки — строго обратный.
 *
 * ### Почему всё делает один сериализованный поток ([worker])
 * Запуск и разборка выполняются строго по очереди в одном потоке [worker],
 * чтобы исключить гонки за дескриптор TUN и нативные потоки. При этом сигнал
 * [stopping] выставляется сразу при приходе команды остановки — если конвейер
 * ещё поднимается, он прервётся между этапами, не дожидаясь полного таймаута.
 *
 * ### Почему нельзя блокировать главный поток
 * `TProxyStopService()` (join потока туннеля) и остановка движка (до 2,5 с) —
 * блокирующие. В главном потоке это ANR/зависание при остановке. Поэтому и
 * `onDestroy` только ставит задачу в очередь, а не разбирает конвейер сам.
 */
class TunnelVpnService : VpnService() {

    /**
     * Дескриптор TUN. [Volatile] — читается из watchdog-потока, пишется из [worker].
     * `null` означает «конвейер не собран».
     */
    @Volatile private var tunInterface: ParcelFileDescriptor? = null

    /** Аргументы движка от выбранного пресета (приходят с ACTION_START). */
    @Volatile private var engineArgs: List<String>? = null

    /** Идёт ли сейчас сборка конвейера (нужно для осмысленных проверок в watchdog). */
    @Volatile private var startingTunnel = false

    /**
     * Должен ли туннель быть запущен согласно последней команде, пришедшей в сервис.
     * Защищает быстрый сценарий «Выкл → Вкл»: старая остановка не вызовет stopSelf(),
     * если следом уже пришёл новый ACTION_START.
     */
    @Volatile private var desiredRunning = false

    private val engine = ByedpiJniEngine()

    /** Единственный рабочий поток: здесь и запуск, и разбор конвейера. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DPIBreak-worker").apply { isDaemon = true }
    }

    /**
     * Поток-смотритель. Раз в [HEALTH_INTERVAL_SEC] проверяет, что поток туннеля
     * и движок живы. Без него «тихая смерть» туннеля (убитый системой byedpi,
     * умерший поток tun2socks) выглядит как «всё включено, но интернета нет».
     */
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DPIBreak-watchdog").apply { isDaemon = true }
        }

    @Volatile private var watchdogTask: ScheduledFuture<*>? = null

    /** Сигнал отмены для прерывания идущего запуска при нажатии «Выключить». */
    private val stopping = AtomicBoolean(false)

    /** Защита от параллельной разборки конвейера двумя потоками. */
    private val cleaning = AtomicBoolean(false)

    /**
     * Unix-сокет сервера protect() (см. [ProtectSocketServer]). Не null — пока
     * конвейер собран. Останавливается в [cleanup] после движка и перед закрытием TUN.
     */
    @Volatile private var protectServer: ProtectSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        // Канал создаётся в App.onCreate; продублируем здесь — сервис обязан иметь
        // канал ДО первого startForeground(), иначе часть оболочек тихо теряет уведомление.
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!startInForeground()) {
                    return START_NOT_STICKY
                }
                engineArgs = intent.getStringArrayListExtra(EXTRA_ENGINE_ARGS)
                desiredRunning = true
                stopping.set(false)
                worker.execute { startTunnel() }
                return START_STICKY
            }

            // Перерождение сервиса системой после убийства процесса: пресет берём
            // из сохранённых настроек; обязательно сразу вызываем startForeground().
            null -> {
                if (!startInForeground()) {
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Service redelivered with null intent — resuming tunnel")
                engineArgs = null
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

    /**
     * Выводит сервис на передний план.
     * @return false, если система запретила foreground (Android 12+ может отказать
     *  при запуске из фона) — тогда сервис останавливается с внятной ошибкой.
     */
    private fun startInForeground(): Boolean = try {
        val presetTitle = Presets.getSelected(this).title
        val notification = NotificationUtils.createNotification(this, contentIntent(this), presetTitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: тип foreground-сервиса обязателен (specialUse — в манифесте)
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
     * Поднимает весь конвейер. Выполняется ТОЛЬКО на [worker].
     * О любой ошибке сообщает через [ServiceManager] — текст виден на главном
     * экране и сильно упрощает диагностику.
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
            Log.i(TAG, "Starting tunnel pipeline…")

            // 1. TUN-интерфейс через VpnService.Builder (без root).
            //    Анти-петля обеспечивается сервером protect() на шаге 2: каждое
            //    исходящее в интернет соединение byedpi помечается VpnService.protect().
            val fd = runCatching {
                Builder()
                    .setSession("DPIBreak")
                    .addAddress("10.111.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    // IPv6: адрес fd00::2/128 и маршрут ::/0, чтобы v6-трафик
                    // тоже шёл через туннель, а не мимо него. Если сеть без IPv6,
                    // Android просто не использует v6-маршрут.
                    .addAddress("fd00::2", 128)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("2606:4700:4700::1111")
                    .setBlocking(true)
                    .establish()
            }.onFailure { Log.e(TAG, "TUN establish threw", it) }.getOrNull()

            if (fd == null) {
                abort("Не удалось создать TUN-интерфейс (разрешение VPN отозвано?)")
                return
            }
            tunInterface = fd
            Log.i(TAG, "TUN established, fd=${fd.fd}")
            if (stopRequestedDuringStart()) return

            // 2. Сервер protect() — ОБЯЗАТЕЛЬНО до движка (см. ProtectSocketServer).
            //    Без него исходящие сокеты byedpi попадают в тот же TUN → петля
            //    трафика: «включено, но интернета нет».
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

            // 3. Движок byedpi: локальный SOCKS5 на 127.0.0.1.
            //    -P <path>: каждое исходящее соединение ядро маркирует через
            //    VpnService.protect() (передача fd по Unix-сокету в файловой системе).
            val presetArgs = engineArgs ?: Presets.getSelected(this).byedpiArgs
            Log.i(TAG, "Engine preset args: $presetArgs")
            val port = engine.start(listOf("-i", "127.0.0.1", "-P", protectPath) + presetArgs)
            if (port == null) {
                abort(
                    "Движок byedpi не запустился (порт ${ByedpiJniEngine.DEFAULT_SOCKS_PORT} не открылся). " +
                        "В logcat ищите тег proxy — там причина."
                )
                return
            }
            Log.i(TAG, "byedpi SOCKS5 listening on 127.0.0.1:$port")
            if (stopRequestedDuringStart()) return

            // 4. tun2socks: TUN → SOCKS5.
            if (!TunSocksBridge.start(fd.fd, cacheDir, "127.0.0.1", port)) {
                abort("Туннель tun2socks не запустился")
                return
            }

            // 5. Готово. Дальше за состоянием следит watchdog.
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

    /**
     * Проверка между этапами запуска: остановку мог запросить пользователь,
     * пока мы ждали порт SOCKS5. Возвращает true, если дальше идти нельзя.
     */
    private fun stopRequestedDuringStart(): Boolean {
        if (!stopping.get() && desiredRunning) return false
        Log.w(TAG, "Stop requested during start — rolling back the started part")
        cleanup(restoreIdle = !desiredRunning)
        return true
    }

    /** Провал на одном из этапов запуска: человекочитаемая ошибка + разборка. */
    private fun abort(message: String) {
        Log.e(TAG, message)
        desiredRunning = false
        // restoreIdle = false: не затираем текст ошибки, который только что выставили.
        ServiceManager.updateState(ServiceState.Error(message))
        cleanup(restoreIdle = false)
    }

    /**
     * Штатная остановка на потоке [worker].
     */
    private fun stopTunnel() {
        cleanup(restoreIdle = !desiredRunning)
    }

    /**
     * Разбор конвейера в обратном порядке: tun2socks → движок → protectServer → TUN.
     * Идемпотентен: повторный вызов из другого потока просто выходит.
     *
     * @param restoreIdle false, если состояние уже [ServiceState.Error] — не затираем текст.
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
            // Сервер protect() гасим ПОСЛЕ движка (ядро может ещё слать запросы на
            // -P сокет при завершении) и ДО закрытия TUN.
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

    /**
     * Смотритель за живостью конвейера.
     *
     * Первая проверка — через 1 с после старта, дальше — раз в [HEALTH_INTERVAL_SEC] с.
     */
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

    /**
     * Реакция watchdog'а. Разборку выполняем на [worker] и ПОВТОРНО проверяем
     * состояние уже там: пока задача ждала своей очереди, конвейер мог штатно
     * остановиться или перезапуститься.
     */
    private fun abortFromWatchdog(message: String) {
        worker.execute {
            if (!startingTunnel && !stopping.get() && desiredRunning && tunInterface != null) {
                abort(message)
            }
        }
    }

    /** Разрешение VPN отозвано пользователем из системных настроек. */
    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN permission revoked")
        desiredRunning = false
        stopping.set(true)
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        // Если сервис убит системой без штатной остановки — прибираемся в worker
        // (блокирующие вызовы в главном потоке приведут к ANR).
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

        /** StringArrayListExtra: аргументы движка от пресета. */
        const val EXTRA_ENGINE_ARGS = "com.dpibreak.ENGINE_ARGS"

        /** Имя Unix-сокета сервера protect() в cacheDir (путь < 108 байт — лимит sun_path). */
        private const val PROTECT_SOCKET_NAME = "pr.sock"

        /** Интервал контроля живости конвейера (в секундах). */
        private const val HEALTH_INTERVAL_SEC = 30L

        /** PendingIntent: открыть приложение по нажатию на уведомление. */
        fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}

/**
 * Сервер protect() для нативного ядра byedpi.
 *
 * Когда VPN-туннель активен, трафик нашего процесса шёл бы обратно в TUN (петля).
 * При запуске с `-P <path>` ядро byedpi перед каждым исходящим соединением
 * подключается к нашему Unix-сокету в файловой системе (`cacheDir/pr.sock`) и
 * одним сообщением `sendmsg()` присылает 1 байт-маркер + fd сокета в SCM_RIGHTS
 * (см. `byedpi/extend.c:protect`). Сервер вызывает [VpnService.protect] и
 * отвечает одним байтом: `1` = успех, `0` = отказ.
 *
 * Вся работа с AF_UNIX в файловой системе и приёмом SCM_RIGHTS вынесена в C
 * (`native-lib.c`), потому что Android `LocalServerSocket(String)` биндится в
 * abstract namespace, а `LocalSocket.getAncillaryFileDescriptors()` теряет fd.
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
                    // VpnService.protect(int) помечает сокет защищённым и НЕ забирает
                    // владение дескриптором — полученную из SCM_RIGHTS копию закрываем сами.
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

    /** Идемпотентная остановка: будит accept() через shutdown() и ждёт поток. */
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
