package com.dpibreak

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpibreak.core.ServiceManager
import com.dpibreak.core.ServiceState
import com.dpibreak.domain.Presets

/**
 * Главный экран: статус, выбор пресета обхода, кнопка вкл/выкл.
 */
class MainActivity : ComponentActivity() {

    /**
     * Системный диалог разрешения VPN. Раньше результат игнорировался: после
     * согласия приходилось нажимать кнопку «Включить» второй раз, а при отказе
     * статус так и оставался «Обход выключен» без объяснения причины.
     */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ServiceManager.start(this)
        } else {
            ServiceManager.updateState(
                ServiceState.Error("Разрешение на VPN не выдано — без него TUN-интерфейс не поднять")
            )
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Разрешение на уведомления получено или отклонено — не критично:
        // foreground-сервис работает и без него, просто без уведомления.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Разрешение на уведомления для Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                MainScreen(this)
            }
        }
    }

    /** Запрашивает разрешение VPN и только потом запускает сервис. */
    internal fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            ServiceManager.toggle(this)
            return
        }
        // Разрешения нет — значит рабочий туннеля точно отсутствует (например,
        // отзывали в настройках). Возвращаем UI в Idle, иначе start() после
        // диалога увидит «уже запущено» и промолчит.
        if (ServiceManager.isRunning) {
            ServiceManager.updateState(ServiceState.Idle)
        }
        vpnPermissionLauncher.launch(prepareIntent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(activity: MainActivity) {
    val serviceState by ServiceManager.state.collectAsStateWithLifecycle()
    val context = activity

    val isActive = serviceState is ServiceState.Active
    val isStarting = serviceState is ServiceState.Starting
    val isButtonEnabled = !isStarting

    // Пресет обхода: выбирается чипами, сохраняется в SharedPreferences
    var selectedPreset by remember { mutableStateOf(Presets.getSelected(context)) }
    // Менять пресет можно только при выключенном обходе
    val canEditPreset = serviceState is ServiceState.Idle || serviceState is ServiceState.Error

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (val s = serviceState) {
                    is ServiceState.Idle -> stringResource(R.string.status_idle)
                    is ServiceState.Starting -> stringResource(R.string.status_starting)
                    is ServiceState.Active -> stringResource(R.string.status_active)
                    is ServiceState.Error -> "Ошибка: ${s.message}"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Выбор пресета ---
            Text(
                text = stringResource(R.string.preset_picker_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Presets.all.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset.id == preset.id,
                        onClick = {
                            selectedPreset = preset
                            Presets.setSelected(context, preset.id)
                        },
                        enabled = canEditPreset,
                        label = { Text(preset.title) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = selectedPreset.description +
                    if (!canEditPreset) "\n\n" + stringResource(R.string.preset_locked_hint) else "",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { activity.requestVpnAndStart() },
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
