package com.dpibreak.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Реализация [DesyncEngine] поверх C-ядра byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Паттерн интеграции (проверенный сообществом):
 *  1. byedpi собирается NDK как static-библиотека и линкуется в libdpibreak.so;
 *  2. JNI-функция принимает массив строк-аргументов, конвертирует в char** и
 *     вызывает main(argc, argv) ядра — ядро стартует свой SOCKS5-сервер;
 *  3. остановка — shutdown(server_fd) (server_fd — глобальная переменная ядра).
 *
 * TODO(Задача 4): реализовать JNI-мост в app/src/main/cpp/native-lib.c
 *   по образцу (проверить имена функций по итоговому package!):
 *
 *   JNIEXPORT jint JNICALL
 *   Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy(
 *           JNIEnv *env, jobject thiz, jobjectArray args) {
 *       // строки Java -> char** argv
 *       // optind = 1;  // getopt можно вызывать повторно
 *       // return main(argc, argv);
 *   }
 *
 *   JNIEXPORT jint JNICALL
 *   Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStopProxy(...) {
 *       // extern int server_fd; shutdown(server_fd, SHUT_RDWR);
 *   }
 *
 * ВАЖНО: ядро byedpi блокирующее — вызывать jniStartProxy нужно в отдельном
 * потоке (не на main и не на UI-потоке Compose).
 */
class ByedpiJniEngine : DesyncEngine {

    companion object {
        init {
            // TODO(Задача 4): раскомментировать, когда нативная библиотека
            //  будет собираться с ядром byedpi:
            // System.loadLibrary("dpibreak")
        }

        /** Порт локального SOCKS5 (совпадает с -p у byedpi). */
        const val DEFAULT_SOCKS_PORT = 1080
    }

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning

    override fun start(args: List<String>): Int? {
        // TODO(Задача 4):
        //  1. запустить поток: jniStartProxy(arrayOf("-p", port, *args.toTypedArray()))
        //  2. дождаться, пока порт начнёт слушать (poll/повторный connect)
        //  3. вернуть порт
        TODO("Задача 4: JNI-мост к byedpi")
    }

    override fun stop() {
        // TODO(Задача 4): jniStopProxy() + jniForceClose() при зависании
        TODO("Задача 4: JNI-мост к byedpi")
    }

    // После реализации моста раскомментировать:
    // private external fun jniStartProxy(args: Array<String>): Int
    // private external fun jniStopProxy(): Int
    // external fun jniForceClose(): Int
}
