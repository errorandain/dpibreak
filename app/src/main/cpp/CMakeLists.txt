cmake_minimum_required(VERSION 3.22.1)
project("dpibreak")

# ============================================================
# Нативная часть DPIBreak.
#
# Сейчас: заглушка (собирается пустая библиотека, чтобы проект
#           собирался с NDK «из коробки»).
#
# TODO(Задача 4): подключить ядро byedpi (git submodule):
#   add_subdirectory(byedpi)  # hufrea/byedpi, MIT
#   target_link_libraries(dpibreak byedpi)
#
# TODO(Задача 5): подключить hev-socks5-tunnel (git submodule):
#   add_subdirectory(hev-socks5-tunnel)  # heiher/hev-socks5-tunnel, MIT
#
# Подробные инструкции — docs/qwen/TASKS.md (Задачи 4 и 5)
# ============================================================

add_library(dpibreak SHARED native-lib.c)
