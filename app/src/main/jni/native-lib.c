#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <getopt.h>
#include <android/log.h>

/*
 * DPIBreak: JNI-мост к ядру byedpi (https://github.com/hufrea/byedpi, MIT).
 *
 * Паттерн интеграции (проверенный сообществом — ByeByeDPI и др.):
 *  - jniStartProxy: Java-строки -> char** argv (argv[0] = "byedpi" — имя
 *    программы, как ожидает getopt), сброс optind, вызов main(argc, argv)
 *    ядра; main() блокирует до остановки — вызывать ТОЛЬКО из фонового потока;
 *  - jniStopProxy: мягкая остановка через shutdown(server_fd) — ровно то же
 *    действие, что обработчик SIGINT/SIGTERM в самом ядре (proxy.c:on_cancel);
 *  - jniForceClose: жёсткое закрытие сокета при зависании.
 *
 * ЗАЩИТА ОТ ДВОЙНОГО ЗАКРЫТИЯ: после выхода main() сокет server_fd уже закрыт
 * самим ядром byedpi, а номер дескриптора мог быть переиспользован другими
 * сокетами приложения (туннеля!). Поэтому stop/forceClose работают с fd
 * ТОЛЬКО когда движок ещё считается запущенным (g_proxy_running).
 *
 * ЗАЩИТА ОТ «ВЕЧНОГО» ЗАХВАТА ФЛАГА: если поток ядра завис и g_proxy_running
 * остался равным 1, повторный старт раньше возвращал -1 немедленно — и движок
 * больше не поднимался до перезапуска всего приложения (туннель без движка =
 * «включено, но интернета нет»). Теперь перед отказом ждём до 2 с, пока
 * предыдущий экземпляр действительно завершится.
 */

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DPIBreak", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DPIBreak", __VA_ARGS__)

/* Коды отказа jniStartProxy (Kotlin транслирует их в сообщение на экране). */
#define DPIBREAK_ERR_BUSY    (-2)  /* предыдущий экземпляр ядра ещё не умер */
#define DPIBREAK_ERR_NOMEM   (-1)  /* не хватило памяти под argv */

extern int server_fd;
extern int main(int argc, char **argv);

static volatile int g_proxy_running = 0;

/* Ждём, пока предыдущий запуск ядра действительно завершится. */
static int wait_proxy_stopped(int timeout_ms)
{
    while (g_proxy_running && timeout_ms > 0) {
        usleep(20 * 1000);
        timeout_ms -= 20;
    }
    return !g_proxy_running;
}

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy(JNIEnv *env,
        __attribute__((unused)) jobject thiz, jobjectArray args) {

    if (g_proxy_running) {
        if (!wait_proxy_stopped(2000)) {
            LOGE("jniStartProxy: previous byedpi instance is still running — refusing");
            return DPIBREAK_ERR_BUSY;
        }
        LOGI("jniStartProxy: waited for the previous instance to exit");
    }

    jsize nargs = (*env)->GetArrayLength(env, args);
    int argc = (int) nargs + 1; /* +1 под argv[0] */
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    if (!argv) {
        return DPIBREAK_ERR_NOMEM;
    }

    argv[0] = strdup("byedpi"); /* имя программы — для корректного разбора getopt */
    for (jsize i = 0; i < nargs; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!js) {
            /*
             * Пустой элемент списка аргументов. NULL в argv — гарантированное
             * падение внутри getopt_long у ядра, поэтому подставляем пустую
             * строку: getopt её просто не разберёт как опцию и вернёт ошибку.
             */
            argv[i + 1] = strdup("");
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, 0);
        argv[i + 1] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
        if (!argv[i + 1]) {
            for (int j = 0; j < argc; j++) free(argv[j]);
            free(argv);
            return DPIBREAK_ERR_NOMEM;
        }
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
    LOGE("jniForceClose: byedpi ignored soft stop, closing listen socket");
    return close(server_fd);
}
