package com.dpibreak.domain

/**
 * Универсальная стратегия обхода DPI.
 * Объединяет лучшие методы: десинхронизацию, фейковые пакеты и авто-подбор.
 * Работает для YouTube, Telegram, Discord, Instagram и большинства заблокированных ресурсов.
 */
data class UniversalStrategy(
    val name: String = "Universal Bypass",
    
    // Основные аргументы ядра byedpi
    // --auto=torst: Автоматически выбирает метод (TLS Record Splitting или Fake Request) в зависимости от типа блокировки
    // --fake -1: Отправляет фейковый TLS пакет перед реальным (сбрасывает анализ DPI)
    // --tlsrec 1+s: Разбивает первый пакет TLS на части (1 байт + остальное), ломая сигнатуру SNI
    // --disorder 1: Меняет порядок пакетов (если поддерживается ядром)
    val arguments: List<String> = listOf(
        "--auto=torst",
        "--fake=-1",
        "--tlsrec=1+s",
        "--disorder=1",
        "--dnsover=https://dns.cloudflare.com/dns-query" // Встроенный DoH для надежности
    ),

    // Блокировка QUIC (UDP 443) принудительно переводит трафик в TCP, где обход работает стабильнее
    val blockQuic: Boolean = true,

    // Hostlist пуст, так как стратегия универсальна и применяется ко всему трафику
    val hostlist: List<String> = emptyList(),

    // Исключения (банки, госуслуги) остаются, чтобы не ломать локальные приложения
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
