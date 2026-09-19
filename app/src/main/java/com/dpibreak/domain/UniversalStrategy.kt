package com.dpibreak.domain

/**
 * Универсальная стратегия обхода DPI.
 * Объединяет лучшие методы: десинхронизацию, фейковые пакеты и авто-подбор.
 * Работает для YouTube, Telegram, Discord, Instagram и большинства заблокированных ресурсов.
 * 
 * Ключевые параметры для YouTube:
 * - --fake=1: Эмулирует неверный размер TCP окна (критично!)
 * - --ttl=8: TTL фейковых пакетов (должен быть меньше реального TTL до сервера)
 * - --auto=torst: Авто-определение типа блокировки
 */
data class UniversalStrategy(
    val name: String = "Universal Bypass",
    
    // Основные аргументы ядра byedpi
    val arguments: List<String> = listOf(
        "--auto=torst",           // Авто-режим: TLS Record Splitting или Fake Request
        "--tlsrec=1+s",           // Разбивает первый пакет TLS (1 байт + остальное)
        "--disorder=1",           // Меняет порядок пакетов
        "--fake=1",               // КРИТИЧНО для YouTube: эмуляция неверного TCP окна
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
