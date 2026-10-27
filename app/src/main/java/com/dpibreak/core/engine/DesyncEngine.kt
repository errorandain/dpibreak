package com.dpibreak.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Движок десинхронизации DPI.
 *
 * Контракт: реализация поднимает ЛОКАЛЬНЫЙ SOCKS5-прокси на 127.0.0.1:[port]
 * и применяет к исходящим соединениям переданные стратегии (split/fake/disorder/…).
 *
 * Основная реализация — [ByedpiJniEngine] (обёртка над C-ядром byedpi через JNI).
 * В перспективе — своя реализация на Kotlin (см. docs/ROADMAP.md, «M6+»).
 */
interface DesyncEngine {

    /** Состояние движка для UI. */
    val isRunning: StateFlow<Boolean>

    /**
     * Запустить локальный SOCKS5-прокси с заданными аргументами движка.
     *
     * Блокирующий вызов (ждёт открытия порта) — только из фонового потока.
     *
     * @param args аргументы в формате CLI byedpi (см. domain/Strategy.kt)
     * @return порт локального SOCKS5 или null при ошибке
     */
    fun start(args: List<String>): Int?

    /**
     * Остановить прокси и разорвать активные соединения.
     *
     * Блокирующий вызов (ждёт завершения потока ядра) — только из фонового потока.
     * Идемпотентен: повторный вызов безопасен.
     */
    fun stop()
}
