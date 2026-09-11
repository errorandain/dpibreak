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

/** YouTube: включая домены видео-трафика (googlevideo.com — критично для видео). */
private val YOUTUBE_DOMAINS = listOf(
    "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "youtu.be",
    "youtubei.googleapis.com",
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
     * Умный: разные рецепты для разных сервисов + блокировка QUIC (Задача 6),
     * с перебором рецептов YouTube после сбросов (по логам с телефона, 2026-09:
     * главный API YouTube уходил без десинка, а движку был запрещён перебор).
     *
     * Группы byedpi (разделяются -A, проверяются слева направо; «-A t,s» включает
     * переход к следующей группе при TCP-сбросе (t) или TLS-ошибке (s), и движок
     * сам запоминает сработавший рецепт; «-A n» — без авто-переключений):
     *  1. «-U -Kh -An»     — UDP выключен целиком (это и есть блокировка QUIC:
     *     YouTube перестаёт пытаться играть видео через UDP 443 и переходит
     *     на TCP, где работает десинк). Обычный HTTP — без вмешательства.
     *  2. «-Kt -H:YT ...»  — домены YouTube (включая главный API
     *     youtubei.googleapis.com): рецепт «Универсального» (OOB-байт +
     *     tlsrec на SNI; -a1 убран — UDP всё равно выключен).
     *  3. те же домены     — фейковый TLS на SNI с TTL 8 («-f1+s -t8»):
     *     активируется, если рецепт 1 был сброшен провайдером.
     *  4. те же домены     — disorder («-d1»): если и фейк не прошёл.
     *  5. «-Kt -H:DC ...»  — домены Discord: разбиение внутри SNI (рецепт
     *     «Discord 2», свежие отчёты с мобильных операторов РФ); перебор выключен.
     *  6. «-A n -Kt,h»     — остальной TLS/HTTP — без вмешательства.
     *
     * DNS работает через mapdns (см. TunSocksBridge) — поэтому при -U
     * резолвинг не ломается.
     *
     * Минус режима: без UDP не работают звонки в мессенджерах (Discord-голос,
     * Telegram/WhatsApp-звонки) и веб-чаты на QUIC/HTTP3. Для звонков —
     * остальные пресеты. Если веб-чаты сломаются — вместо полного «-U»
     * блокировать только UDP-порт 443 (нужна небольшая доработка
     * вендоренного byedpi).
     */
    val SMART = Strategy(
        id = "smart",
        title = "Умный (YouTube + Discord)",
        description = "YouTube пробует 3 рецепта по очереди и сам запоминает " +
            "рабочий (включая главный API youtubei.googleapis.com). QUIC (UDP) " +
            "выключен: видео идёт по TCP. Минус: звонки в мессенджерах в этом " +
            "режиме не работают.",
        byedpiArgs = listOf(
            // 1. UDP выключен (анти-QUIC), HTTP — без вмешательства
            "-U", "-Kh", "-An",
            // 2. YouTube, рецепт 1: OOB + tlsrec (как в «Универсальном»)
            "-Kt", hostsArg(YOUTUBE_DOMAINS), "-o1", "-r-5+se",
            // 3. YouTube, рецепт 2: если 1-й сброшен — фейковый SNI с TTL 8
            "-A", "t,s", "-Kt", hostsArg(YOUTUBE_DOMAINS), "-f1+s", "-t8",
            // 4. YouTube, рецепт 3: если и фейк не прошёл — disorder
            "-A", "t,s", "-Kt", hostsArg(YOUTUBE_DOMAINS), "-d1",
            // 5. Discord: разбиение внутри SNI, без авто-переключений
            "-A", "n", "-Kt", hostsArg(DISCORD_DOMAINS), "-s3:7+sm",
            // 6. Остальной трафик — без вмешательства
            "-A", "n", "-Kt,h",
        ),
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
        byedpiArgs = listOf("-Ku", "-a3", "-An", "-Kt,h", "-d1", "-s0+s", "-d3+s"),
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
