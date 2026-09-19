package com.dpibreak.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import com.dpibreak.core.engine.ByedpiJniEngine
import com.dpibreak.core.tunnel.TunSocksBridge
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Сбор диагностических логов для показа пользователю (экран «Лог»).
 *
 * Источники:
 *  - logcat нашего процесса: движок byedpi пишет с тегом `proxy`, обвязка — `DPIBreak`;
 *  - файл hev.log: лог tun2socks (включая mapdns), настраивается в TunSocksBridge.
 *
 * Ничего не отправляет наружу — текст виден на экране и копируется вручную.
 */
object DiagLog {

    private const val MAX_LINES = 500

    fun collect(context: Context): String = runCatching {
        buildString {
            appendLine("=== СТАТУС КОМПОНЕНТОВ ===")
            appendLine("byedpi запущен: ${getByedpiStatus()}")
            appendLine("tun2socks запущен: ${getTun2socksStatus()}")
            
            // Проверка доступности SOCKS5 прокси
            appendLine("\n=== ПРОВЕРКА SOCKS5 ===")
            val socksAvailable = checkSocks5Proxy("127.0.0.1", 8080)
            appendLine("SOCKS5 на 127.0.0.1:8080: ${if (socksAvailable) "✅ доступен" else "❌ недоступен"}")
            
            // Проверка сетевого подключения
            appendLine("\n=== СЕТЕВОЕ ПОДКЛЮЧЕНИЕ ===")
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            appendLine("Тип сети: ${getNetworkType(capabilities)}")
            appendLine("VPN активен: ${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true}")
            
            appendLine("\n=== logcat (DPIBreak / proxy) ===")
            runCatching {
                val proc = ProcessBuilder(
                    "logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"
                ).start()
                val out = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                val interesting = out.filter {
                    it.contains("DPIBreak") || it.contains("proxy") || it.contains("byedpi") || it.contains("hev") || it.contains("tun2socks")
                }
                if (interesting.isEmpty()) appendLine("(пусто)")
                else appendLine(interesting.takeLast(MAX_LINES).joinToString("\n"))
            }.onFailure { appendLine("logcat недоступен: ${it.message}") }

            appendLine("\n=== hev.log (tun2socks) ===")
            runCatching {
                val f = File(context.cacheDir, "hev.log")
                if (f.exists()) {
                    val lines = f.readLines()
                    appendLine(lines.takeLast(MAX_LINES).joinToString("\n"))
                } else {
                    appendLine("(файла нет — туннель ещё не запускался или hev.log не создан)")
                }
            }.onFailure { appendLine("не удалось прочитать: ${it.message}") }
            
            appendLine("\n=== ДИАГНОСТИКА ПРОБЛЕМ ===")
            when {
                !socksAvailable -> appendLine("⚠️ SOCKS5 прокси не отвечает. Проверьте, что byedpi запустился без ошибок.")
                !getTun2socksStatus().contains("запущен") -> appendLine("⚠️ tun2socks не запущен. Туннель не работает.")
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true -> 
                    appendLine("⚠️ VPN не активен в системе. Возможно, трафик идёт в обход туннеля.")
                else -> appendLine("✅ Все компоненты работают корректно.")
            }
        }
    }.getOrElse { "Ошибка сбора диагностики: ${it.message}\n\nПопробуйте перезапустить приложение." }
    
    /** Проверяет доступность SOCKS5 прокси */
    private fun checkSocks5Proxy(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                true
            }
        }.getOrElse { false }
    }
    
    /** Возвращает читаемое название типа сети */
    private fun getNetworkType(capabilities: android.net.NetworkCapabilities?): String {
        return when {
            capabilities == null -> "Нет подключения"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Другое"
        }
    }
    
    /** Безопасно получает статус byedpi без создания нового экземпляра */
    private fun getByedpiStatus(): String {
        return try {
            // Используем singleton instance вместо создания нового объекта
            val engine = ByedpiJniEngine.getInstance()
            if (engine != null && engine.isRunning.value) "✅ запущен" else "❌ остановлен"
        } catch (e: Exception) {
            "⚠️ ошибка проверки: ${e.message}"
        }
    }
    
    /** Безопасно получает статус tun2socks */
    private fun getTun2socksStatus(): String {
        return try {
            if (TunSocksBridge.isRunning()) "✅ запущен" else "❌ остановлен"
        } catch (e: Exception) {
            "⚠️ ошибка проверки: ${e.message}"
        }
    }
}
