package com.dpibreak.domain

/**
 * Универсальная стратегия обхода DPI.
 * Объединяет лучшие методы: десинхронизацию, фейковые пакеты и авто-подбор.
 * Работает для YouTube, Telegram, Discord, Instagram и большинства заблокированных ресурсов.
 * 
 * Ключевые параметры для YouTube:
 * - --fake=mss: Эмулирует неверный MSS (Maximum Segment Size) — критично для YouTube!
 * - --disorder=5: Меняет порядок 5 пакетов (агрессивнее обход)
 * - --oob=0x00: Добавляет OOB-данные в фейковые пакеты
 * - --ttl=8: TTL фейковых пакетов (должен быть меньше реального TTL до сервера)
 */
data class UniversalStrategy(
    val name: String = "Universal Bypass",
    
    // Основные аргументы ядра byedpi
    val arguments: List<String> = listOf(
        "--auto=torst",           // Авто-режим: TLS Record Splitting или Fake Request
        "--tlsrec=1+s",           // Разбивает первый пакет TLS (1 байт + остальное)
        "--disorder=5",           // Меняет порядок 5 пакетов (агрессивнее для YouTube)
        "--fake=mss",             // КРИТИЧНО: эмуляция неверного MSS (лучше чем --fake=1)
        "--oob=0x00",             // Добавляет OOB-данные в фейковые пакеты
        "--ttl=8",                // TTL фейковых пакетов (исчезают до достижения сервера)
        "--dnsover=https://dns.google/dns-query"  // Google DNS для googlevideo.com
    ),

    // Блокировка QUIC (UDP 443) принудительно переводит трафик в TCP
    val blockQuic: Boolean = true,

    // Hostlist пуст, так как стратегия универсальна
    val hostlist: List<String> = emptyList(),

    // Исключения (банки, госуслуги)
    val excludedApps: List<String> = listOf(
        "ru.sberbankmobile",
        "ru.vtb.mobileapp",
        "ru.tinkoff.bank",
        "ru.gosuslugi"
    )
) {
    companion object {
        val Instance = UniversalStrategy()
        
        fun getCommandLineArgs(): String = Instance.arguments.joinToString(" ")
    }
}
