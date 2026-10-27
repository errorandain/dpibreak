# Правила ProGuard/R8 для release-сборки.
#
# Без этих правил release-APK НЕ РАБОТАЕТ, хотя debug-сборка ведёт себя идеально:
# R8 переименовывает классы/методы, а нативная сторона обращается к ним по
# фиксированным именам.

# 1) Движок byedpi: методы связываются по конвенции JNI
#    Java_com_dpibreak_core_engine_ByedpiJniEngine_jniStartProxy и т.д.
#    (см. app/src/main/jni/native-lib.c). Переименование = UnsatisfiedLinkError
#    при первом же включении обхода.
-keepclasseswithmembernames,includedescriptorclasses class com.dpibreak.core.engine.ByedpiJniEngine {
    native <methods>;
}

# 2) Туннель: REGISTER NATIVES (не конвенция имён!) — JNI_OnLoad в vendored-коде
#    ищет класс по имени, зашитому в app/src/main/jni/Application.mk:
#        -DPKGNAME=com/dpibreak/core/tunnel -DCLSNAME=TunSocksBridge
#    FindClass не найдёт класс после минификации -> JNI_ERR -> библиотека не
#    загрузится, System.loadLibrary кинет UnsatisfiedLinkError.
#    Методы обязаны быть статическими (@JvmStatic) — их тоже не трогаем.
-keep class com.dpibreak.core.tunnel.TunSocksBridge {
    <fields>;
    <init>();
    native <methods>;
}

# 3) На случай, если JNI-методы появятся в других классах core — не давать R8
#    удалять «невидимые из Java» нативные методы как неиспользуемые.
-keepclassmembers class com.dpibreak.** {
    native <methods>;
}

# 4) Значения полей domain/Strategy уходят в SharedPreferences по id — имена
#    не критичны, сами id это строки; правил не нужно.

# Что НЕ делать: не добавлять «-ignorewarnings»/«-dontobfuscate» широко —
# это только спрячет поломку, а не починит её.
