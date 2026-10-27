# DPIBreak: параметры ndk-build для всех модулей

APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := arm64-v8a armeabi-v7a x86_64

# Перенацеливаем JNI-регистрацию hev-socks5-tunnel на наш класс
# com.dpibreak.core.tunnel.TunSocksBridge (см. src/hev-jni.c туннеля)
APP_CFLAGS := -O3 -DPKGNAME=com/dpibreak/core/tunnel -DCLSNAME=TunSocksBridge
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
APP_LDFLAGS := -Wl,-z,max-page-size=16384
