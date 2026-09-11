#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Смоук-тесты движка byedpi — запускаются в CI (.github/workflows/engine-tests.yml).

Для каждого пресета из domain/Strategy.kt проверяем:
  1) движок стартует с аргументами пресета и открывает SOCKS5-порт;
  2) TCP: HTTP через SOCKS5 CONNECT на ЛОКАЛЬНЫЙ http-сервер (127.0.0.1).
     Локальная цель — устойчивость теста: внешние серверы по-разному
     реагируют на десинк-манипуляции (example.com с раннеров GitHub
     молча ронял запросы с OOB-байтом). Любой HTTP-статус = успех,
     важно лишь что данные ходят туда-обратно;
  3) UDP: DNS-запрос через SOCKS5 UDP ASSOCIATE на 1.1.1.1:53 — внешний
     путь (он же путь голосовых каналов Discord); для пресетов с -U
     (UDP выключен) проверка пропускается;
  4) дополнительно для transparent (без десинка): внешний HTTP до
     example.com — контроль что исходящий TCP вообще работает.

Честное ограничение: на раннерах GitHub нет ТСПУ, поэтому проверяется
«пресет не ломает связность движка», а не эффективность обхода DPI.
Эффективность проверяется только на реальном устройстве.

!!! Список пресетов синхронизируется ВРУЧНУЮ с
app/src/main/java/com/dpibreak/domain/Strategy.kt !!!

