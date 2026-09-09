#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <stdlib.h>

// Объявляем main() из byedpi/main.c
extern int main(int argc, char *argv[]);

// Глобальный серверный сокет (используется byedpi)
extern int server_fd;

/*
 * JNI-мост к ядру byedpi.
 * Имена функций должны точно соответствовать пакету:
 *   Java_com_dpibreak_core_engine_ByedpiJniEngine_jni*
 */

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    // Конвертируем Java String[] в char** argv
    jsize len = (*env)->GetArrayLength(env, args);
    char **argv = (char **)malloc((len + 1) * sizeof(char *));
    if (!argv) return -1;

    for (jsize i = 0; i < len; i++) {
        jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *cstr = (*env)->GetStringUTFChars(env, str, NULL);
        argv[i] = strdup(cstr);
        (*env)->ReleaseStringUTFChars(env, str, cstr);
        (*env)->DeleteLocalRef(env, str);
    }
    argv[len] = NULL;

    // Сбрасываем optind для повторного вызова getopt
    optind = 1;

    // Вызываем main() byedpi (блокирует до остановки)
    int result = main(len, argv);

    // Освобождаем память
    for (int i = 0; i < len; i++) {
        free(argv[i]);
    }
    free(argv);

    return result;
}

JNIEXPORT void JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStopProxy(JNIEnv *env, jobject thiz) {
    if (server_fd >= 0) {
        shutdown(server_fd, SHUT_RDWR);
    }
}

JNIEXPORT void JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniForceClose(JNIEnv *env, jobject thiz) {
    if (server_fd >= 0) {
        close(server_fd);
        server_fd = -1;
    }
}
