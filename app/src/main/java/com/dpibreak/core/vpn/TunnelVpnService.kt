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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DPIBreak"

/**
 * VPN-сервис: собирает конвейер обхода.
 *
 *   приложения → TUN-интерфейс → tun2socks (hev-socks5-tunnel, vendored)
 *   → SOCKS5 (byedpi, vendored) → интернет с десинхронизацией DPI.
 *
 * Порядок запуска: TUN → движок byedpi → tun2socks.
 * Порядок остановки — обратный, на фоновом потоке, с защитой от повторного
 * входа: некорректная последовательность остановки вызывала вылет приложения
 * (двойное закрытие сокета движка и блокировка главного потока).
 */
class TunnelVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private val engine = ByedpiJniEngine()
    private var healthJob: Job? = null

    /** Аргументы движка от выбранного пресета (приходят с ACTION_START). */
    @Volatile private var engineArgs: List<String>? = null

    /** Все фоновые работы сервиса. */
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    /** Защита от повторного входа в остановку (кнопка + onDestroy + onRevoke). */
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                engineArgs = intent?.getStringArrayListExtra(EXTRA_ENGINE_ARGS)
                startInForeground()
                if (stopping.get()) {
                    Log.w(TAG, "Start requested while stopping — ignoring")
                    return START_STICKY
                }
                serviceScope.launch { startTunnel() }
            }
            ACTION_STOP -> {
                serviceScope.launch { stopTunnel() }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val presetTitle = Presets.getSelected(this).title
        val pendingIntent = contentIntent(this)
        val notification = NotificationUtils.createNotification(this, pendingIntent, presetTitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: тип foreground-сервиса обязателен (specialUse — в манифесте)
            ServiceCompat.startForeground(
                this, NotificationUtils.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Поднимает весь конвейер (вызывается на Dispatchers.IO).
     * О любой ошибке сообщает через [ServiceManager.updateState] — текст
     * виден на главном экране приложения и сильно упрощает диагностику.
     */
    private fun startTunnel() {
        if (tunInterface != null) {
            Log.w(TAG, "Tunnel already running")
            return
        }
        Log.i(TAG, "Starting tunnel pipeline…")

        // 1. TUN-интерфейс через VpnService.Builder (без root).
        //    Настройки повторяют проверенный эталон (ByeByeDPI):
        //    адрес /32, маршрут по умолчанию, DNS, своё приложение исключено.
        //    MTU интерфейса не задаём: туннель работает со значением по умолчанию.
        //
        //    DNS указывает на служебный адрес mapdns (Задача 6): hev-socks5-
        //    tunnel перехватывает запросы к нему прямо в туннеле, поэтому
        //    резолвинг не зависит от UDP-релея движка (пресет «Умный»
        //    выключает UDP целиком ради блокировки QUIC).
        val fd = Builder()
            .setSession("DPIBreak")
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TunSocksBridge.MAPDNS_ADDRESS)
            .addDisallowedApplication(packageName)
            .establish()
            ?: run {
                Log.e(TAG, "TUN establish failed (разрешение VPN отозвано?)")
                ServiceManager.updateState(
                    ServiceState.Error("Не удалось создать TUN-интерфейс (разрешение VPN?)")
                )
                stopSelf()
                return
            }
        tunInterface = fd
        Log.i(TAG, "TUN established, fd=${fd.fd}")

        // 2. Движок byedpi: локальный SOCKS5 на 127.0.0.1.
        //    Аргументы — от пресета, выбранного в UI (или сохранённого,
        //    если сервис перезапущен системой с null-intent).
        val presetArgs = engineArgs ?: Presets.getSelected(this).byedpiArgs
        Log.i(TAG, "Engine preset args: $presetArgs")
        val port = engine.start(listOf("-i", "127.0.0.1") + presetArgs)
        if (port == null) {
            Log.e(TAG, "byedpi engine failed to start")
            ServiceManager.updateState(
                ServiceState.Error("Движок byedpi не запустился (порт 1080 не открылся)")
            )
            cleanup(restoreIdle = false)
            return
        }
        Log.i(TAG, "byedpi SOCKS5 listening on 127.0.0.1:$port")

        // 3. tun2socks: TUN → SOCKS5.
        val bridgeStarted = TunSocksBridge.start(fd.fd, cacheDir, "127.0.0.1", port)
        if (!bridgeStarted) {
            Log.e(TAG, "tun2socks bridge failed to start")
            ServiceManager.updateState(
                ServiceState.Error("Туннель tun2socks не запустился")
            )
            cleanup(restoreIdle = false)
            return
        }

        // 4. Проверка здоровья: поток туннеля должен быть жив спустя секунду.
        //    Если он умер — на экране появится конкретная ошибка вместо
        //    молчаливого «нет интернета».
        healthJob = serviceScope.launch {
            delay(1000)
            if (TunSocksBridge.TProxyIsRunning()) {
                Log.i(TAG, "Pipeline is UP: TUN → byedpi:$port → tun2socks")
                ServiceManager.updateState(ServiceState.Active)
            } else {
                Log.e(TAG, "tun2socks thread died right after start")
                ServiceManager.updateState(
                    ServiceState.Error("Туннель умер сразу после старта — нужен logcat (тег DPIBreak)")
                )
                stopTunnel()
            }
        }
    }

    /**
     * Штатная остановка: только через этот метод (фоновый поток + защита
     * от повторного входа). Вызывается по кнопке, onRevoke и из проверок
     * здоровья.
     */
    private suspend fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) {
            return // уже останавливаемся
        }
        cleanup(restoreIdle = true)
        stopping.set(false)
    }

    /**
     * Разбор конвейера в обратном порядке. [restoreIdle]=false, если уже
     * установлено состояние Error — не затираем текст ошибки.
     */
    private fun cleanup(restoreIdle: Boolean) {
        Log.i(TAG, "Stopping tunnel…")
        healthJob?.cancel()
        healthJob = null
        runCatching { TunSocksBridge.stop() }
        runCatching { engine.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (restoreIdle) {
            ServiceManager.updateState(ServiceState.Idle)
        }
        Log.i(TAG, "Tunnel stopped")
    }

    /** Разрешение VPN отозвано пользователем из системных настроек. */
    override fun onRevoke() {
        Log.w(TAG, "onRevoke: VPN permission revoked")
        serviceScope.launch { stopTunnel() }
    }

    override fun onDestroy() {
        // Если сервис убит системой без штатной остановки — прибираемся
        // best-effort (обычный путь уже всё закрыл, операции идемпотентны).
        if (tunInterface != null) {
            Log.w(TAG, "onDestroy with active tunnel — emergency cleanup")
            cleanup(restoreIdle = true)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dpibreak.START"
        const val ACTION_STOP = "com.dpibreak.STOP"

        /** StringArrayListExtra: аргументы движка от пресета. */
        const val EXTRA_ENGINE_ARGS = "com.dpibreak.ENGINE_ARGS"

        /** PendingIntent: открыть приложение по нажатию на уведомление. */
        fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}
