package com.dpibreak.core

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.dpibreak.core.vpn.TunnelVpnService
import com.dpibreak.domain.Presets

private const val TAG = "DPIBreak"

/**
 * Состояние VPN-сервиса.
 *
 * [ServiceState.Starting] — команда запустить отдана, сервис ещё не подтвердил,
 * что конвейер собран. Именно здесь раньше возникал рассинхрон: состояние сразу
 * ставили Active, и при медленном/неудачном старте UI врал пользователю.
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Starting : ServiceState()
    object Active : ServiceState()
    data class Error(val message: String) : ServiceState()
}

/**
 * Менеджер жизненного цикла сервиса: команды запуск/остановка + StateFlow состояния.
 *
 * Единственный источник истины по состояниям Active/Error/Idle — сам
 * [TunnelVpnService] (он вызывает [updateState], когда конвейер реально собран
 * или разобран). Менеджер отвечает только за переход в [ServiceState.Starting]
 * и за страховку на случай, если сервис не ответил.
 */
object ServiceManager {

    /** Сколько ждём подтверждения от сервиса, прежде чем считать запуск неудачным. */
    private const val START_TIMEOUT_MS = 15_000L

    /** Сколько ждём, пока сервис подтвердит остановку. */
    private const val STOP_TIMEOUT_MS = 10_000L

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    /**
     * Считается ли обход включённым (для кнопки «Выключить обход»).
     * [ServiceState.Starting] — тоже «включено», иначе двойной тап поднимет второй сервис.
     */
    val isRunning: Boolean
        get() = _state.value is ServiceState.Starting || _state.value is ServiceState.Active

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Страховка: завершилась ли переходная фаза (Starting/остановка) вовремя. */
    private var timeoutJob: Job? = null

    /**
     * Запускает сервис, если не запущен, иначе останавливает.
     */
    fun toggle(context: Context) {
        if (isRunning) stop(context) else start(context)
    }

    /**
     * Запускает VPN-сервис через startForegroundService.
     */
    fun start(context: Context) {
        if (isRunning) return

        val appContext = context.applicationContext
        _state.value = ServiceState.Starting

        // Выбранный пресет передаётся в сервис аргументами движка
        val preset = Presets.getSelected(appContext)
        val intent = Intent(appContext, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putStringArrayListExtra(
                TunnelVpnService.EXTRA_ENGINE_ARGS,
                ArrayList(preset.byedpiArgs)
            )
        }

        try {
            appContext.startForegroundService(intent)
        } catch (t: Throwable) {
            // Android 12+ может отказать в старте foreground-сервиса из фона.
            Log.e(TAG, "startForegroundService rejected", t)
            updateState(
                ServiceState.Error(
                    "Система запретила запуск сервиса (${t.javaClass.simpleName}). " +
                        "Откройте приложение и повторите."
                )
            )
            return
        }
        armTimeout(
            START_TIMEOUT_MS,
            onTimeout = {
                if (_state.value is ServiceState.Starting) {
                    updateState(
                        ServiceState.Error(
                            "Сервис не подтвердил запуск за " +
                                "${START_TIMEOUT_MS / 1000} с — попробуйте выключить и включить снова"
                        )
                    )
                    // Страховка: если сервис живой, но завис, не оставляем его висеть.
                    runCatching {
                        appContext.stopService(
                            Intent(appContext, TunnelVpnService::class.java)
                        )
                    }
                }
            }
        )
    }

    /**
     * Останавливает VPN-сервис.
     *
     * Состояние здесь НЕ меняется: Active → Idle переведёт сам сервис, когда
     * разберёт конвейер (иначе UI «опережает» реальность). Если он не ответит
     * за [STOP_TIMEOUT_MS], состояние сбрасываем принудительно.
     */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        val stopIntent = Intent(appContext, TunnelVpnService::class.java)
        try {
            stopIntent.action = TunnelVpnService.ACTION_STOP
            appContext.startService(stopIntent)
        } catch (t: Throwable) {
            // Приложение уже в фоне и startService недоступен — просим систему
            // остановить сервис напрямую (разборку сделаем в onDestroy).
            Log.w(TAG, "ACTION_STOP delivery failed, falling back to stopService", t)
            runCatching { appContext.stopService(stopIntent) }
        }
        armTimeout(
            STOP_TIMEOUT_MS,
            onTimeout = {
                if (isRunning) {
                    Log.w(TAG, "Service did not confirm stop — forcing Idle")
                    updateState(ServiceState.Idle)
                }
            }
        )
    }

    /**
     * Обновляет состояние сервиса (вызывается из [TunnelVpnService] при смене статуса).
     * Любой явный сигнал от сервиса снимает страховочный таймер.
     */
    fun updateState(newState: ServiceState) {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = newState
    }

    private fun armTimeout(timeoutMs: Long, onTimeout: suspend () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            timeoutJob = null
            onTimeout()
        }
    }
}
