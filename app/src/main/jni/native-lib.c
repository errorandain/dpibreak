#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <getopt.h>

/*
 * DPIBreak: JNI-мост к ядру byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Паттерн интеграции (проверенный сообществом — ByeByeDPI и др.):
 *  - jniStartProxy: Java-строки -> char** argv (argv[0] = "byedpi" — имя
 *    программы, как ожидает getopt), сброс optind, вызов main(argc, argv)
 *    ядра; main() блокирует до остановки — вызывать ТОЛЬКО из фонового потока;
 *  - jniStopProxy: мягкая остановка через shutdown(server_fd);
 *  - jniForceClose: жёсткое закрытие сокета при зависании.
 *
 * ЗАЩИТА ОТ ДВОЙНОГО ЗАКРЫТИЯ: после выхода main() сокет server_fd уже закрыт
 * самим ядром byedpi, а номер дескриптора мог быть переиспользован другими
 * сокетами приложения (туннеля!). Поэтому stop/forceClose работают с fd
 * ТОЛЬКО когда движок ещё считается запущенным (g_proxy_running).
 */

extern int server_fd;
extern int main(int argc, char **argv);

static volatile int g_proxy_running = 0;

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy(JNIEnv *env,
        __attribute__((unused)) jobject thiz, jobjectArray args) {

    if (g_proxy_running) {
        return -1;
    }

    jsize nargs = (*env)->GetArrayLength(env, args);
    int argc = (int) nargs + 1; /* +1 под argv[0] */
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    if (!argv) {
        return -1;
    }

    argv[0] = strdup("byedpi"); /* имя программы — для корректного разбора getopt */
    for (jsize i = 0; i < nargs; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!js) {
            argv[i + 1] = NULL;
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, 0);
        argv[i + 1] = utf ? strdup(utf) : NULL;
        if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[argc] = NULL;

    optind = 1;              /* сброс getopt для повторного запуска ядра */
    g_proxy_running = 1;

    int rc = main(argc, argv);

    g_proxy_running = 0;

    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);

    return rc;
}

/* Мягкая остановка: shutdown выводит main() из цикла событий. Безопасна повторно. */
JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    if (!g_proxy_running) {
        return -1; /* уже остановлен — fd мог быть переиспользован, не трогаем */
    }
    return shutdown(server_fd, SHUT_RDWR);
}

/* Жёсткое закрытие — только если main() завис, но формально ещё «работает». */
JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniForceClose(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    if (!g_proxy_running) {
        return -1; /* main() уже закрыл server_fd сам */
    }
    return close(server_fd);
}
