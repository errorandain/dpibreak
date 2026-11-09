package com.dpibreak.domain

import android.content.Context

/**
 * Стратегия обхода = набор аргументов командной строки движка byedpi.
 *
 * Формат аргументов см. в README движка: app/src/main/jni/byedpi/README.md
 * Важно: рабочие параметры ЗАВИСЯТ ОТ ПРОВАЙДЕРА И СЕТИ (Wi-Fi ≠ мобильный
 * интернет). Значения ниже основаны на эталонном ByeByeDPI (romanvht/ByeByeDPI).
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
 * Готовые пресеты и настройки совместимости с эталонным ByeByeDPI.
 */
object Presets {

    /** Дефолтная командная строка из ByeByeDPI (PreferencesUtils.DEFAULT_CMD_ARGS). */
    const val DEFAULT_BYEBYEDPI_CMD = "-o1 -a1 -r-5+se"

    /** Дефолт. Точная копия строки по умолчанию из эталонного ByeByeDPI. */
    val UNIVERSAL = Strategy(
        id = "universal",
        title = "Универсальный",
        description = "Эталон ByeByeDPI из коробки (-o1 -a1 -r-5+se): " +
            "OOB-байт, разбиение TLS-записи на SNI и один UDP-фейк.",
        byedpiArgs = listOf("-o1", "-a1", "-r-5+se"),
    )

    /**
     * YouTube / Авто-подбор из проверенных групп ByeByeDPI:
     * 1) дефолт ByeByeDPI;
     * 2) при сбросе или зависании за 2,5 с (-T + -At,r,s) — серия disorder+split
     *    внутри SNI без TTL-фейков (не ломает близкие серверы Google Global Cache);
     * 3) запасная группа с рандомизированным TLS-фейком (-Qr).
     */
    val YOUTUBE = Strategy(
        id = "youtube",
        title = "YouTube (авто)",
        description = "Автопереключение между 3 группами ByeByeDPI: если провайдер " +
            "режет первую попытку, за 2,5 с переключается на disorder/split для YouTube.",
        byedpiArgs = listOf(
            "-T2.5:0.3:2:5000",
            "-o1", "-r-5+se", "-a1",
            "-At,r,s",
            "-d1", "-s1+s", "-d3+s", "-s6+s", "-d9+s", "-a1",
            "-At,r,s",
            "-n", "www.google.com", "-Qr", "-f-1", "-r1+s", "-a1",
        ),
    )

    /**
     * Мобильный интернет (МТС, МегаФон, Билайн, Т2, Yota):
     * мелкая нарезка + disorder внутри SNI без фейковых пакетов с высоким TTL,
     * которые в мобильных сетях долетают до кэш-серверов оператора и портят TLS.
     */
    val MOBILE = Strategy(
        id = "mobile",
        title = "Мобильный",
        description = "Для мобильных сетей (МТС, МегаФон, Билайн, Т2, Yota): " +
            "частое разбиение + disorder по SNI без TTL-фейков.",
        byedpiArgs = listOf(
            "-d1", "-s1+s", "-d3+s", "-s6+s", "-d9+s",
            "-s12+s", "-d15+s", "-r1+s", "-a1",
        ),
    )

    /** Discord, вариант 1: авто-режим, две группы (UDP-фейки + TLS-разбиения). */
    val DISCORD = Strategy(
        id = "discord",
        title = "Discord",
        description = "Авто-режим: UDP-фейки для голоса + разбиение/disorder " +
            "для TLS. Рабочий рецепт сообщества (Wi-Fi и мобильные).",
        byedpiArgs = listOf("-Ku", "-a3", "-An", "-Kt,h", "-d1", "-s0+s", "-d3+s"),
    )

    /** Discord, вариант 2: серия разбиений внутри SNI (мобильные РФ). */
    val DISCORD_2 = Strategy(
        id = "discord2",
        title = "Discord 2",
        description = "Серия разбиений внутри SNI. Отчёты: МТС, " +
            "Ростелеком, Т2/Tele2.",
        byedpiArgs = listOf("-s3:7+sm", "-a1"),
    )

    /** Discord, вариант 3: fake по Host + OOB (мобильные Yota/МТС). */
    val DISCORD_3 = Strategy(
        id = "discord3",
        title = "Discord 3",
        description = "Fake-пакет по смещению Host + OOB-байт. Отчёты: " +
            "мобильные Yota и МТС.",
        byedpiArgs = listOf("-f9+hm", "-o3", "-a2"),
    )

    /** Telegram: разбиение первого пакета MTProto + UDP-фейки для звонков. */
    val TELEGRAM = Strategy(
        id = "telegram",
        title = "Telegram",
        description = "Разбиение первого пакета MTProto + UDP-фейки для звонков.",
        byedpiArgs = listOf("-d1", "-s3", "-a2"),
    )

    /** Своя командная строка — можно вставить любую рабочую строку прямо из ByeByeDPI. */
    val CUSTOM = Strategy(
        id = "custom",
        title = "Своя строка",
        description = "Вставьте сюда рабочую строку аргументов из ByeByeDPI " +
            "(например, подобранную через «Подбор стратегий»).",
        byedpiArgs = emptyList(),
    )

