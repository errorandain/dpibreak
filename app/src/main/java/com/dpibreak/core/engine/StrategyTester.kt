package com.dpibreak.core.engine

import android.content.Context
import android.util.Log
import com.dpibreak.domain.Presets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Автоматический подбор рабочей стратегии обхода DPI прямо на устройстве
 * по образцу TestService / SiteCheckUtils из эталонного ByeByeDPI.
 *
 * Запускается только по явному нажатию пользователя при выключенном VPN:
 * поднимает локальный SOCKS5-прокси byedpi на тестовом порту и проверяет реальное
 * прохождение TLS ClientHello и HTTP-ответа к целевым сервисам.
 */
object StrategyTester {

    sealed interface State {
        data object Idle : State
        data class Running(
            val currentIndex: Int,
            val totalCount: Int,
            val currentCmd: String,
            val bestScore: Int,
            val maxScore: Int
        ) : State
        data class Completed(
            val winningCmd: String?,
            val score: Int,
            val maxScore: Int
        ) : State
    }

    private const val TAG = "StrategyTester"
    private const val TEST_PORT = 1089
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 3500

    /** Целевые HTTPS-узлы (как в proxytest_*.sites эталонного ByeByeDPI). */
    private val TEST_URLS = listOf(
        "https://www.youtube.com/generate_204",
        "https://manifest.googlevideo.com",
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg",
        "https://discord.com"
    )

    /**
     * Набор разнообразных проверенных стратегий из proxytest_strategies.list (ByeByeDPI)
     * для мобильных и домашних операторов (ТСПУ).
     */
    val CANDIDATE_STRATEGIES: List<String> = listOf(
        "-o1 -a1 -r-5+se",
        "-s1 -d3+s -r1+s -a1",
        "-q1 -r1+s -a1",
        "-d1 -d3+s -d9+s -s6+s -s12+s -r1+s -a1",
        "-o1 -o25+s -r1+s -f-1 -Qr -n www.google.com -a1",
        "-s1 -o1+s -d3+s -r-5+se -S -a1",
        "-q1 -q5+s -r-5+s -f-1 -Qr -n ya.ru -a1",
        "-d1 -r1+s -M h,d,r -a1",
        "-f-1 -Qr -t8 -s1+s -d3+s -n vk.com -a1",
        "-T2.5 -o1 -a1 -r-5+se -At,r,s -s1 -d3+s -r1+s -At,r,s -f-1 -Qr -o1+s -n www.google.com"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Запускает перебор стратегий. При нахождении стратегии со 100% результатом
     * завершает проверку досрочно и сохраняет её в пользовательскую строку (Presets.CUSTOM).
     */
    fun start(context: Context, onStrategyApplied: (String) -> Unit) {
        if (activeJob?.isActive == true) return
        val appContext = context.applicationContext
        val ipv6Enabled = Presets.isIpv6Enabled(appContext)

        activeJob = scope.launch {
            val engine = ByedpiJniEngine()
            var bestCmd: String? = null
            var bestScore = 0
            val maxScore = TEST_URLS.size
            var cancelled = false

            try {
                for ((idx, cmd) in CANDIDATE_STRATEGIES.withIndex()) {
                    ensureActive()
                    _state.value = State.Running(
                        currentIndex = idx + 1,
                        totalCount = CANDIDATE_STRATEGIES.size,
                        currentCmd = cmd,
                        bestScore = bestScore,
                        maxScore = maxScore
                    )

                    val baseArgs = buildList {
                        add("-i")
                        add("127.0.0.1")
                        if (!ipv6Enabled) {
                            add("-X")
                        }
                        addAll(Presets.parseByeDpiCmdArgs(cmd))
                    }

                    val port = engine.start(baseArgs, TEST_PORT)
                    if (port == null) {
                        Log.w(TAG, "Failed to start test engine for: $cmd")
                        engine.stop()
                        continue
                    }

                    val score = try {
                        checkAllTargets(port)
                    } finally {
                        engine.stop()
                    }
                    Log.i(TAG, "Strategy [${idx + 1}/${CANDIDATE_STRATEGIES.size}] '$cmd' -> $score/$maxScore")

                    if (score > bestScore) {
                        bestScore = score
                        bestCmd = cmd
                    }

                    // Если все цели (YouTube + GoogleVideo + Discord) ответили по HTTPS — готово.
                    if (score == maxScore) {
                        break
                    }
                    delay(150)
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Strategy test failed", e)
            } finally {
                // Гарантируем полную остановку потока byedpi до разблокировки кнопки запуска VPN.
                engine.stop()
                if (cancelled) {
                    _state.value = State.Idle
                } else {
                    val winner = bestCmd?.takeIf { bestScore > 0 }
                    if (winner != null) {
                        Presets.setCustomCmd(appContext, winner)
                        Presets.setSelected(appContext, Presets.CUSTOM.id)
                        withContext(Dispatchers.Main) {
                            onStrategyApplied(winner)
                        }
                    }
                    _state.value = State.Completed(
                        winningCmd = winner,
                        score = bestScore,
                        maxScore = maxScore
                    )
                }
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    private suspend fun checkAllTargets(port: Int): Int = coroutineScope {
        TEST_URLS.map { url ->
            async(Dispatchers.IO) {
                if (checkSingleUrl(url, port)) 1 else 0
            }
        }.awaitAll().sum()
    }

    private fun checkSingleUrl(urlString: String, port: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val url = URL(urlString)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/130.0.0.0 Mobile")
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            stream?.use { input ->
                val buf = ByteArray(512)
                input.read(buf)
            }
            code in 100..599
        } catch (e: Exception) {
            Log.d(TAG, "Check failed for $urlString: ${e.message}")
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