Запуск: python3 engine_smoke.py <путь к byedpi-cli> [--only id1,id2]
"""
import argparse
import socket
import struct
import subprocess
import sys
import threading
import time
import random
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EXT_TCP_HOST, EXT_TCP_PORT = "example.com", 80
DNS_IP, DNS_PORT = "1.1.1.1", 53


class _QuietHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


def start_local_http():
    """Локальный HTTP-сервер -> (server, port). Чистится в main()."""
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _QuietHandler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, srv.server_address[1]

# (id пресета, аргументы движка, проверять ли UDP)
PRESETS = [
    ("smart", [
        "-U", "-Kh", "-An",
        "-Kt", "-H:youtube.com googlevideo.com ytimg.com ggpht.com gvt1.com youtu.be",
        "-o1", "-r-5+se", "-An",
        "-Kt", "-H:discord.com discord.gg discordapp.com discordapp.net discord.media",
        "-s3:7+sm", "-An",
        "-Kt,h",
    ], False),
    ("universal", ["-o1", "-a1", "-r-5+se"], True),
    ("youtube", ["-f1+s", "-t8", "-a3"], True),
    ("discord", ["-Ku", "-a3", "-An", "-Kt,h", "-d1", "-s0+s", "-d3+s"], True),
    ("discord2", ["-s3:7+sm", "-a1"], True),
    ("discord3", ["-f9+hm", "-o3", "-a2"], True),
    ("telegram", ["-d1", "-s3", "-a2"], True),
    ("transparent", [], True),
]


def log(msg):
    print(msg, flush=True)


def start_engine(engine, port, args):
    """Запускает движок и ждёт открытия порта. -> (proc | None, ошибка | None)."""
    proc = subprocess.Popen(
        [engine, "-p", str(port), "-i", "127.0.0.1"] + args,
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    deadline = time.time() + 6
    while time.time() < deadline:
        if proc.poll() is not None:
            err = proc.stderr.read().decode(errors="replace").strip()
            return None, "движок умер при старте: " + err[:200]
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.4):
                return proc, None
        except OSError:
            time.sleep(0.15)
    proc.terminate()
    return None, "порт SOCKS5 не открылся за 6 с"


def tcp_test(port, target=("127.0.0.1", 0), host_header="localhost",
             external=False):
    """HTTP через SOCKS5 CONNECT. Любой валидный статус = успех.

    local: цель 127.0.0.1:<port> (ATYP=1), внешний не трогаем — десинк
    может законно ломать конкретный внешний сервер.
    external=True: цель example.com (ATYP=3), ожидаем 2xx/3xx —
    используется только для transparent (запрос без манипуляций).
    """
    tport = target[1]
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    try:
        s.sendall(b"\x05\x01\x00")
        if s.recv(2) != b"\x05\x00":
            raise RuntimeError("SOCKS handshake failed")
        try:
            socket.inet_aton(target[0])
            is_ip = True
        except OSError:
            is_ip = False
        if is_ip:
            req = (b"\x05\x01\x00\x01" + socket.inet_aton(target[0])
                   + struct.pack(">H", tport))
        else:
            host = target[0].encode()
            req = (b"\x05\x01\x00\x03" + bytes([len(host)]) + host
                   + struct.pack(">H", tport))
        s.sendall(req)
        r = s.recv(10)
        if len(r) < 2 or r[1] != 0:
            raise RuntimeError(f"CONNECT code {r[1] if len(r) > 1 else '?'}")
        s.sendall(f"GET / HTTP/1.1\r\nHost: {host_header}\r\n"
                  f"Connection: close\r\n\r\n".encode())
        data = b""
        s.settimeout(12)
        while len(data) < 2048:
            chunk = s.recv(2048)
            if not chunk:
                break
            data += chunk
        line = data.split(b"\r\n", 1)[0].decode(errors="replace")
        parts = line.split(" ")
        status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
        if not status:
            raise RuntimeError("нет HTTP-ответа")
        if external and not (200 <= status < 400):
            raise RuntimeError(f"HTTP status {status}")
    finally:
        s.close()


def udp_test(port, target=None, host_header=None):
    """DNS через SOCKS5 UDP ASSOCIATE (путь голосовых каналов)."""
    ctrl = socket.create_connection(("127.0.0.1", port), timeout=10)
    try:
        ctrl.sendall(b"\x05\x01\x00")
        if ctrl.recv(2) != b"\x05\x00":
            raise RuntimeError("SOCKS handshake failed")
        # UDP ASSOCIATE: VER CMD RSV ATYP=1 0.0.0.0:0
        ctrl.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        r = ctrl.recv(10)
        if len(r) < 10 or r[1] != 0:
            raise RuntimeError(f"ASSOCIATE code {r[1] if len(r) > 1 else '?'}")
        relay = (socket.inet_ntoa(r[4:8]), struct.unpack(">H", r[8:10])[0])

        # Заголовок датаграммы: RSV(2) FRAG(1) ATYP=1 IPv4(4) PORT(2) — урок
        # из отладки: ровно 3+1+4+2 байта, без лишнего.
        query = (b"\xab\xcd\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00"
                 b"\x07example\x03com\x00\x00\x01\x00\x01")
        pkt = (b"\x00\x00\x00\x01" + socket.inet_aton(DNS_IP)
               + struct.pack(">H", DNS_PORT) + query)

        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            udp.settimeout(10)
            # Стандартное поведение SOCKS5-клиента: UDP-сокет с тем же
            # локальным портом, что и управляющее TCP-соединение.
            udp.bind(("127.0.0.1", ctrl.getsockname()[1]))
            udp.sendto(pkt, relay)
            data, _ = udp.recvfrom(2048)
        finally:
            udp.close()

        # Ответ: 10 байт заголовка + DNS; проверяем бит QR (это ответ)
        if len(data) < 22 or not (data[12] & 0x80):
            raise RuntimeError(f"подозрительный UDP-ответ ({len(data)} байт)")
    finally:
        ctrl.close()


def with_retries(fn, port, target, host_header="localhost", attempts=2):
    """2 попытки с паузой — гасим сетевые флейки CI. -> '' | текст ошибки."""
    last = "не выполнялось"
    for _ in range(attempts):
        try:
            fn(port, target, host_header)
            return ""
        except Exception as e:
            last = str(e)
        time.sleep(1)
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engine", help="путь к собранному byedpi-cli")
    ap.add_argument("--only", default="",
                    help="запустить только эти пресеты (через запятую)")
    args = ap.parse_args()
    only = set(filter(None, args.only.split(",")))

    local_srv, local_port = start_local_http()
    local_target = ("127.0.0.1", local_port)
    failures = []
    for preset_id, preset_args, check_udp in PRESETS:
        if only and preset_id not in only:
            continue
        port = random.randint(20000, 40000)
        log(f"--- пресет «{preset_id}» (порт {port}) ---")

        proc, err = start_engine(args.engine, port, preset_args)
        if proc is None:
            log(f"  ❌ старт: {err}")
            failures.append(preset_id)
            continue

        try:
            tcp_err = with_retries(tcp_test, port, local_target)
            log(f"  {'✅' if not tcp_err else '❌'} TCP (локальный): "
                f"{tcp_err or 'OK'}")

            # Контроль внешнего TCP без десинка — только transparent
            ext_err = ""
            if preset_id == "transparent":
                ext_err = with_retries(
                    tcp_test, port, (EXT_TCP_HOST, EXT_TCP_PORT),
                    host_header=EXT_TCP_HOST)
                log(f"  {'✅' if not ext_err else '❌'} TCP (внешний): "
                    f"{ext_err or 'OK'}")

            if check_udp:
                udp_err = with_retries(udp_test, port, target=local_target)
                log(f"  {'✅' if not udp_err else '❌'} UDP: {udp_err or 'OK'}")
            else:
                udp_err = ""
                log("  ⏭ UDP: пропущен (в пресете -U)")

            if tcp_err or udp_err or ext_err:
                failures.append(preset_id)
        finally:
            proc.terminate()
            try:
                proc.wait(3)
            except subprocess.TimeoutExpired:
                proc.kill()
            # stderr показываем только при провале пресета: при остановке по
            # SIGTERM движок штатно пишет «accept: Invalid argument» — это шум
            if preset_id in failures:
                err_out = proc.stderr.read(4096).decode(errors="replace").strip()
                if err_out:
                    log(f"  stderr движка: {err_out[:300]}")

    local_srv.shutdown()
    log("")
    if failures:
        log(f"ИТОГ: ❌ провалены пресеты: {', '.join(failures)}")
        sys.exit(1)
    log("ИТОГ: ✅ все пресеты прошли (старт + TCP + UDP)")


if __name__ == "__main__":
    main()
