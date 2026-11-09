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

        /** Таймаут одной проверки рукопожатия SOCKS5 (как в ByeByeDPI NativeSession). */
        private const val PROBE_TIMEOUT_MS = 250

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

    @Volatile private var activePort: Int = DEFAULT_SOCKS_PORT

    /** Включить/выключить отбрасывание QUIC (UDP 443) на уровне локального прокси. */
    fun setDropQuic(drop: Boolean) {
        runCatching { jniSetDropQuic(drop) }
            .onFailure { Log.w(TAG, "jniSetDropQuic failed: ${it.message}") }
    }

    /**
     * {@inheritDoc}
     *
     * Метод блокирующий (ожидает порт и рукопожатие SOCKS5) — вызывать только из фонового потока.
     */
    override fun start(args: List<String>): Int? = start(args, DEFAULT_SOCKS_PORT)

    /**
     * Запускает локальный SOCKS5-прокси ядра byedpi на указанном [port].
     */
    fun start(args: List<String>, port: Int): Int? {
        if (_isRunning.value) {
            return activePort
        }
        // Поток прошлого запуска ещё жив — новый main() поднимать нельзя.
        coreThread?.takeIf { it.isAlive }?.let {
            Log.e(TAG, "Previous byedpi thread is still alive — cannot start a new one")
            return null
        }
        coreThread = null

        val allArgs = listOf("-p", port.toString()) + args
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

        // Ждём готовности SOCKS5 с полноценной проверкой приветствия (0x05, 0x01, 0x00 -> 0x05, 0x00),
        // точно как в эталонном ByeByeDPI (NativeSession.awaitProxy).
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive) {
                Log.e(TAG, "byedpi main() exited before opening port")
                coreThread = null
                _isRunning.value = false
                return null
            }
            if (jniIsProxyReady() && probeSocks5Handshake("127.0.0.1", port)) {
                if (!thread.isAlive) {
                    coreThread = null
                    _isRunning.value = false
                    return null
                }
                Log.i(TAG, "SOCKS5 port $port is open and verified")
                activePort = port
                _isRunning.value = true
                return port
            }
            Thread.sleep(50)
        }

        Log.e(TAG, "SOCKS5 port $port was not opened within ${PORT_WAIT_MS / 1000} seconds")
        stop()
        return null
    }

    private fun probeSocks5Handshake(host: String, port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
            s.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            val input = s.getInputStream()
            input.read() == 0x05 && input.read() == 0x00
        }
    } catch (_: IOException) {
        false
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
    private external fun jniIsProxyReady(): Boolean
    private external fun jniSetDropQuic(drop: Boolean)
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
