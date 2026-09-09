cmake_minimum_required(VERSION 3.22.1)
project("dpibreak")

# ============================================================
# Нативная часть DPIBreak.
#
# Byedpi ядро интегрировано (Задача 3).
# TODO(Задача 4): подключить hev-socks5-tunnel (git submodule):
#   add_subdirectory(hev-socks5-tunnel)  # heiher/hev-socks5-tunnel, MIT
#
# Подробные инструкции — docs/qwen/TASKS.md (Задача 4)
# ============================================================

# Собираем byedpi как статическую библиотеку (без win_service.c)
set(BYEDPI_SOURCES
    byedpi/packets.c
    byedpi/main.c
    byedpi/conev.c
    byedpi/proxy.c
    byedpi/desync.c
    byedpi/mpool.c
    byedpi/extend.c
)

add_library(byedpi STATIC ${BYEDPI_SOURCES})
target_include_directories(byedpi PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi)
target_compile_options(byedpi PRIVATE
    -D_DEFAULT_SOURCE
    -std=c99
    -O2
    -Wall
    -Wno-unused
    -Wextra
    -Wno-unused-parameter
    -pedantic
)

# Основная библиотека DPIBreak
add_library(dpibreak SHARED native-lib.c)
target_link_libraries(dpibreak byedpi)
