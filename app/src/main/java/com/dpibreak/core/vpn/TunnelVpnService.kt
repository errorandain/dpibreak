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
 * Порядок запуска: startForeground → TUN → движок byedpi → tun2socks.
 * Порядок остановки — строго обратный.
 *
 * ### Почему всё делает один сериализованный поток ([worker])
 * Раньше запуск шёл в одной корутине, а остановка — в другой, и они могли
 * пересечься: остановка закрывала дескриптор TUN, пока запуск продолжал с ним
 * работать и выставлял статус Active (UI «работает», интернета нет; закрытый fd
 * к тому же мог быть переиспользован другим потоком). Один поток-очередь делает
 * такие гонки невозможными: команды выполняются строго по одной.
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

    /** Идёт ли сейчас сборка конвейера (нужно для осмысленных сообщений в watchdog). */
    @Volatile private var startingTunnel = false

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

    /** Защита от повторного входа в остановку (кнопка + onDestroy + onRevoke + watchdog). */
    private val stopping = AtomicBoolean(false)

    /** Защита от параллельной разборки конвейера двумя потоками. */
    private val cleaning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Канал создаётся в App.onCreate; продублируем здесь — сервис обязан иметь
        // канал ДО первого startForeground(), иначе часть оболочек тихо теряет уведомление.
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Любую команду — в первую очередь выводим сервис на передний план.
        //    Требование Android 8+: после startForegroundService() обязан быть
        //    startForeground() в пределах ~5 с. Отдельно важен null-intent:
        //    при START_STICKY система пересылает именно null-интент после убийства
        //    процесса, и раньше мы в этом случае startForeground() не вызывали вовсе
        //    → ForegroundServiceDidNotStartInTimeException (вылет на Samsung).
        if (!startInForeground()) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {
                engineArgs = intent?.getStringArrayListExtra(EXTRA_ENGINE_ARGS)
                worker.execute { startTunnel() }
            }

            // Перерождение сервиса системой: пресет берём из сохранённых настроек,
            // разрешение VPN при этом не сбрасывается — конвейер можно поднять снова.
            null -> {
                Log.i(TAG, "Service redelivered with null intent — resuming tunnel")
                engineArgs = null
                worker.execute { startTunnel() }
            }

            ACTION_STOP -> worker.execute { stopTunnel() }

            else -> {
                Log.w(TAG, "Unknown action '${intent?.action}' — stopping")
                worker.execute { stopTunnel() }
            }
        }
        return START_STICKY
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
     * экране и сильно упрощает диагностику (пользователь без adb тоже поймёт, что не так).
     */
    private fun startTunnel() {
        if (tunInterface != null) {
            Log.w(TAG, "Tunnel already running")
            ServiceManager.updateState(ServiceState.Active)
            return
        }
        if (stopping.get()) {
            Log.w(TAG, "Stop requested — skipping start")
            ServiceManager.updateState(ServiceState.Idle)
            return
        }
        startingTunnel = true
        try {
            Log.i(TAG, "Starting tunnel pipeline…")

            // 1. TUN-интерфейс через VpnService.Builder (без root).
            //    Настройки повторяют проверенный эталон (ByeByeDPI): адрес /32,
            //    маршрут по умолчанию, DNS; своё приложение исключено — так решается
            //    анти-петля (трафик движка и туннеля идёт мимо VPN).
            //    MTU не задаём: при переданном fd туннель использует его только как
            //    размер буфера чтения, на интерфейс не влияет.
            //    establish() может бросить SecurityException, если разрешение VPN
            //    отозвали в момент вызова (OEM-оболочки), — поэтому в runCatching.
            val fd = runCatching {
                Builder()
                    .setSession("DPIBreak")
                    .addAddress("10.111.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(packageName)
                    .establish()
            }.onFailure { Log.e(TAG, "TUN establish threw", it) }.getOrNull()

            if (fd == null) {
                abort("Не удалось создать TUN-интерфейс (разрешение VPN отозвано?)")
                return
            }
            tunInterface = fd
            Log.i(TAG, "TUN established, fd=${fd.fd}")
            if (stopRequestedDuringStart()) return

            // 2. Движок byedpi: локальный SOCKS5 на 127.0.0.1.
            //    Аргументы — от пресета, выбранного в UI (или сохранённого,
            //    если сервис перезапущен системой с null-intent).
            val presetArgs = engineArgs ?: Presets.getSelected(this).byedpiArgs
            Log.i(TAG, "Engine preset args: $presetArgs")
            val port = engine.start(listOf("-i", "127.0.0.1") + presetArgs)
            if (port == null) {
                abort(
                    "Движок byedpi не запустился (порт 1080 не открылся). " +
                        "В logcat ищите тег proxy — там причина."
                )
                return
            }
            Log.i(TAG, "byedpi SOCKS5 listening on 127.0.0.1:$port")
            if (stopRequestedDuringStart()) return

            // 3. tun2socks: TUN → SOCKS5.
            if (!TunSocksBridge.start(fd.fd, cacheDir, "127.0.0.1", port)) {
                abort("Туннель tun2socks не запустился")
                return
            }

            // 4. Готово. Дальше за состоянием следит watchdog.
            Log.i(TAG, "Pipeline is UP: TUN → byedpi:$port → tun2socks")
            ServiceManager.updateState(ServiceState.Active)
            armWatchdog()
        } finally {
            startingTunnel = false
            // Если остановка пришла, пока мы поднимали конвейер, очередь уже
            // выполнила разборку — на всякий случай повторяем (cleanup идемпотентен).
            if (stopping.get()) cleanup(restoreIdle = true)
        }
    }

    /**
     * Проверка между этапами запуска: остановку мог запросить пользователь,
     * пока мы ждали порт SOCKS5. Возвращает true, если дальше идти нельзя.
     */
    private fun stopRequestedDuringStart(): Boolean {
        if (!stopping.get()) return false
        Log.w(TAG, "Stop requested during start — rolling back the started part")
        cleanup(restoreIdle = true)
        return true
    }

    /** Провал на одном из этапов запуска: человекочитаемая ошибка + разборка. */
    private fun abort(message: String) {
        Log.e(TAG, message)
        // restoreIdle = false: не затираем текст ошибки, который только что выставили.
        ServiceManager.updateState(ServiceState.Error(message))
        cleanup(restoreIdle = false)
    }

    /**
     * Штатная остановка. Только через [worker] (сериализация) и только один раз.
     */
    private fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) {
            Log.i(TAG, "Stop already in progress — ignoring repeat")
            return
        }
        cleanup(restoreIdle = true)
        stopping.set(false)
    }

    /**
     * Разбор конвейера в обратном порядке: tun2socks → движок → TUN.
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
            runCatching { tunInterface?.close() }
            tunInterface = null
        } catch (t: Throwable) {
            Log.e(TAG, "cleanup failed", t)
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (restoreIdle) {
                ServiceManager.updateState(ServiceState.Idle)
            }
            cleaning.set(false)
            Log.i(TAG, "Tunnel stopped")
        }
    }

    /**
     * Смотритель за живостью конвейера.
     *
     * Первая проверка — через 1 с: исторически поток туннеля умирал сразу после
     * старта, и UI показывал «работает» без интернета. Дальше — раз в
     * [HEALTH_INTERVAL_SEC]: на агрессивных оболочках (Samsung Low Power Mode,
     * «оптимизация работы в фоновом режиме») половину туннеля могут убить
     * через полчаса, и пользователь должен увидеть причину, а не молчаливый ноль.
     */
    private fun armWatchdog() {
        watchdogTask?.cancel(false)
        watchdogTask = watchdog.scheduleWithFixedDelay({
            // Идёт сборка конвейера — не вмешиваемся.
            if (startingTunnel || stopping.get() || tunInterface == null) return@scheduleWithFixedDelay
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
        }, FIRST_CHECK_DELAY_MS, HEALTH_INTERVAL_SEC, TimeUnit.SECONDS)
    }

    /**
     * Реакция watchdog'а. Разборку выполняем на [worker] и ПОВТОРНО проверяем
     * состояние уже там: пока задача ждала своей очереди, конвейер мог штатно
     * остановиться или, наоборот, подняться — иначе watchdog убил бы здоровый туннель.
     */
    private fun abortFromWatchdog(message: String) {
        worker.execute {
            if (!startingTunnel && !stopping.get() && tunInterface != null) {
                abort(message)
            }
        }
    }

    /** Разрешение VPN отозвано пользователем из системных настроек. */
    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN permission revoked")
        worker.execute { stopTunnel() }
        // Реализация по умолчанию делает stopSelf(); вызываем явно, чтобы система
        // считала сервис корректно остановленным, а не «убитым».
        super.onRevoke()
    }

    override fun onDestroy() {
        // Если сервис убит системой без штатной остановки — прибираемся, но НЕ
        // здесь (разборка блокирующая → ANR в главном потоке). Задачу кладём в
        // тот же worker и закрываем его: поставленное выполнится, новое — нет.
        watchdogTask?.cancel(false)
        watchdog.shutdownNow()
        if (tunInterface != null) {
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

        /** Первая проверка живости — через секунду после старта. */
        private const val FIRST_CHECK_DELAY_MS = 1000L

        /** Интервал дальнейшего контроля конвейера. */
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
