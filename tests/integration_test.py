#!/usr/bin/env python3
import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build-test"


def b64(text: str) -> str:
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def dec(text: str) -> str:
    text += "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text).decode()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_port(port, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.3):
                return
        except OSError:
            time.sleep(.1)
    raise RuntimeError(f"porta {port} nao abriu")


def wait_http(url, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=.5) as r:
                if r.status == 200:
                    return
        except Exception:
            time.sleep(.1)
    raise RuntimeError(f"HTTP nao respondeu: {url}")


class Client:
    def __init__(self, name, port, token="-"):
        self.name = name
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.sock.settimeout(5)
        self.file = self.sock.makefile("rwb", buffering=0)
        self.send(f"HELLO|{b64(name)}|{token}")
        welcome = self.read_until("WELCOME")
        self.token = welcome.split("|")[1]

    def send(self, line):
        self.file.write((line + "\n").encode())

    def read_line(self):
        line = self.file.readline()
        if not line:
            raise EOFError("conexao fechada")
        return line.decode().rstrip("\r\n")

    def read_until(self, kind, timeout=5):
        self.sock.settimeout(timeout)
        while True:
            line = self.read_line()
            if line == kind or line.startswith(kind + "|"):
                return line

    def state(self):
        line = self.read_until("STATE")
        return self.parse_state(line)

    def state_after(self, min_version):
        while True:
            state = self.state()
            if state["version"] > min_version:
                return state

    @staticmethod
    def parse_state(line):
        f = line.split("|")
        return {
            "raw": line,
            "id": f[1],
            "masked": dec(f[2]),
            "p1": dec(f[3]), "e1": int(f[4]),
            "p2": dec(f[5]), "e2": int(f[6]),
            "turn": f[7], "used": dec(f[8]), "status": f[9],
            "winner": f[10], "message": dec(f[11]), "version": int(f[12]),
            "category": dec(f[14]) if len(f) >= 15 else "GERAL",
            "timeout": int(f[15]) if len(f) >= 16 else None,
            "remaining": int(f[16]) if len(f) >= 17 else None,
            "p1token": f[17] if len(f) >= 19 else None,
            "p2token": f[18] if len(f) >= 19 else None,
            "replay": dec(f[19]) if len(f) >= 20 else "",
            "actor": f[20] if len(f) >= 21 else "-",
        }

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def start(logs, *cmd, cwd=ROOT, env=None):
    log = tempfile.NamedTemporaryFile(prefix="forca-", suffix=".log", delete=False)
    logs.append(log.name)
    return subprocess.Popen(cmd, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, env=env)


def terminate(proc):
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(4)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(2)


