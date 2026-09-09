package com.dpibreak

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Главный экран.
 *
 * Скелет UI: статус + кнопка вкл/выкл.
 * TODO(Задача 1): подключить реальное состояние сервиса (ServiceManager + StateFlow).
 * TODO(Задача 7): выбор стратегии (пресеты YouTube/Discord/Telegram).
 * TODO(Задача 9): экран логов, per-app исключения.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    // Заглушка состояния — заменяется на сервисное в Задаче 2-3
    var active by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (active) "Обход работает" else "Обход выключен",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { active = !active /* TODO(Задача 2): startService/stopService */ }) {
                Text(if (active) "Выключить обход" else "Включить обход")
            }
        }
    }
}
