package com.dpibreak.domain

import android.content.Context

/**
 * Стратегия обхода = набор аргументов командной строки движка byedpi.
 *
 * Формат аргументов см. в README движка: app/src/main/jni/byedpi/README.md
 * Важно: рабочие параметры ЗАВИСЯТ ОТ ПРОВАЙДЕРА И СЕТИ (Wi-Fi ≠ мобильный
 * интернет). Значения ниже — из проверенных рецептов сообщества
 * (ByeDPIAndroid, zapret), каждая проверена на живом движке в песочнице.
 *
 * ### Группировка параметров (--auto)
 * Byedpi делит аргументы на группы через `--auto=<триггер>`:
 * - Триггеры: torst (таймаут), ssl_err (ошибка SSL), redirect (перенаправление),
 *   none (всегда применять), conn (новое соединение)
 * - Фильтры: pf (порты), ipset (IP), proto (tls/http/udp), hosts (домены)
 * - Если фильтр не прошёл — переход к следующей группе
 *
 * Пример: `--auto=none --hosts youtube.com --fake 1+s --auto=ssl_err --oob 3`
 * → Для youtube.com: fake, для остальных при ssl_err: oob
 */
data class Strategy(
    val id: String,
    val title: String,
    val description: String,
    /** Аргументы движка byedpi, например: ["-o1", "-a1", "-r-5+se"] */
    val byedpiArgs: List<String>,
    /**
     * Список доменов для фильтрации трафика.
     * Если не пуст — передаётся в движок как `-H :домен1 домен2 ...`
     * (двоеточие обозначает inline-строку, а не файл).
     * Работает как whitelist: десинхронизация применяется ТОЛЬКО к этим доменам.
     * Совпадение по суффиксу: "googlevideo.com" покрывает "rr5---sn-xxxx.googlevideo.com".
     */
    val hostlist: List<String> = emptyList(),
    /**
     * Блокировать ли QUIC (UDP 443) на уровне VPN.
     * Нужно для стабильного видео (YouTube заставляет QUIC, который ломается без прокси).
     * При включении добавляется правило блокировки UDP 443 в VpnService.Builder.
     */
    val blockQuic: Boolean = false,
    /**
     * Список пакетов для исключения из VPN (per-app bypass).
     * Трафик этих приложений идёт напрямую, минуя туннель.
     * Полезно для банков, Госуслуг, локальных сервисов.
     */
    val excludedApps: List<String> = emptyList(),
)

// ---------- Встроенные hostlists и excludedApps (Задача 6, 7) ----------
//
// Домены передаются движку прямо в аргументах: byedpi понимает -H со
// строкой после двоеточия («-H :домен1 домен2 ...», разделитель — пробел).
// Совпадение — по суффиксу: «googlevideo.com» покрывает и
// «rr5---sn-xxxx.googlevideo.com» (см. parse_hosts в main.c).
//
// Пакеты исключений (excludedApps) — это package name приложений Android,
// трафик которых не должен идти через VPN. Используется Builder.addDisallowedApplication().

/** YouTube: включая домены видео-трафика (googlevideo.com — критично для видео). */
private val YOUTUBE_DOMAINS = listOf(
    "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "youtu.be",
)

/** Discord: основной сайт, медиа и голосовая инфраструктура. */
private val DISCORD_DOMAINS = listOf(
    "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media",
)

/** Telegram: основные домены + CDN. */
private val TELEGRAM_DOMAINS = listOf(
    "telegram.org", "telegram.me", "t.me", "tdesktop.com", "telegra.ph",
)

/** Банки РФ и критическая инфраструктура (для excludedApps). */
private val RU_BANKS_PACKAGES = listOf(
    "ru.sberbank.mobile",          // Сбербанк
    "ru.vtb.mobile",              // ВТБ
    "ru.tinkoff.mobile",          // Тинькофф
    "ru.gazprombank.android",     // Газпромбанк
    "ru.alfabank.mobile",         // Альфа-Банк
    "ru.pochtabank.mobile",       // Почта Банк
    "ru.mts.money",               // МТС Банк
    "ru.openmobile",              // Открытие
    "ru.raiffeisen.mobile",       // Райффайзен
    "ru.unicredit.mobile",        // ЮниКредит
    "com.bcs.app",                // БКС
    "ru.investing",               // Investing.com
)

/** Государственные сервисы (Госуслуги, налоги, МФЦ). */
private val GOV_SERVICES_PACKAGES = listOf(
    "ru.gosuslugi",               // Госуслуги
    "ru.nalog.flk",               // ФНС (налоги)
    "ru.mos.mmf",                 // МФЦ Москвы
    "ru.pfr.mobile",              // Пенсионный фонд
    "ru.fssp.mobile",             // ФССП
)

/** Социальные сети и мессенджеры (для точечного обхода). */
private val SOCIAL_MEDIA_PACKAGES = listOf(
    "com.instagram.android",
    "com.facebook.katana",
    "com.facebook.orca",          // Messenger
    "com.twitter.android",
    "com.zhiliaoapp.musically",   // TikTok
    "com.linkedin.android",
)

