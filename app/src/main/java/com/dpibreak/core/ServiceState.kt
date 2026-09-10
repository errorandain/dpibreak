package com.dpibreak.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dpibreak.core.vpn.TunnelVpnService
import com.dpibreak.domain.Presets

/**
 * Состояние VPN-сервиса.
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Starting : ServiceState()
    object Active : ServiceState()
    data class Error(val message: String) : ServiceState()
}

/**
 * Менеджер жизненного цикла сервиса: запуск/остановка + StateFlow состояния.
 * Используется из UI (MainActivity) для отображения статуса и управления.
 */
object ServiceManager {

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private var isServiceRunning = false

    /**
     * Запускает сервис, если не запущен, иначе останавливает.
     */
    fun toggle(context: Context) {
        if (isServiceRunning) {
            stop(context)
        } else {
            start(context)
        }
    }

    /**
     * Запускает VPN-сервис через startForegroundService.
     */
    fun start(context: Context) {
        if (isServiceRunning) return

        _state.value = ServiceState.Starting

        // Выбранный пресет передаётся в сервис аргументами движка
        val preset = Presets.getSelected(context)
        val intent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putStringArrayListExtra(
                TunnelVpnService.EXTRA_ENGINE_ARGS,
                ArrayList(preset.byedpiArgs)
            )
        }

        context.startForegroundService(intent)
        isServiceRunning = true
        _state.value = ServiceState.Active
    }

    /**
     * Останавливает VPN-сервис.
     */
    fun stop(context: Context) {
        if (!isServiceRunning) return

        val intent = Intent(context, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        }

        context.startService(intent)
        isServiceRunning = false
        _state.value = ServiceState.Idle
    }

    /**
     * Устанавливает состояние ошибки (вызывается из сервиса при ошибке).
     */
    fun setError(message: String) {
        isServiceRunning = false
        _state.value = ServiceState.Error(message)
    }

    /**
     * Обновляет состояние сервиса (вызывается из сервиса при смене статуса).
     */
    fun updateState(newState: ServiceState) {
        if (newState is ServiceState.Idle || newState is ServiceState.Error) {
            isServiceRunning = false
        } else if (newState is ServiceState.Active) {
            isServiceRunning = true
        }
        _state.value = newState
    }

    /**
     * Сбрасывает состояние в Idle (например, после onRevoke).
     */
    fun resetToIdle() {
        isServiceRunning = false
        _state.value = ServiceState.Idle
    }
}
