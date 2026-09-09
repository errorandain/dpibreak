package com.dpibreak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpibreak.core.ServiceManager
import com.dpibreak.core.ServiceState
import com.dpibreak.core.vpn.TunnelVpnService

/**
 * Главный экран.
 *
 * Скелет UI: статус + кнопка вкл/выкл.
 * TODO(Задача 1): подключить реальное состояние сервиса (ServiceManager + StateFlow).
 * TODO(Задача 7): выбор стратегии (пресеты YouTube/Discord/Telegram).
 * TODO(Задача 9): экран логов, per-app исключения.
 */
class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Пользователь дал или отклонил разрешение VPN
        // В любом случае продолжаем: если отклонил — сервис не запустится, onRevoke не вызовется
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Разрешение получено или отклонено — сервис можно запускать в любом случае
        // (на Android < 13 разрешение не требуется)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрашиваем разрешение на уведомления для Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение уже есть
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        setContent {
            MaterialTheme {
                MainScreen(this)
            }
        }
    }
}

@Composable
fun MainScreen(activity: MainActivity) {
    val serviceState by ServiceManager.state.collectAsStateWithLifecycle()
    val context = activity

    val isActive = when (serviceState) {
        is ServiceState.Active -> true
        else -> false
    }

    // Защита от двойного клика: если статус Starting, кнопка неактивна
    val isButtonEnabled = serviceState !is ServiceState.Starting

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (serviceState) {
                    is ServiceState.Idle -> stringResource(R.string.status_idle)
                    is ServiceState.Starting -> "Запуск..."
                    is ServiceState.Active -> stringResource(R.string.status_active)
                    is ServiceState.Error -> "Ошибка: ${(serviceState as ServiceState.Error).message}"
                },
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    // Запрос разрешения VPN через VpnService.prepare()
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        // Требуется подтверждение пользователя
                        activity.vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        // Разрешение уже есть — запускаем сервис
                        ServiceManager.toggle(context)
                    }
                },
                enabled = isButtonEnabled
            ) {
                Text(
                    if (isActive) stringResource(R.string.btn_stop)
                    else stringResource(R.string.btn_start)
                )
            }
        }
    }
}