    /** Без обмана DPI — чистый туннель для диагностики. */
    val TRANSPARENT = Strategy(
        id = "transparent",
        title = "Прозрачный",
        description = "Без обмана DPI — чистый туннель. Для проверки того, " +
            "что обычный интернет через туннель проходит.",
        byedpiArgs = emptyList(),
    )

    val all = listOf(
        UNIVERSAL,
        YOUTUBE,
        MOBILE,
        DISCORD,
        DISCORD_2,
        DISCORD_3,
        TELEGRAM,
        CUSTOM,
        TRANSPARENT,
    )

    /** Пресет по умолчанию — эталон ByeByeDPI. */
    val default: Strategy get() = UNIVERSAL

    fun byId(id: String?): Strategy = all.firstOrNull { it.id == id } ?: default

    // ---------- Сохранение выбора и настроек туннеля ----------

    private const val PREFS = "dpibreak_settings"
    private const val KEY_PRESET = "preset_id"
    private const val KEY_CUSTOM_CMD = "custom_cmd"
    private const val KEY_IPV6_ENABLED = "ipv6_enabled"
    private const val KEY_BLOCK_QUIC = "block_quic"

    fun getSelected(context: Context): Strategy =
        byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, null))

    fun setSelected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESET, id).apply()
    }

    fun getCustomCmd(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_CMD, null) ?: DEFAULT_BYEBYEDPI_CMD

    fun setCustomCmd(context: Context, cmd: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_CMD, cmd).apply()
    }

    /**
     * IPv6 в TUN-интерфейсе. По умолчанию ВЫКЛЮЧЕНО (false) — точно как в
     * эталонном ByeByeDPI (`ipv6_enable = false`). На мобильных сетях без
     * реального внешнего IPv6 включённый маршрут ::/0 в TUN ломает YouTube,
     * Discord и другие двухстековые сайты.
     */
    fun isIpv6Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_IPV6_ENABLED, false)

    fun setIpv6Enabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IPV6_ENABLED, enabled).apply()
    }

    /**
     * Блокировать ли QUIC (UDP порт 443), чтобы YouTube и браузеры сразу
     * переходили на TCP 443 (где работают техники обхода TLS), не зависая на
     * заблокированном у оператора QUIC. DNS (UDP 53) и звонки продолжают работать.
     */
    fun isBlockQuicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLOCK_QUIC, false)

    fun setBlockQuicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BLOCK_QUIC, enabled).apply()
    }

    /** Итоговый список аргументов byedpi для выбранной стратегии. */
    fun resolveEffectiveArgs(
        context: Context,
        strategy: Strategy = getSelected(context),
    ): List<String> =
        if (strategy.id == CUSTOM.id) {
            parseByeDpiCmdArgs(getCustomCmd(context))
        } else {
            strategy.byedpiArgs
        }

    /**
     * Разбор командной строки в формате ByeByeDPI (совместимо с
     * `ByeDpiProxyCmdPreferences` и `ArgumentsUtils.shellSplit` из эталона):
     * поддерживает кавычки, переводы строк, плейсхолдер `{sni}`, отбрасывает
     * ведущее имя программы (`ciadpi`/`byedpi`) и служебные флаги адреса/порта.
     */
    fun parseByeDpiCmdArgs(rawCommand: String): List<String> {
        val prepared = rawCommand.replace("{sni}", "www.google.com")
        val tokens = shellSplit(prepared).dropWhile { !it.startsWith("-") }
        val forbiddenFlags = setOf("--help", "--version", "-h", "-v")

        val result = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token in forbiddenFlags) continue
            if (token == "--ip" || token == "--port" || token == "--po" || token == "--por" ||
                token == "-i" || token == "-p" || token == "-P"
            ) {
                if (i < tokens.size) i++ // пропускаем значение адреса/порта
                continue
            }
            if (token.startsWith("--ip=") || token.startsWith("--port=") ||
                token.startsWith("-i") && token.length > 2 ||
                token.startsWith("-p") && token.length > 2 ||
                token.startsWith("-P") && token.length > 2
            ) {
                continue
            }
            result.add(token)
        }
        return result
    }

    /** Разбиение строки аргументов с учётом одинарных/двойных кавычек (как в ByeByeDPI). */
    fun shellSplit(string: CharSequence): List<String> {
        val tokens = ArrayList<String>()
        var quoteChar = ' '
        var escaping = false
        var quoting = false
        var lastCloseQuoteIndex = Int.MIN_VALUE
        var current = StringBuilder()

        for (i in string.indices) {
            val c = string[i]
            when {
                escaping -> {
                    current.append(c)
                    escaping = false
                }
                c == '\\' && quoting -> {
                    if (i + 1 < string.length && string[i + 1] == quoteChar) {
                        escaping = true
                    } else {
                        current.append(c)
                    }
                }
                quoting && c == quoteChar -> {
                    quoting = false
                    lastCloseQuoteIndex = i
                }
                !quoting && (c == '\'' || c == '"') -> {
                    quoting = true
                    quoteChar = c
                }
                !quoting && Character.isWhitespace(c) -> {
                    if (current.isNotEmpty() || lastCloseQuoteIndex == i - 1) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty() || lastCloseQuoteIndex == string.length - 1) {
            tokens.add(current.toString())
        }
        return tokens
    }
}
