package com.dpibreak.domain

import android.content.Context

/**
 * Стратегия обхода = набор аргументов командной строки движка byedpi.
 *
 * Формат аргументов см. в README движка: app/src/main/jni/byedpi/README.md
 * Важно: рабочие параметры ЗАВИСЯТ ОТ ПРОВАЙДЕРА. Пресеты ниже — стартовые
 * значения из опыта сообщества (ByeByeDPI, zapret); их подбирают тестом.
 */
data class Strategy(
    val id: String,
    val title: String,
    val description: String,
    /** Аргументы движка byedpi, например: ["-o1", "-a1", "-r-5+se"] */
    val byedpiArgs: List<String>,
    /**
     * Список доменов (файл в assets/hostlists/), к которым применяется
     * стратегия через -H. TODO(Задача 6): подключить hostlists.
     */
    val hostlistFile: String? = null,
)

/**
 * Готовые пресеты.
 *
 * Значения подобраны из проверенных источников:
 *  - UNIVERSAL — дефолт приложения ByeByeDPI (тот же движок, тысячи пользователей);
 *  - YOUTUBE — фейк TLS на SNI + фейки QUIC (YouTube живёт на HTTP/3);
 *  - DISCORD — fake+split (из разборов Flowseal для Discord);
 *  - TELEGRAM — MTProto не содержит SNI, поэтому простое разбиение;
 *  - TRANSPARENT — без обмана, для диагностики самого туннеля.
 */
object Presets {

    /** Без обмана DPI — чистый туннель. Диагностика: работает ли сам конвейер. */
    val TRANSPARENT = Strategy(
        id = "transparent",
        title = "Прозрачный",
        description = "Без обмана DPI — чистый туннель. Если здесь интернет есть, " +
            "а в других режимах нет — стратегия не подошла провайдеру.",
        byedpiArgs = emptyList(),
    )

    /** Дефолт. Проверенный набор ByeByeDPI: OOB-байт + tlsrec + 1 UDP-фейк. */
    val UNIVERSAL = Strategy(
        id = "universal",
        title = "Универсальный",
        description = "Проверенный набор по умолчанию (как в ByeByeDPI): " +
            "OOB-разбиение, tlsrec на SNI, один UDP-фейк.",
        byedpiArgs = listOf("-o1", "-a1", "-r-5+se"),
    )

    /** YouTube: блокировка по SNI в TLS + QUIC. */
    val YOUTUBE = Strategy(
        id = "youtube",
        title = "YouTube",
        description = "Фейковый TLS-пакет на SNI (доходит до DPI, умирает до сервера) " +
            "+ 3 фейковых UDP-пакета для QUIC-видео.",
        byedpiArgs = listOf("-f1+s", "-t8", "-a3"),
    )

    /** Discord: шлюз TLS + голос UDP. */
    val DISCORD = Strategy(
        id = "discord",
        title = "Discord",
        description = "Фейк + разбиение ClientHello, UDP-фейк для голосовых каналов.",
        byedpiArgs = listOf("-f1+s", "-s2+s", "-t8", "-a1"),
    )

    /** Telegram: MTProto — не TLS, SNI нет; обычно и так доступен. */
    val TELEGRAM = Strategy(
        id = "telegram",
        title = "Telegram",
        description = "Простое разбиение первого пакета (MTProto — не TLS). " +
            "Если Telegram заблокирован по IP, десинк не поможет.",
        byedpiArgs = listOf("-s2", "-a1"),
    )

    val all = listOf(UNIVERSAL, YOUTUBE, DISCORD, TELEGRAM, TRANSPARENT)

    /** Пресет по умолчанию. */
    val default: Strategy get() = UNIVERSAL

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
