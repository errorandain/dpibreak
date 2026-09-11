package com.dpibreak.domain

import android.content.Context
import android.content.SharedPreferences

/**
 * Универсальный пресет обхода DPI.
 * Использует единую стратегию UniversalStrategy для всех случаев.
 */
object Presets {
    
    private const val PREFS_NAME = "dpibreak_prefs"
    private const val KEY_SELECTED_PRESET = "selected_preset_id"
    
    /** Единственный доступный пресет - универсальный */
    val UNIVERSAL = Preset(
        id = "universal",
        title = "🚀 Универсальный обход",
        description = "Автоматически подбирает методы обхода для YouTube, Telegram, Discord и других сервисов. Блокирует QUIC для стабильной работы.",
        byedpiArgs = UniversalStrategy.Instance.arguments,
        blockQuic = UniversalStrategy.Instance.blockQuic,
        excludedApps = UniversalStrategy.Instance.excludedApps
    )
    
    /** Все доступные пресеты (пока только один) */
    val all: List<Preset> = listOf(UNIVERSAL)
    
    /** Получить выбранный пресет из SharedPreferences */
    fun getSelected(context: Context): Preset {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(KEY_SELECTED_PRESET, UNIVERSAL.id) ?: UNIVERSAL.id
        return all.find { it.id == selectedId } ?: UNIVERSAL
    }
    
    /** Сохранить выбранный пресет в SharedPreferences */
    fun setSelected(context: Context, presetId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_PRESET, presetId).apply()
    }
}

/**
 * Модель пресета для UI.
 */
data class Preset(
    val id: String,
    val title: String,
    val description: String,
    val byedpiArgs: List<String>,
    val blockQuic: Boolean = false,
    val excludedApps: List<String> = emptyList()
)
