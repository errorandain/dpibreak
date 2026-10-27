#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <getopt.h>
#include <android/log.h>

/*
 * DPIBreak: JNI-мост к ядру byedpi (https://github.com/hufrea/byedpi, MIT)
 * и нативный Unix-сокет сервера VpnService.protect().
 *
 * Паттерн интеграции с ядром byedpi:
 *  - jniStartProxy: Java-строки -> char** argv (argv[0] = "byedpi" — имя
 *    программы, как ожидает getopt), сброс optind, вызов main(argc, argv)
 *    ядра; main() блокирует до остановки — вызывать ТОЛЬКО из фонового потока;
 *  - jniIsProxyReady: проверка, что именно наш экземпляр открыл порт и вошёл
 *    в цикл событий (server_fd >= 0);
 *  - jniStopProxy: мягкая остановка через shutdown(server_fd) — ровно то же
 *    действие, что обработчик SIGINT/SIGTERM в самом ядре (proxy.c:on_cancel);
 *  - jniForceClose: жёсткое закрытие сокета при зависании.
 *
 * ЗАЩИТА ОТ ДВОЙНОГО ЗАКРЫТИЯ: server_fd сбрасывается в -1 сразу при выходе из
 * цикла событий до close(srvfd), поэтому stop/forceClose никогда не затронут
 * переиспользованный дескриптор.
 */

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DPIBreak", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DPIBreak", __VA_ARGS__)

/* Коды отказа jniStartProxy. */
#define DPIBREAK_ERR_BUSY    (-2)  /* предыдущий экземпляр ядра ещё не умер */
#define DPIBREAK_ERR_NOMEM   (-1)  /* не хватило памяти под argv */

extern volatile int server_fd;
extern int byedpi_main(int argc, char **argv);

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
    if (!argv[0]) {
        free(argv);
        return DPIBREAK_ERR_NOMEM;
    }

    for (jsize i = 0; i < nargs; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!js) {
            argv[i + 1] = strdup("");
        } else {
            const char *utf = (*env)->GetStringUTFChars(env, js, 0);
            argv[i + 1] = strdup(utf ? utf : "");
            if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
            (*env)->DeleteLocalRef(env, js);
        }
        if (!argv[i + 1]) {
            for (int j = 0; j <= i + 1; j++) free(argv[j]);
            free(argv);
            return DPIBREAK_ERR_NOMEM;
        }
    }
    argv[argc] = NULL;

    optind = 1;              /* сброс getopt для повторного запуска ядра */
    server_fd = -1;
    g_proxy_running = 1;

    int rc = byedpi_main(argc, argv);

    server_fd = -1;
    g_proxy_running = 0;

    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);

    return rc;
}

/* Готов ли наш экземпляр byedpi принимать соединения (listen + epoll готовы). */
JNIEXPORT jboolean JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniIsProxyReady(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {
    return (g_proxy_running && server_fd >= 0) ? JNI_TRUE : JNI_FALSE;
}

/* Мягкая остановка: shutdown выводит main() из цикла событий. Безопасна повторно. */
JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    int fd = server_fd;
    if (!g_proxy_running || fd < 0) {
        return -1;
    }
    return shutdown(fd, SHUT_RDWR);
}

/* Жёсткое закрытие — только если main() завис, но формально ещё «работает». */
JNIEXPORT jint JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniForceClose(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz) {

    int fd = server_fd;
    if (!g_proxy_running || fd < 0) {
        return -1;
    }
    server_fd = -1;
    LOGE("jniForceClose: byedpi ignored soft stop, closing listen socket");
    return close(fd);
}