/** Аргумент -H со встроенным списком доменов (один элемент argv). */
private fun hostsArg(domains: List<String>): String = "-H:" + domains.joinToString(" ")

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
     * 
     * Включена блокировка QUIC для стабильного видео.
     */
    val SMART = Strategy(
        id = "smart",
        title = "Умный (автовыбор)",
        description = "Автоматически использует рабочие параметры обхода. " +
            "Сейчас применяются проверенные настройки пресета «Discord», которые " +
            "стабильно работают на большинстве провайдеров для YouTube, Discord и других сервисов. " +
            "QUIC заблокирован для стабильного видео.",
        byedpiArgs = listOf("-U", "-s5:10+sm", "-f10+hm", "-o2", "-a2", "-d1", "-An", "--fake-tls", "--tls-hack"),
        blockQuic = true,
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
            "+ 3 фейковых UDP-пакета для QUIC-видео. " +
            "Включена фильтрация по доменам YouTube и блокировка QUIC.",
        byedpiArgs = listOf("-f1+s", "-t8", "-a3") + hostsArg(YOUTUBE_DOMAINS),
        hostlist = YOUTUBE_DOMAINS,
        blockQuic = true,
    )

    /** Discord, вариант 1: авто-режим, две группы (UDP-фейки + TLS-разбиения). */
    val DISCORD = Strategy(
        id = "discord",
        title = "Discord",
        description = "Авто-режим: UDP-фейки для голоса + разбиение/disorder " +
            "для TLS. Рабочий рецепт сообщества (Wi-Fi и мобильные). " +
            "Включена фильтрация по доменам Discord.",
        byedpiArgs = listOf("-U", "-a3", "-An", "-Kt,h", "-d1", "-s0+s", "-d3+s") + hostsArg(DISCORD_DOMAINS),
        hostlist = DISCORD_DOMAINS,
    )

    /** Discord, вариант 2: серия разбиений внутри SNI (свежий, мобильные РФ). */
    val DISCORD_2 = Strategy(
        id = "discord2",
        title = "Discord 2",
        description = "Серия разбиений внутри SNI. Отчёты 2026 г.: МТС, " +
            "Ростелеком, Теле2. Начните с этого, если вы на мобильном интернете. " +
            "Включена фильтрация по доменам Discord.",
        byedpiArgs = listOf("-s3:7+sm", "-a1") + hostsArg(DISCORD_DOMAINS),
        hostlist = DISCORD_DOMAINS,
    )

    /** Discord, вариант 3: fake по Host + OOB (мобильные Yota/МТС). */
    val DISCORD_3 = Strategy(
        id = "discord3",
        title = "Discord 3",
        description = "Fake-пакет по смещению Host + OOB-байт. Отчёты: " +
            "мобильные Yota и МТС. ✅ Подтверждён на нашем тестовом Samsung. " +
            "Включена фильтрация по доменам Discord.",
        byedpiArgs = listOf("-f9+hm", "-o3", "-a2") + hostsArg(DISCORD_DOMAINS),
        hostlist = DISCORD_DOMAINS,
    )

    /**
     * Telegram: MTProto — не TLS (SNI нет), ТСПУ распознаёт сам протокол.
     * Разбиение первого пакета + UDP-фейки для звонков (STUN).
     * Если замедление по IP — десинк поможет лишь частично.
     * 
     * Включена фильтрация по доменам Telegram и список приложений для исключения.
     */
    val TELEGRAM = Strategy(
        id = "telegram",
        title = "Telegram",
        description = "Разбиение первого пакета MTProto + UDP-фейки для звонков. " +
            "Текст может заработать; если замедление по IP — увы, нужен прокси. " +
            "Включена фильтрация по доменам Telegram.",
        byedpiArgs = listOf("-d1", "-s3", "-a2") + hostsArg(TELEGRAM_DOMAINS),
        hostlist = TELEGRAM_DOMAINS,
        excludedApps = SOCIAL_MEDIA_PACKAGES,  // Мессенджеры в обход VPN
    )

    /**
     * Банки РФ: обход без модификаций для работы с Госуслугами и банковскими приложениями.
     * Эти приложения исключены из VPN-туннеля — трафик идёт напрямую.
     */
    val BANKING = Strategy(
        id = "banking",
        title = "Банки и Госуслуги",
        description = "Режим для работы с российскими банками и Госуслугами. " +
            "Приложения из списка исключены из VPN-туннеля и работают напрямую. " +
            "Используйте вместе с другими пресетами для остального трафика.",
        byedpiArgs = emptyList(),
        excludedApps = RU_BANKS_PACKAGES + GOV_SERVICES_PACKAGES,
    )

    val all = listOf(SMART, UNIVERSAL, YOUTUBE, DISCORD, DISCORD_2, DISCORD_3, TELEGRAM, BANKING, TRANSPARENT)

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