def main():
    if not shutil.which("java") or not shutil.which("javac"):
        print("ERRO: Java 17+ (java e javac) e necessario")
        return 2

    shutil.rmtree(BUILD, ignore_errors=True)
    BUILD.mkdir()
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(BUILD), "server/Server.java"], cwd=ROOT, check=True)

    db_port, backup_port, primary_port, repl_port, restored_port = [free_port() for _ in range(5)]
    data_dir = Path(tempfile.mkdtemp(prefix="forca-db-"))
    env = os.environ.copy()
    env.update({"FORCA_DB_PATH": str(data_dir / "forca.db"), "FORCA_DB_PORT": str(db_port), "FORCA_DB_HOST": "127.0.0.1"})
    db_url = f"http://127.0.0.1:{db_port}"
    logs = []
    db = backup = primary = restored = None
    clients = []

    try:
        db = start(logs, sys.executable, "database/db_service.py", env=env)
        wait_http(db_url + "/health")

        backup = start(logs, "java", "-cp", str(BUILD), "Server",
                       "--role=backup", f"--port={backup_port}", f"--replication-port={repl_port}",
                       "--words=server/words.txt", f"--db-url={db_url}", "--grace=5", "--turn-timeout=0",
                       "--promotion-timeout-ms=1200")
        wait_port(backup_port)
        wait_port(repl_port)

        primary = start(logs, "java", "-cp", str(BUILD), "Server",
                        "--role=primary", f"--port={primary_port}", f"--peer=127.0.0.1:{repl_port}",
                        "--words=server/words.txt", f"--db-url={db_url}", "--grace=5", "--turn-timeout=0",
                       "--promotion-timeout-ms=1200")
        wait_port(primary_port)

        # Quatro jogadores => duas partidas simultaneas.
        p1 = Client("Ana", primary_port); clients.append(p1)
        p2 = Client("Bruno", primary_port); clients.append(p2)
        s1a = p1.state(); s1b = p2.state()
        assert s1a["id"] == s1b["id"], "primeiro par nao caiu na mesma partida"

        p3 = Client("Carla", primary_port); clients.append(p3)
        p4 = Client("Diego", primary_port); clients.append(p4)
        s2a = p3.state(); s2b = p4.state()
        assert s2a["id"] == s2b["id"] and s2a["id"] != s1a["id"], "fila nao criou duas partidas"

        # Fora do turno deve ser recusado.
        current = p1 if s1a["turn"] == p1.token else p2
        waiting = p2 if current is p1 else p1
        waiting.send("GUESS|" + b64("A"))
        err = waiting.read_until("ERROR")
        assert "Aguarde sua vez" in dec(err.split("|", 1)[1]), "servidor aceitou jogada fora do turno"

        # Erro de palavra completa soma somente no boneco de quem jogou e aparece para ambos.
        before_e1, before_e2 = s1a["e1"], s1a["e2"]
        current.send("WORD|" + b64("ZZZZZZZZZZZZZZ"))
        after_a = p1.state(); after_b = p2.state()
        assert after_a["raw"] == after_b["raw"], "clientes receberam estados diferentes"
        if current.token == p1.token:
            assert after_a["e1"] == before_e1 + 1 and after_a["e2"] == before_e2
        else:
            assert after_a["e2"] == before_e2 + 1 and after_a["e1"] == before_e1

        # Banco recebeu as duas partidas.
        end = time.time() + 5
        stats = {}
        while time.time() < end:
            with urllib.request.urlopen(db_url + "/stats", timeout=1) as r:
                stats = json.load(r)
            if stats.get("active", 0) >= 2 and stats.get("snapshots", 0) >= 3:
                break
            time.sleep(.15)
        assert stats.get("active", 0) >= 2, f"banco nao persistiu partidas: {stats}"

        # Aguarda a replicacao e derruba o primario.
        time.sleep(.5)
        snapshot_before_fail = after_a
        token1, token2 = p1.token, p2.token
        p1.close(); p2.close()
        terminate(primary); primary = None
        time.sleep(1.35)  # aguarda promocao segura do reserva apos perda do heartbeat

        r1 = Client("Ana", backup_port, token1); clients.append(r1)
        r2 = Client("Bruno", backup_port, token2); clients.append(r2)
        rs1 = r1.state(); rs2 = r2.state()
        assert rs1["id"] == snapshot_before_fail["id"] == rs2["id"], "backup nao recuperou a mesma partida"
        assert (rs1["e1"], rs1["e2"]) == (snapshot_before_fail["e1"], snapshot_before_fail["e2"]), "erros se perderam no failover"
        assert rs1["version"] >= snapshot_before_fail["version"], "versao regrediu no failover"

        # Continua jogando no reserva.
        active_client = r1 if rs1["turn"] == r1.token else r2
        active_client.send("WORD|" + b64("XXXXXXXXXXXX"))
        cont1 = r1.state_after(rs1["version"]); cont2 = r2.state_after(rs1["version"])
        assert cont1["raw"] == cont2["raw"] and cont1["version"] > rs1["version"], "partida nao continuou no backup"

        # Persistencia real: derruba o ultimo servidor e sobe um novo a partir do SQLite.
        r1.close(); r2.close()
        time.sleep(.4)
        terminate(backup); backup = None
        restored = start(logs, "java", "-cp", str(BUILD), "Server",
                         "--role=primary", f"--port={restored_port}", "--words=server/words.txt",
                         f"--db-url={db_url}", "--grace=5", "--turn-timeout=0")
        wait_port(restored_port)
        d1 = Client("Ana", restored_port, token1); clients.append(d1)
        restored_state = d1.state()
        assert restored_state["id"] == cont1["id"], "reinicio nao restaurou a partida do banco"
        assert restored_state["version"] >= cont1["version"], "estado persistido esta atrasado"

        print("OK: sockets, lobby, 2 jogadores, semaforo/turno, dois bonecos, banco, replicacao e failover validados.")
        print(f"DB stats: {stats}")
        return 0
    finally:
        for c in clients:
            c.close()
        for proc in (primary, backup, restored, db):
            terminate(proc)
        shutil.rmtree(data_dir, ignore_errors=True)
        if os.environ.get("FORCA_KEEP_TEST_LOGS") != "1":
            for name in logs:
                try: os.unlink(name)
                except OSError: pass


if __name__ == "__main__":
    raise SystemExit(main())
