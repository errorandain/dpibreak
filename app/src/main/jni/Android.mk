# DPIBreak: нативная сборка (ndk-build)
#
# Две библиотеки:
#   1) hev-socks5-tunnel — tun2socks (TUN → SOCKS5), https://github.com/heiher/hev-socks5-tunnel (MIT)
#   2) dpibreak — наш JNI-мост + ядро byedpi (SOCKS5-прокси с десинком), MIT
#
# ВАЖНО: PKGNAME/CLSNAME задаются в Application.mk — они нацеливают JNI-регистрацию
# туннеля на наш класс com.dpibreak.core.tunnel.TunSocksBridge.

# DPIBREAK_PATH — своё имя переменной: НЕ называется TOP_PATH, потому что
# Android.mk туннеля перезаписывает TOP_PATH (переменные make глобальны).
DPIBREAK_PATH := $(call my-dir)

# --- hev-socks5-tunnel (с его зависимостями: yaml, lwip, hev-task-system) ---
include $(DPIBREAK_PATH)/hev-socks5-tunnel/Android.mk

# --- Наша библиотека: JNI-мост + движок byedpi ---
include $(CLEAR_VARS)
LOCAL_PATH := $(DPIBREAK_PATH)
LOCAL_MODULE := dpibreak
LOCAL_SRC_FILES := \
    native-lib.c \
    byedpi/main.c \
    byedpi/proxy.c \
    byedpi/conev.c \
    byedpi/desync.c \
    byedpi/packets.c \
    byedpi/extend.c \
    byedpi/mpool.c
LOCAL_C_INCLUDES := $(DPIBREAK_PATH)/byedpi
LOCAL_CFLAGS := -D_DEFAULT_SOURCE -DANDROID_APP -std=c99 -O2 \
    -Wno-unused -Wno-unused-parameter -Wno-unused-function \
    -Wno-gnu-zero-variadic-macro-arguments -Wno-unknown-attributes \
    -Wno-format -Wno-sign-compare
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
