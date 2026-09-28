#!/usr/bin/env python3
import base64
import shutil
import socket
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build-test"


def enc(text):
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def dec(text):
    text += "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text).decode()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_port(port, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.2):
                return
        except OSError:
            time.sleep(.1)
    raise RuntimeError("servidor nao abriu")


class Client:
    def __init__(self, name, port, token="-"):
        self.name = name
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.sock.settimeout(8)
        self.file = self.sock.makefile("rwb", buffering=0)
        self.send(f"HELLO|{enc(name)}|{token}")
        self.token = self.until("WELCOME").split("|")[1]

    def send(self, msg):
        self.file.write((msg + "\n").encode())

    def until(self, kind):
        while True:
            line = self.file.readline()
            if not line:
                raise EOFError
            value = line.decode().rstrip("\r\n")
            if value == kind or value.startswith(kind + "|"):
                return value

    def state(self):
        f = self.until("STATE").split("|")
        return {
            "status": f[9], "winner": f[10], "message": dec(f[11]), "turn": f[7],
            "timeout": int(f[15]) if len(f) >= 16 else None,
            "remaining": int(f[16]) if len(f) >= 17 else None,
        }

    def close(self):
        try: self.sock.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        try: self.file.close()
        except OSError: pass
        try: self.sock.close()
        except OSError: pass


def main():
    if not shutil.which("java") or not shutil.which("javac"):
        print("ERRO: Java 17+ necessario")
        return 2
    BUILD.mkdir(exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(BUILD), "server/Server.java"], cwd=ROOT, check=True)
    port = free_port()
    log = tempfile.NamedTemporaryFile(prefix="forca-wo-", suffix=".log", delete=False)
    proc = subprocess.Popen([
        "java", "-cp", str(BUILD), "Server", "--role=primary", f"--port={port}",
        "--words=server/words.txt", "--grace=2", "--turn-timeout=2"
    ], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    clients = []
    try:
        wait_port(port)
        a = Client("TimeoutA", port); clients.append(a)
        b = Client("TimeoutB", port); clients.append(b)
        sa = a.state(); sb = b.state()
        assert sa["timeout"] == 2 and sb["timeout"] == 2, "limite do turno nao foi enviado aos dois clientes"
        assert sa["remaining"] == sb["remaining"], "clientes nao receberam o mesmo tempo restante"
        assert 0 < sa["remaining"] <= 2000, "tempo restante inicial invalido"

        # O relogio deve continuar correndo durante desconexao/reconexao, sem resetar o turno.
        current = a if sa["turn"] == a.token else b
        other = b if current is a else a
        current_name, current_token = current.name, current.token
        expected_winner = other.token
        time.sleep(.65)
        current.close()
        disconnected = other.state()
        assert disconnected["remaining"] < sa["remaining"] - 350, "contador nao diminuiu no servidor"
        reconnected = Client(current_name, port, current_token); clients.append(reconnected)
        sr = reconnected.state()
        so = other.state()
        assert sr["remaining"] == so["remaining"], "tempo apos reconexao divergiu entre clientes"
        assert sr["remaining"] < sa["remaining"] - 350, "reconexao reiniciou indevidamente o cronometro"

        end = time.time() + 6
        fa = None
        while time.time() < end:
            fa = other.state()
            if fa["status"] == "FINISHED": break
        assert fa and fa["status"] == "FINISHED", "timeout de turno nao encerrou a partida"
        assert fa["winner"] == expected_winner, "vencedor do timeout incorreto"
        assert "W.O." in fa["message"], "mensagem nao informa W.O."
        reconnected.state()
        other.close(); reconnected.close()

        c = Client("QuitC", port); clients.append(c)
        d = Client("QuitD", port); clients.append(d)
        c.state(); d.state()
        c.send("QUIT")
        fd = d.state()
        assert fd["status"] == "FINISHED" and fd["winner"] == d.token, "QUIT nao deu W.O. imediato"
        assert "W.O." in fd["message"], "QUIT sem mensagem de W.O."
        print("OK: timeout de turno, cronometro sincronizado, reconexao sem reset e abandono voluntario (W.O.) validados.")
        return 0
    finally:
        for c in clients: c.close()
        if proc.poll() is None:
            proc.terminate()
            try: proc.wait(3)
            except subprocess.TimeoutExpired: proc.kill()
        log.close()


if __name__ == "__main__":
    raise SystemExit(main())
