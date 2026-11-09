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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpibreak.core.ServiceManager
import com.dpibreak.core.ServiceState
import com.dpibreak.core.engine.StrategyTester
import com.dpibreak.domain.Presets

/**
 * Главный экран: статус, выбор пресета обхода, автоподбор стратегии, ввод строки ByeByeDPI и настройки туннеля.
 */
class MainActivity : ComponentActivity() {

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
    ) { _ ->
        // Foreground-сервис работает и без разрешения на уведомления.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    internal fun onToggleClick() {
        if (ServiceManager.isRunning) {
            ServiceManager.stop(this)
        } else {
            requestVpnAndStart()
        }
    }

    internal fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            ServiceManager.start(this)
            return
        }
        vpnPermissionLauncher.launch(prepareIntent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(activity: MainActivity) {
    val serviceState by ServiceManager.state.collectAsStateWithLifecycle()
    val testerState by StrategyTester.state.collectAsStateWithLifecycle()
    val context = activity

    val isActive = serviceState is ServiceState.Active
    val isStarting = serviceState is ServiceState.Starting
    val isTesting = testerState is StrategyTester.State.Running
    val isButtonEnabled = !isStarting && !isTesting
    val canEditSettings =
        (serviceState is ServiceState.Idle || serviceState is ServiceState.Error) && !isTesting

    var selectedPreset by remember { mutableStateOf(Presets.getSelected(context)) }
    var customCmd by remember { mutableStateOf(Presets.getCustomCmd(context)) }
    var blockQuic by remember { mutableStateOf(Presets.isBlockQuicEnabled(context)) }
    var ipv6Enabled by remember { mutableStateOf(Presets.isIpv6Enabled(context)) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
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

            Spacer(modifier = Modifier.height(20.dp))

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
                        enabled = canEditSettings,
                        label = { Text(preset.title) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = selectedPreset.description +
                    if (!canEditSettings && !isTesting) "\n\n" + stringResource(R.string.preset_locked_hint) else "",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            if (selectedPreset.id == Presets.CUSTOM.id) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customCmd,
                    onValueChange = { value ->
                        customCmd = value
                        Presets.setCustomCmd(context, value)
                    },
                    enabled = canEditSettings,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.custom_cmd_label)) },
                    placeholder = { Text(stringResource(R.string.custom_cmd_placeholder)) },
                    minLines = 2,
                    maxLines = 5
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            SettingToggleRow(
                title = stringResource(R.string.opt_block_quic_title),
                description = stringResource(R.string.opt_block_quic_desc),
                checked = blockQuic,
                enabled = canEditSettings,
                onCheckedChange = { checked ->
                    blockQuic = checked
                    Presets.setBlockQuicEnabled(context, checked)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            SettingToggleRow(
                title = stringResource(R.string.opt_ipv6_title),
                description = stringResource(R.string.opt_ipv6_desc),
                checked = ipv6Enabled,
                enabled = canEditSettings,
                onCheckedChange = { checked ->
                    ipv6Enabled = checked
                    Presets.setIpv6Enabled(context, checked)
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            when (val ts = testerState) {
                is StrategyTester.State.Running -> {
                    Text(
                        text = "Подбор стратегии ${ts.currentIndex}/${ts.totalCount}: ${ts.currentCmd}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                is StrategyTester.State.Completed -> {
                    val resultMsg = if (ts.winningCmd != null) {
                        "Подобрана стратегия (${ts.score}/${ts.maxScore}): ${ts.winningCmd}\nНажмите «Включить обход»."
                    } else {
                        "Ни одна из базовых стратегий не получила ответ. Проверьте интернет или укажите свою строку."
                    }
                    Text(
                        text = resultMsg,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = if (ts.winningCmd != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                StrategyTester.State.Idle -> Unit
            }

            Button(
                onClick = { activity.onToggleClick() },
                enabled = isButtonEnabled
            ) {
                Text(
                    if (isActive) stringResource(R.string.btn_stop)
                    else stringResource(R.string.btn_start)
                )
            }

            if (!isActive && !isStarting) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (isTesting) {
                            StrategyTester.cancel()
                        } else {
                            StrategyTester.start(context) { winningCmd ->
                                customCmd = winningCmd
                                selectedPreset = Presets.CUSTOM
                            }
                        }
                    }
                ) {
                    Text(
                        if (isTesting) stringResource(R.string.btn_autotest_stop)
                        else stringResource(R.string.btn_autotest)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
