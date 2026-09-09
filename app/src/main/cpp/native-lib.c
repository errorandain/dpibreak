#include <jni.h>

/*
 * Заглушка JNI-библиотеки.
 *
 * TODO(Задача 4): заменить на JNI-мост к ядру byedpi:
 *   - jniStartProxy(JNIEnv*, jobject, jobjectArray args) -> jint
 *       конвертирует Java-строки в char** и вызывает main(argc, argv) ядра;
 *       перед вызовом: optind = 1 (повторный запуск getopt).
 *   - jniStopProxy()  -> shutdown(server_fd, SHUT_RDWR)
 *   - jniForceClose() -> close(server_fd)
 *
 * Имена JNI-функций должны точно соответствовать пакету:
 *   Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy и т.д.
 */

JNIEXPORT jstring JNICALL
Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStubInfo(JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return (*env)->NewStringUTF(env, "native stub: byedpi not linked yet (see TODO Task 4)");
}
