package com.dpibreak.core.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Реализация [DesyncEngine] поверх C-ядра byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Интеграция: JNI-мост вызывает main(argc, argv) ядра в отдельном потоке —
 * ядро поднимает SOCKS5-прокси на 127.0.0.1:[port] и работает до остановки.
 *
 * ВАЖНО (починено после бага с вылетом): после выхода main() сокет server_fd
 * закрывается самим ядром. Поэтому stop() сначала просит ядро остановиться
 * мягко (shutdown), ждёт завершения потока, и только если поток завис — делает
 * forceClose. Так мы не закрываем чужие дескрипторы.
 *
 * Поток — «родной» [Thread], а не корутина: main() ядра блокирует поток до
 * остановки, отменить такую корутину нельзя (job.cancel() не прерывает нативный
 * вызов), а отложенный корутинами пул только создаёт иллюзию отмены. Поток —
 * daemon, поэтому зависшее ядро не удержит процесс от завершения.
 */
class ByedpiJniEngine : DesyncEngine {

    companion object {
        init {
            System.loadLibrary("dpibreak")
        }

        /** Порт локального SOCKS5 (совпадает с -p у byedpi). */
        const val DEFAULT_SOCKS_PORT = 1080

        /** Сколько ждём открытия порта SOCKS5. */
        private const val PORT_WAIT_MS = 5_000L

        /** Сколько ждём мягкого завершения потока ядра. */
        private const val SOFT_STOP_WAIT_MS = 2_000L

        /** Сколько дополнительно ждём после жёсткого закрытия. */
        private const val HARD_STOP_WAIT_MS = 500L

        private const val TAG = "DPIBreak"
    }

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Поток, в котором работает main() ядра. Не null — пока поток не завершён. */
    @Volatile private var coreThread: Thread? = null

    /**
     * {@inheritDoc}
     *
     * Метод блокирующий (ожидает порт) — вызывать только из фонового потока.
     */
    override fun start(args: List<String>): Int? {
        if (_isRunning.value) {
            return DEFAULT_SOCKS_PORT
        }
        // Поток прошлого запуска ещё жив (ядро не отреагировало на остановку) —
        // новый main() поднимать нельзя: нативная сторона вернёт ошибку.
        coreThread?.takeIf { it.isAlive }?.let {
            Log.e(TAG, "Previous byedpi thread is still alive — cannot start a new one")
            return null
        }
        coreThread = null

        val allArgs = listOf("-p", DEFAULT_SOCKS_PORT.toString()) + args
        Log.i(TAG, "Starting byedpi with args: $allArgs")

        val thread = Thread {
            val rc = jniStartProxy(allArgs.toTypedArray())
            _isRunning.value = false
            Log.i(TAG, "byedpi main() exited with code $rc")
        }
        thread.name = "byedpi-core"
        thread.isDaemon = true
        coreThread = thread
        thread.start()

        // Ждём открытия порта SOCKS5
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive) {
                // main() завершился раньше, чем порт открылся — ошибка запуска
                Log.e(TAG, "byedpi main() exited before opening port")
                coreThread = null
                _isRunning.value = false
                return null
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", DEFAULT_SOCKS_PORT), 500)
                }
                Log.i(TAG, "SOCKS5 port $DEFAULT_SOCKS_PORT is open")
                _isRunning.value = true
                return DEFAULT_SOCKS_PORT
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }

        Log.e(TAG, "SOCKS5 port was not opened within ${PORT_WAIT_MS / 1000} seconds")
        stop()
        return null
    }

    override fun stop() {
        val thread = coreThread
        if (thread == null) {
            _isRunning.value = false
            return
        }

        // 1. Мягкая остановка: shutdown(server_fd) внутри ядра
        runCatching { jniStopProxy() }
            .onFailure { Log.w(TAG, "jniStopProxy: ${it.message}") }

        // 2. Ждём завершения потока ядра (сам себя джойнить нельзя)
        if (thread !== Thread.currentThread()) {
            runCatching { thread.join(SOFT_STOP_WAIT_MS) }
                .onFailure { Log.w(TAG, "join byedpi-core: ${it.message}") }
        }

        // 3. Только если завис — жёсткое закрытие
        if (thread.isAlive) {
            Log.w(TAG, "byedpi did not stop in ${SOFT_STOP_WAIT_MS} ms, force closing")
            runCatching { jniForceClose() }
            runCatching { thread.join(HARD_STOP_WAIT_MS) }
        }

        if (!thread.isAlive) {
            coreThread = null
        }
        _isRunning.value = false
        Log.i(TAG, "byedpi engine stopped")
    }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
