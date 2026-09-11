package com.dpibreak.core.net

import com.dpibreak.core.engine.ByedpiJniEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Самопроверка связи — отвечает на вопрос «работает ли голос» без звонка.
 *
 * Приложение исключено из VPN, поэтому проверяем через SOCKS5 движка
 * (127.0.0.1:1080) — это ровно тот путь, которым туннель гоняет трафик:
 *
 *  1. TCP: SOCKS5 CONNECT до discord.com:443 — путь текстовых каналов;
 *  2. UDP: SOCKS5 UDP ASSOCIATE + DNS-запрос к 1.1.1.1 — путь голосовых
 *     каналов (тот же UDP-ретранслятор byedpi, что и для звонков).
 *
 * Логика та же, что в CI-тестах scripts/engine_tests/engine_smoke.py.
 */
object ConnectionSelfTest {

    private const val SOCKS_HOST = "127.0.0.1"
    private const val DNS_IP = "1.1.1.1"
    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 8000

    /** Результат проверки с человеческим вердиктом. */
    data class Result(
        val socksReachable: Boolean,
        val tcpOk: Boolean,
        val tcpDetail: String,
        val udpOk: Boolean,
        val udpDetail: String,
    ) {
        /** Короткий вывод для пользователя. */
        val verdict: String
            get() = when {
                !socksReachable ->
                    "Движок не отвечает. Включите обход и попробуйте ещё раз."
                tcpOk && udpOk ->
                    "✅ Всё работает: текст и голос. Можно звонить."
                tcpOk ->
                    "⚠️ Текст работает, голос — нет: UDP-пакеты не проходят " +
                        "(оператор режет UDP). Звонки не заработают ни на одном " +
                        "пресете, текст — работает."
                else ->
                    "❌ Нет связи с Discord даже по TCP. Попробуйте другой пресет."
            }
    }

    suspend fun run(port: Int = ByedpiJniEngine.DEFAULT_SOCKS_PORT): Result =
        withContext(Dispatchers.IO) {
            val socks = InetSocketAddress(SOCKS_HOST, port)

            // 0. Движок вообще жив?
            val reachable = runCatching {
                Socket().use { it.connect(socks, 2000) }
                true
            }.getOrDefault(false)

            if (!reachable) {
                return@withContext Result(
                    socksReachable = false, tcpOk = false,
                    tcpDetail = "SOCKS5 на $SOCKS_HOST:$port недоступен",
                    udpOk = false, udpDetail = "не проверялся",
                )
            }

            // 1. TCP-путь (текст): CONNECT до discord.com:443
            val tcp = runCatching { tcpConnectDiscord(socks) }
            val tcpOk = tcp.getOrNull() == true
            val tcpDetail = when {
                tcp.isSuccess && tcpOk -> "подключение к discord.com:443 установлено"
                tcp.isSuccess -> "discord.com:443 отказал в подключении"
                else -> "ошибка: ${tcp.exceptionOrNull()?.message ?: "неизвестна"}"
            }

            // 2. UDP-путь (голос): UDP ASSOCIATE + DNS к 1.1.1.1
            val udp = runCatching { udpDnsProbe(socks) }
            val udpOk = udp.getOrNull() == true
            val udpDetail = when {
                udp.isSuccess && udpOk -> "UDP-пакеты ходят (DNS через туннель ответил)"
                udp.isSuccess -> "ответ по UDP не пришёл"
                else -> "ошибка: ${udp.exceptionOrNull()?.message ?: "неизвестна"}"
            }

            Result(true, tcpOk, tcpDetail, udpOk, udpDetail)
        }

    /** SOCKS5-приветствие. Бросает исключение при ошибке. */
    private fun handshake(s: Socket) {
        s.soTimeout = TIMEOUT_MS
        s.getOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00)); flush()
        }
        val r = s.getInputStream().readNBytesCompat(2)
        if (!r.contentEquals(byteArrayOf(0x05, 0x00))) {
            error("SOCKS handshake не прошёл")
        }
    }

    /** TCP: CONNECT discord.com:443 (имя домена, ATYP=3). */
    private fun tcpConnectDiscord(socks: InetSocketAddress): Boolean {
        Socket().use { s ->
            s.connect(socks, TIMEOUT_MS)
            handshake(s)
            val host = "discord.com".toByteArray(Charsets.US_ASCII)
            val req = ByteBuffer.allocate(3 + 1 + 1 + host.size + 2)
                .put(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                .put(host.size.toByte()).put(host)
                .putShort(443).array()
            s.getOutputStream().apply { write(req); flush() }
            val r = s.getInputStream().readNBytesCompat(10)
            return r.size >= 2 && r[1] == 0.toByte()
        }
    }

    /** UDP: UDP ASSOCIATE + DNS A-запись discord.com к 1.1.1.1. */
    private fun udpDnsProbe(socks: InetSocketAddress): Boolean {
        Socket().use { ctrl ->
            ctrl.connect(socks, TIMEOUT_MS)
            handshake(ctrl)
            // UDP ASSOCIATE: VER CMD RSV ATYP=1 0.0.0.0:0
            ctrl.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); flush()
            }
            val r = ctrl.getInputStream().readNBytesCompat(10)
            if (r.size < 10 || r[1] != 0.toByte()) error("ASSOCIATE отклонён")
            val relayIp = InetAddress.getByAddress(r.copyOfRange(4, 8))
            val relayPort = ((r[8].toInt() and 0xFF) shl 8) or (r[9].toInt() and 0xFF)

            // Заголовок датаграммы: RSV(2) FRAG(1) ATYP=1 IPv4(4) PORT(2);
            // DNS-запрос A discord.com собираем вручную (без библиотек)
            val q = ByteBuffer.allocate(512)
            q.put(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
            "discord.com".split(".").forEach { label ->
                q.put(label.length.toByte()); q.put(label.toByteArray(Charsets.US_ASCII))
            }
            q.put(0); q.putShort(1); q.putShort(1) // A, IN
            val query = q.array().copyOfRange(0, q.position())

            val pkt = ByteBuffer.allocate(10 + query.size)
                .put(byteArrayOf(0, 0, 0, 0x01))
                .put(InetAddress.getByName(DNS_IP).address)
                .putShort(DNS_PORT.toShort())
                .put(query).array()

            DatagramSocket().use { udp ->
                udp.soTimeout = TIMEOUT_MS
                // Стандарт SOCKS5: UDP-сокет — с тем же локальным портом,
                // что и управляющее TCP-соединение (так делает движок туннеля).
                udp.bind(InetSocketAddress(ctrl.localPort))
                udp.send(java.net.DatagramPacket(pkt, pkt.size, relayIp, relayPort))
                val buf = ByteArray(2048)
                val resp = java.net.DatagramPacket(buf, buf.size)
                udp.receive(resp)
                val data = resp.data
                // 10 байт SOCKS-заголовка + DNS-ответ: проверяем бит QR (это ответ)
                return resp.length > 22 && (data[12].toInt() and 0x80) != 0
            }
        }
    }

    /** readNBytes для minSdk 24 (в java.io.InputStream он с API 33 — свой). */
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n); var off = 0
        while (off < n) {
            val k = read(out, off, n - off)
            if (k < 0) return out.copyOf(off)
            off += k
        }
        return out
    }
}
