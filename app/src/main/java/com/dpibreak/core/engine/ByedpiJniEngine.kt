package com.dpibreak.core.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Реализация [DesyncEngine] поверх C-ядра byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Паттерн интеграции (проверенный сообществом):
 *  1. byedpi собирается NDK как static-библиотека и линкуется в libdpibreak.so;
 *  2. JNI-функция принимает массив строк-аргументов, конвертирует в char** и
 *     вызывает main(argc, argv) ядра — ядро стартует свой SOCKS5-сервер;
 *  3. остановка — shutdown(server_fd) (server_fd — глобальная переменная ядра).
 */
class ByedpiJniEngine : DesyncEngine {

    companion object {
        init {
            System.loadLibrary("dpibreak")
        }

        /** Порт локального SOCKS5 (совпадает с -p у byedpi). */
        const val DEFAULT_SOCKS_PORT = 1080
        
        private const val TAG = "DPIBreak"
    }

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning
    
    private var proxyJob: Job? = null

    override fun start(args: List<String>): Int? {
        Log.d(TAG, "Starting byedpi proxy with args: $args")
        
        // Запускаем поток с jniStartProxy
        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            val allArgs = listOf("-p", DEFAULT_SOCKS_PORT.toString()) + args
            Log.d(TAG, "Full args: $allArgs")
            val result = jniStartProxy(allArgs.toTypedArray())
            Log.d(TAG, "byedpi main() returned: $result")
        }
        
        // Ждём открытия порта (~5 сек)
        val timeoutMs = 5000L
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connectTimeout = 500
                    socket.connect(InetSocketAddress("127.0.0.1", DEFAULT_SOCKS_PORT), 500)
                }
                Log.d(TAG, "SOCKS5 port $DEFAULT_SOCKS_PORT is open")
                _isRunning.value = true
                return DEFAULT_SOCKS_PORT
            } catch (e: Exception) {
                // Порт ещё не открыт, ждём
                Thread.sleep(100)
            }
        }
        
        Log.e(TAG, "Failed to open SOCKS5 port within $timeoutMs ms")
        stop()
        return null
    }

    override fun stop() {
        Log.d(TAG, "Stopping byedpi proxy")
        try {
            jniStopProxy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy: ${e.message}")
        }
        
        // Принудительное закрытие при зависании
        try {
            Thread.sleep(500)
            if (_isRunning.value) {
                Log.w(TAG, "Proxy still running, force closing...")
                jniForceClose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force closing: ${e.message}")
        }
        
        proxyJob?.cancel()
        proxyJob = null
        _isRunning.value = false
    }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy()
    private external fun jniForceClose()
}
