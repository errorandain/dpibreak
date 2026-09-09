# Правила ProGuard/R8 для release-сборки.
# TODO(Задача 4): после подключения byedpi через JNI добавить keep-правила для JNI-методов:
#
# -keepclasseswithmembernames class com.dpibreak.core.engine.** {
#     native <methods>;
# }
