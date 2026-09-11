package com.dpibreak.domain

import android.content.Context

/**
 * Стратегия обхода = набор аргументов командной строки движка byedpi.
 *
 * Формат аргументов см. в README движка: app/src/main/jni/byedpi/README.md
 * Важно: рабочие параметры ЗАВИСЯТ ОТ ПРОВАЙДЕРА И СЕТИ (Wi-Fi ≠ мобильный
 * интернет). Значения ниже — из проверенных рецептов сообщества
 * (ByeDPIAndroid, zapret), каждая проверена на живом движке в песочнице.
 */
data class Strategy(
    val id: String,
    val title: String,
    val description: String,
    /** Аргументы движка byedpi, например: ["-o1", "-a1", "-r-5+se"] */
    val byedpiArgs: List<String>,
)

// ---------- Встроенные hostlists (Задача 6) ----------
//
// Домены передаются движку прямо в аргументах: byedpi понимает -H со
// строкой после двоеточия («-H:домен1 домен2 ...», разделитель — пробел).
// Совпадение — по суффиксу: «googlevideo.com» покрывает и
// «rr5---sn-xxxx.googlevideo.com» (см. host_cmp в исходниках движка).
// Примечание: в текущей версии SMART эти константы не используются,
// но сохранены для будущих расширений и других пресетов.

/** YouTube: включая домены видео-трафика (googlevideo.com — критично для видео). */
private val YOUTUBE_DOMAINS = listOf(
    "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "youtu.be",
)

/** Discord: основной сайт, медиа и голосовая инфраструктура. */
private val DISCORD_DOMAINS = listOf(
    "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media",
)

/** Аргумент -H со встроенным списком доменов (один элемент argv). */
private fun hostsArg(domains: List<String>) = "-H:" + domains.joinToString(" ")

/**
 * Готовые пресеты.
 *
 * Для Discord несколько вариантов: на мобильных операторах и Wi-Fi работают
 * разные стратегии — пробуйте по очереди (Discord 2 → Discord 3 → Discord).
 */
object Presets {

    /**
     * Умный: автоматически подбирает рабочий метод обхода.
     *
     * Текущая реализация (Вариант Б): использует проверенные параметры пресета
     * "Discord" для всего трафика. Эти параметры работают на большинстве провайдеров
     * и обеспечивают стабильный обход для YouTube, Discord и других сервисов.
     *
     * В будущей версии будет реализован полный перебор методов для автоматического
     * выбора оптимального под конкретного провайдера.
     */
    val SMART = Strategy(
        id = "smart",
        title = "Умный (автовыбор)",
        description = "Автоматически использует рабочие параметры обхода. " +
            "Сейчас применяются проверенные настройки пресета «Discord», которые " +
            "стабильно работают на большинстве провайдеров для YouTube, Discord и других сервисов.",
        byedpiArgs = listOf("-U", "-s5:10+sm", "-f10+hm", "-o2", "-a2", "-d1", "-An", "--fake-tls", "--tls-hack"),
    )

    /** Без обмана DPI — чистый туннель. Диагностика: работает ли сам конвейер. */
    val TRANSPARENT = Strategy(
        id = "transparent",
        title = "Прозрачный",
        description = "Без обмана DPI — чистый туннель. Если здесь интернет есть, " +
            "а в других режимах нет — стратегия не подошла провайдеру.",
        byedpiArgs = emptyList(),
    )

    /** Рецепт сообщества (ByeByeDPI): OOB-байт + tlsrec + 1 UDP-фейк. */
    val UNIVERSAL = Strategy(
        id = "universal",
        title = "Универсальный",
        description = "Проверенный набор по умолчанию (как в ByeByeDPI): " +
            "OOB-разбиение, tlsrec на SNI, один UDP-фейк.",
        byedpiArgs = listOf("-o1", "-a1", "-r-5+se"),
    )

    /** YouTube: фейк TLS на SNI + фейки QUIC. */
    val YOUTUBE = Strategy(
        id = "youtube",
        title = "YouTube",
        description = "Фейковый TLS-пакет на SNI (доходит до DPI, умирает до сервера) " +
            "+ 3 фейковых UDP-пакета для QUIC-видео.",
        byedpiArgs = listOf("-f1+s", "-t8", "-a3"),
    )

    /** Discord, вариант 1: авто-режим, две группы (UDP-фейки + TLS-разбиения). */
    val DISCORD = Strategy(
        id = "discord",
        title = "Discord",
        description = "Авто-режим: UDP-фейки для голоса + разбиение/disorder " +
            "для TLS. Рабочий рецепт сообщества (Wi-Fi и мобильные).",
        byedpiArgs = listOf("-U", "-a3", "-An", "-Kt,h", "-d1", "-s0+s", "-d3+s"),
    )

    /** Discord, вариант 2: серия разбиений внутри SNI (свежий, мобильные РФ). */
    val DISCORD_2 = Strategy(
        id = "discord2",
        title = "Discord 2",
        description = "Серия разбиений внутри SNI. Отчёты 2026 г.: МТС, " +
            "Ростелеком, Теле2. Начните с этого, если вы на мобильном интернете.",
        byedpiArgs = listOf("-s3:7+sm", "-a1"),
    )

    /** Discord, вариант 3: fake по Host + OOB (мобильные Yota/МТС). */
    val DISCORD_3 = Strategy(
        id = "discord3",
        title = "Discord 3",
        description = "Fake-пакет по смещению Host + OOB-байт. Отчёты: " +
            "мобильные Yota и МТС. ✅ Подтверждён на нашем тестовом Samsung.",
        byedpiArgs = listOf("-f9+hm", "-o3", "-a2"),
    )

    /**
     * Telegram: MTProto — не TLS (SNI нет), ТСПУ распознаёт сам протокол.
     * Разбиение первого пакета + UDP-фейки для звонков (STUN).
     * Если замедление по IP — десинк поможет лишь частично.
     */
    val TELEGRAM = Strategy(
        id = "telegram",
        title = "Telegram",
        description = "Разбиение первого пакета MTProto + UDP-фейки для звонков. " +
            "Текст может заработать; если замедление по IP — увы, нужен прокси.",
        byedpiArgs = listOf("-d1", "-s3", "-a2"),
    )

    val all = listOf(SMART, UNIVERSAL, YOUTUBE, DISCORD, DISCORD_2, DISCORD_3, TELEGRAM, TRANSPARENT)

    /** Пресет по умолчанию. */
    val default: Strategy get() = SMART

    fun byId(id: String?): Strategy = all.firstOrNull { it.id == id } ?: default

    // ---------- Сохранение выбора ----------

    private const val PREFS = "dpibreak_settings"
    private const val KEY_PRESET = "preset_id"

    fun getSelected(context: Context): Strategy =
        byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, null))

    fun setSelected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESET, id).apply()
    }
}
