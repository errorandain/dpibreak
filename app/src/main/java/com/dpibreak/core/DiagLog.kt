package com.dpibreak.core

import android.content.Context
import android.os.Process
import java.io.File

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

    fun collect(context: Context): String = buildString {
        appendLine("=== logcat (DPIBreak / proxy) ===")
        runCatching {
            val proc = ProcessBuilder(
                "logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"
            ).start()
            val out = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            val interesting = out.filter {
                it.contains("DPIBreak") || it.contains("proxy") || it.contains("byedpi")
            }
            if (interesting.isEmpty()) appendLine("(пусто)")
            else appendLine(interesting.takeLast(MAX_LINES).joinToString("\n"))
        }.onFailure { appendLine("logcat недоступен: ${it.message}") }

        appendLine()
        appendLine("=== hev.log (tun2socks) ===")
        runCatching {
            val f = File(context.cacheDir, "hev.log")
            if (f.exists()) {
                val lines = f.readLines()
                appendLine(lines.takeLast(MAX_LINES).joinToString("\n"))
            } else {
                appendLine("(файла нет — туннель ещё не запускался)")
            }
        }.onFailure { appendLine("не удалось прочитать: ${it.message}") }
    }
}
