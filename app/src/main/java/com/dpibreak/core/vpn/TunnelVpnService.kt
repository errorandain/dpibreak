package com.dpibreak.core.vpn

import android.app.PendingIntent
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

private const val TAG = "DPIBreak"

/**
 * Локальный VPN-сервис: поднимает TUN-интерфейс и заводит весь трафик
 * в движок десинка. Ничего не отправляет на удалённые серверы.
 *
 * ПОРЯДОК ЗАПУСКА (реализуется по задачам 3→4→5):
 *   1. startForeground() — уведомление (иначе система убьёт сервис)
 *   2. Builder → establish() — получить fd TUN-интерфейса
 *   3. DesyncEngine.start(strategy) — локальный SOCKS5 на 127.0.0.1:1080
 *   4. TunSocksBridge.start(tunFd, socksPort) — TUN → SOCKS5
 *
 * КЛЮЧЕВЫЕ ПРАВИЛА:
 *   • ИСХОДЯЩИЕ сокеты движка/туннеля обязательно пропускать через
 *     protect(socket) — иначе они уйдут обратно в TUN (петля!).
 *   • DNS: адрес в Builder → резолвинг через движок/DoH, иначе ТСПУ может
 *     подменять ответы DNS (см. docs/DPI-TECHNIQUES.md, раздел DNS).
 *   • IPv6: либо полноценно обрабатывать, либо не маршрутизировать v6 в Builder
 *     (addRoute только 0.0.0.0/0), иначе «включено, но не работает».
 *   • QUIC: YouTube по UDP/443 обходит TCP-трюки — UDP 443 блокируется
 *     (см. Задача 7).
 */
class TunnelVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        // TODO(Задача 3): startForeground с уведомлением из NotificationUtils
        NotificationUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startTunnel()
                startForegroundService()
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    /**
     * Запускает foreground-режим с уведомлением.
     * На Android 14+ (API 34+) требуется указать FOREGROUND_SERVICE_TYPE_SPECIAL_USE.
     */
    private fun startForegroundService() {
        val pendingIntent = TunnelVpnService.contentIntent(this)
        val notification = NotificationUtils.createNotification(this, pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: требуется указать тип foreground-сервиса
            ServiceCompat.startForeground(
                this,
                NotificationUtils.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
        }
    }

    private fun startTunnel() {
        Log.d(TAG, "Starting tunnel...")
        
        val fd = Builder()
            .setSession("DPIBreak")
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)              // весь IPv4-трафик
            .addDnsServer("1.1.1.1")             // TODO(Задача 7): DoH
            .setMtu(1500)
            // TODO(Задача 9): addDisallowedApplication(pkg) для per-app исключений
            .establish() ?: run { 
                Log.e(TAG, "Failed to establish TUN interface")
                ServiceManager.updateState(ServiceManager.ServiceState.Error("Failed to establish TUN"))
                stopSelf()
                return 
            }
        
        tunInterface = fd
        Log.i(TAG, "TUN interface established successfully (fd=${fd.fd})")
        
        // Обновляем состояние в ServiceManager
        ServiceManager.updateState(ServiceManager.ServiceState.Active)
        
        // TODO(Задача 4): engine.start(strategy.byedpiArgs)
        // TODO(Задача 5): TunSocksBridge.start(fd.fd, port)
    }

    private fun stopTunnel() {
        Log.d(TAG, "Stopping tunnel...")
        // TODO(Задача 5): TunSocksBridge.stop()
        // TODO(Задача 4): engine.stop()
        tunInterface?.close()
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Tunnel stopped")
        
        // Обновляем состояние в ServiceManager
        ServiceManager.updateState(ServiceManager.ServiceState.Idle)
    }

    override fun onRevoke() {
        // Пользователь отозвал разрешение VPN из системных настроек
        Log.w(TAG, "VPN permission revoked by user")
        stopTunnel()
        ServiceManager.updateState(ServiceManager.ServiceState.Idle)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dpibreak.START"
        const val ACTION_STOP = "com.dpibreak.STOP"

        /** PendingIntent для уведомления: вернуться в приложение. */
        fun contentIntent(service: VpnService): PendingIntent =
            PendingIntent.getActivity(
                service, 0,
                Intent(service, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}
