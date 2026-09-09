package com.dpibreak.core.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Реализация [DesyncEngine] поверх C-ядра byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Интеграция: JNI-мост вызывает main(argc, argv) ядра в фоновом потоке —
 * ядро поднимает SOCKS5-прокси на 127.0.0.1:[port] и работает до остановки.
 *
 * ВАЖНО (починено после бага с вылетом): после выхода main() сокет server_fd
 * закрывается самим ядром. Поэтому stop() сначала просит ядро остановиться
 * мягко (shutdown), ждёт завершения потока до 2 секунд, и только если поток
 * завис — делает forceClose. Так мы не закрываем чужие дескрипторы.
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
        if (_isRunning.value) {
            return DEFAULT_SOCKS_PORT
        }

        val allArgs = listOf("-p", DEFAULT_SOCKS_PORT.toString()) + args
        Log.i(TAG, "Starting byedpi with args: $allArgs")

        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            val rc = jniStartProxy(allArgs.toTypedArray())
            _isRunning.value = false
            Log.i(TAG, "byedpi main() exited with code $rc")
        }

        // Ждём открытия порта SOCKS5 (~5 сек)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (proxyJob?.isActive != true && !_isRunning.value) {
                // main() завершился раньше, чем порт открылся — ошибка запуска
                Log.e(TAG, "byedpi main() exited before opening port")
                proxyJob = null
                return null
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", DEFAULT_SOCKS_PORT), 500)
                }
                Log.i(TAG, "SOCKS5 port $DEFAULT_SOCKS_PORT is open")
                _isRunning.value = true
                return DEFAULT_SOCKS_PORT
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }

        Log.e(TAG, "SOCKS5 port was not opened within 5 seconds")
        stop()
        return null
    }

    override fun stop() {
        val job = proxyJob
        if (job == null) {
            _isRunning.value = false
            return
        }

        // 1. Мягкая остановка: shutdown(server_fd) внутри ядра
        runCatching { jniStopProxy() }
            .onFailure { Log.w(TAG, "jniStopProxy: ${it.message}") }

        // 2. Ждём завершения потока ядра до 2 секунд
        val finished = runBlocking {
            withTimeoutOrNull(2000) { job.join(); true }
        } == true

        // 3. Только если завис — жёсткое закрытие
        if (!finished) {
            Log.w(TAG, "byedpi did not stop in 2s, force closing")
            runCatching { jniForceClose() }
            job.cancel()
        }

        proxyJob = null
        _isRunning.value = false
        Log.i(TAG, "byedpi engine stopped")
    }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
