package com.dpibreak

import android.app.Application

/**
 * Точка входа приложения.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO(Задача 3): создать канал уведомлений для foreground-сервиса
        //  (NotificationChannel с IMPORTANCE_LOW, id = "vpn_service")
    }
}
