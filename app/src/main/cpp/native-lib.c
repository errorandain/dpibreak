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
 *  - jniStartProxy: Java-строки -> char** argv, сброс optind, вызов main(argc, argv)
 *    ядра; main() блокирует до остановки — вызывать ТОЛЬКО из фонового потока;
 *  - jniStopProxy: мягкая остановка через shutdown(server_fd);
 *  - jniForceClose: жёсткое закрытие сокета при зависании.
 *
 * server_fd — глобальная переменная ядра (proxy.c).
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

    jsize argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    if (!argv) {
        return -1;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!js) {
            argv[i] = NULL;
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, 0);
        argv[i] = utf ? strdup(utf) : NULL;
        if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[argc] = NULL;

    optind = 1;              /* сброс getopt для повторного запуска ядра */
    g_proxy_running = 1;

    int rc = main(argc, argv);

    g_proxy_running = 0;

    for (jsize i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);

    return rc;
}

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    if (!g_proxy_running) {
        return -1;
    }
    /* Мягкая остановка: событие выхода из event loop ядра */
    return shutdown(server_fd, SHUT_RDWR);
}

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniForceClose(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    /* Жёсткое закрытие сокета — на случай зависания */
    return close(server_fd);
}