/*
 * ============================================================================
 * Нативный Unix-сокет (AF_UNIX в файловой системе) для ProtectSocketServer.
 *
 * Почему в C, а не через Android LocalServerSocket:
 *  1) LocalServerSocket(String) биндится в ABSTRACT namespace (\0path), тогда
 *     как ядро byedpi (extend.c:protect) подключается к обычному файлу на диске;
 *  2) публичный LocalSocket.getAncillaryFileDescriptors() в AOSP вызывает
 *     recvmsg с нулевым буфером (iov_len = 0), из-за чего Linux отбрасывает
 *     SCM_RIGHTS и дескриптор никогда не доходит до Java;
 *  3) работа с int-дескрипторами напрямую избавляет от рефлексии к скрытому
 *     полю FileDescriptor.descriptor (ограничения Hidden API на Android 9+).
 * ============================================================================
 */

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniCreateSocket(
        JNIEnv *env, __attribute__((unused)) jobject thiz, jstring pathObj) {

    if (!pathObj) return -1;
    const char *path = (*env)->GetStringUTFChars(env, pathObj, 0);
    if (!path) return -1;

    struct sockaddr_un sa = { 0 };
    sa.sun_family = AF_UNIX;
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(sa.sun_path)) {
        LOGE("jniCreateSocket: invalid socket path length (%zu)", len);
        (*env)->ReleaseStringUTFChars(env, pathObj, path);
        return -1;
    }
    memcpy(sa.sun_path, path, len);
    unlink(path); /* удаляем старый файл сокета, если остался после падения */
    (*env)->ReleaseStringUTFChars(env, pathObj, path);

    int sfd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sfd < 0) {
        LOGE("jniCreateSocket: socket() failed: %s", strerror(errno));
        return -1;
    }
    if (bind(sfd, (struct sockaddr *) &sa, sizeof(sa)) < 0) {
        LOGE("jniCreateSocket: bind(%s) failed: %s", sa.sun_path, strerror(errno));
        close(sfd);
        return -1;
    }
    if (listen(sfd, 64) < 0) {
        LOGE("jniCreateSocket: listen() failed: %s", strerror(errno));
        unlink(sa.sun_path);
        close(sfd);
        return -1;
    }
    return sfd;
}

JNIEXPORT jint JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniAcceptClient(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz, jint serverFd) {

    if (serverFd < 0) return -1;
    int cfd;
    do {
        cfd = accept((int) serverFd, NULL, NULL);
    } while (cfd < 0 && errno == EINTR);
    return cfd;
}

/*
 * Принимает ОДНО сообщение от byedpi (extend.c:protect):
 * 1 байт-маркер + fd сокета в SCM_RIGHTS в одном вызове recvmsg().
 */
JNIEXPORT jint JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniRecvProtectedFd(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz, jint clientFd) {

    if (clientFd < 0) return -1;

    struct timeval tv = { .tv_sec = 2 };
    setsockopt((int) clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt((int) clientFd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    char cmsg_buf[CMSG_SPACE(sizeof(int) * 4)] = { 0 };
    char marker = 0;
    struct iovec io = { .iov_base = &marker, .iov_len = 1 };
    struct msghdr msg = { 0 };
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t n;
    do {
        n = recvmsg((int) clientFd, &msg, 0);
    } while (n < 0 && errno == EINTR);

    if (n < 1) {
        return -1;
    }

    int result_fd = -1;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL;
            cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
            if (cmsg->cmsg_len < CMSG_LEN(sizeof(int))) continue;
            size_t data_len = cmsg->cmsg_len - CMSG_LEN(0);
            size_t count = data_len / sizeof(int);
            const unsigned char *ptr = CMSG_DATA(cmsg);
            for (size_t i = 0; i < count; i++) {
                int fd = -1;
                memcpy(&fd, ptr + i * sizeof(int), sizeof(int));
                if (result_fd < 0) {
                    result_fd = fd;
                } else if (fd >= 0) {
                    close(fd); /* закрываем лишние дескрипторы, чтобы не утекли */
                }
            }
        }
    }
    return result_fd;
}

JNIEXPORT void JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniReplyAndClose(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz, jint clientFd, jboolean ok) {

    if (clientFd < 0) return;
    char reply = (ok == JNI_TRUE) ? 1 : 0;
    ssize_t s;
    do {
        s = send((int) clientFd, &reply, 1, MSG_NOSIGNAL);
    } while (s < 0 && errno == EINTR);
    close((int) clientFd);
}

JNIEXPORT void JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniShutdownSocket(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz, jint fd) {
    if (fd >= 0) shutdown((int) fd, SHUT_RDWR);
}

JNIEXPORT void JNICALL
Java_com_dpibreak_core_vpn_ProtectSocketServer_jniCloseFd(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz, jint fd) {
    if (fd >= 0) close((int) fd);
}
