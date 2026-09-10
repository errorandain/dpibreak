package com.dpibreak

import android.app.Application
import com.dpibreak.core.NotificationUtils

/**
 * Точка входа приложения.
 *
 * Канал уведомлений создаём здесь, а не только в сервисе: foreground-сервис
 * обязан иметь готовый канал к моменту первого startForeground() — иначе
 * (Samsung/OneUI) уведомление тихо не показывается, а сервис без уведомления
 * система убивает через минуту. Метод идемпотентен, дубль в сервисе остаётся
 * на случай прямого запуска сервиса системой.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationUtils.createNotificationChannel(this)
    }
}
