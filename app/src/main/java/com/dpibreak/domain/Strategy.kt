package com.dpibreak.domain

/**
 * Стратегия обхода = набор аргументов командной строки движка byedpi.
 *
 * Формат аргументов см. в README движка: https://github.com/hufrea/byedpi
 * Важно: рабочие параметры ЗАВИСЯТ ОТ ПРОВАЙДЕРА. Пресеты ниже — стартовые
 * значения из опыта сообщества; их нужно подбирать (см. docs/DPI-TECHNIQUES.md).
 */
data class Strategy(
    val id: String,
    val title: String,
    val description: String,
    /** Аргументы движка byedpi, например: ["-s", "0+s", "-d", "3+s"] */
    val byedpiArgs: List<String>,
    /** Список доменов (файл в assets/hostlists/), к которым применяется стратегия */
    val hostlistFile: String? = null,
)

/**
 * Готовые пресеты.
 *
 * TODO(Задача 6): проверить/оттюнить на реальных сетях; добавить пресет "Авто".
 */
object Presets {

    /** Щадящий режим: разбиваем ClientHello на границе SNI. Часто достаточно для YouTube. */
    val YOUTUBE = Strategy(
        id = "youtube",
        title = "YouTube",
        description = "Разбиение ClientHello на границе SNI + фейковые UDP",
        byedpiArgs = listOf(
            "-s", "0+s",      // split в начале SNI
            "-d", "3+s",      // disorder около SNI
            "-a", "3",        // 3 фейковых UDP-пакета (QUIC)
        ),
        hostlistFile = "hostlists/youtube.txt",
    )

    /** Discord: хост-лист + fake с малым TTL. */
    val DISCORD = Strategy(
        id = "discord",
        title = "Discord",
        description = "Fake-пакеты с малым TTL + split",
        byedpiArgs = listOf(
            "-f", "7",        // fake на позиции 7
            "-t", "8",        // TTL фейка = 8 (подобрать под провайдера!)
            "-s", "1+s",      // split в SNI
        ),
        hostlistFile = "hostlists/discord.txt",
    )

    /**
     * Telegram: в большинстве сетей РФ доступен напрямую.
     * Если резится по DPI (редкие сети) — помогает split.
     * Если заблокирован по IP — десинк НЕ поможет (см. README).
     */
    val TELEGRAM = Strategy(
        id = "telegram",
        title = "Telegram",
        description = "Split для DPI-фильтрации SNI (не помогает при блокировке по IP)",
        byedpiArgs = listOf("-s", "0+s"),
        hostlistFile = "hostlists/telegram.txt",
    )

    /** Всё сразу: YouTube + Discord + Telegram. */
    val GENERAL = Strategy(
        id = "general",
        title = "Общий (YT + Discord + TG)",
        description = "Универсальный набор: split + disorder + fake",
        byedpiArgs = listOf(
            "-s", "0+s",
            "-d", "3+s",
            "-f", "7",
            "-t", "8",
            "-a", "3",
        ),
        hostlistFile = "hostlists/general.txt",
    )

    val all = listOf(YOUTUBE, DISCORD, TELEGRAM, GENERAL)
}
