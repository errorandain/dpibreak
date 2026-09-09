package com.dpibreak.domain

/**
 * Режим работы приложения.
 *
 * VPN_ALL       — весь трафик устройства через TUN (основной режим).
 * VPN_SELECTED  — через TUN идут только выбранные приложения
 *                 (Builder.addDisallowedApplication для остальных).
 * LOCAL_PROXY   — TUN не поднимается: приложение просто держит локальный
 *                 SOCKS5 (удобно для связки с AdGuard и т.п.).
 */
enum class ProxyMode { VPN_ALL, VPN_SELECTED, LOCAL_PROXY }
