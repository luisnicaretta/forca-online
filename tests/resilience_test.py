#!/usr/bin/env python3
import base64
import os
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
            time.sleep(.08)
    raise RuntimeError(f"porta {port} nao abriu")


def start(logs, *cmd):
    log = tempfile.NamedTemporaryFile(prefix="forca-resilience-", suffix=".log", delete=False)
    logs.append(log.name)
    return subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)


def terminate(proc):
    if proc and proc.poll() is None:
        proc.terminate()
        try: proc.wait(3)
        except subprocess.TimeoutExpired:
            proc.kill(); proc.wait(2)


class Client:
    def __init__(self, name, port, token="-", timeout=6):
        self.name = name
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.sock.settimeout(timeout)
        self.file = self.sock.makefile("rwb", buffering=0)
        self.send(f"HELLO|{enc(name)}|{token}")
        self.token = self.until("WELCOME").split("|")[1]

    def send(self, line):
        self.file.write((line + "\n").encode())

    def until(self, kind):
        while True:
            line = self.file.readline()
            if not line: raise EOFError("conexao fechada")
            value = line.decode().rstrip("\r\n")
            if value == kind or value.startswith(kind + "|"):
                return value

    def state(self):
        f = self.until("STATE").split("|")
        return {
            "raw": "|".join(f), "id": f[1], "p1": dec(f[3]), "p2": dec(f[5]),
            "e1": int(f[4]), "e2": int(f[6]), "turn": f[7], "status": f[9],
            "winner": f[10], "message": dec(f[11]), "version": int(f[12]),
            "timeout": int(f[15]), "remaining": int(f[16]),
            "p1token": f[17], "p2token": f[18], "replay": dec(f[19]), "actor": f[20],
        }

    def state_after(self, min_version):
        while True:
            state = self.state()
            if state["version"] > min_version or state["status"] == "FINISHED":
                return state

    def close(self):
        try: self.file.close()
        except Exception: pass
        try: self.sock.close()
        except Exception: pass


def standby_rejects_new_client(backup_port):
    with socket.create_connection(("127.0.0.1", backup_port), timeout=2) as sock:
        sock.settimeout(3)
        f = sock.makefile("rwb", buffering=0)
        f.write((f"HELLO|{enc('Intruso')}|-\n").encode())
        line = f.readline().decode().rstrip("\r\n")
        assert line.startswith("ERROR|"), f"backup aceitou cliente enquanto primario vivo: {line}"
        assert "reserva em espera" in dec(line.split("|", 1)[1]).lower()


def main():
    if not shutil.which("java") or not shutil.which("javac"):
        print("ERRO: Java 17+ necessario")
        return 2
    shutil.rmtree(BUILD, ignore_errors=True)
    BUILD.mkdir()
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(BUILD), "server/Server.java"], cwd=ROOT, check=True)

    primary_port, backup_port, repl_port = free_port(), free_port(), free_port()
    logs=[]; primary=backup=None; clients=[]
    common=["--words=server/words.txt", "--grace=5", "--turn-timeout=4", "--promotion-timeout-ms=1200"]
    try:
        backup=start(logs,"java","-cp",str(BUILD),"Server","--role=backup",f"--port={backup_port}",f"--replication-port={repl_port}",*common)
        wait_port(backup_port); wait_port(repl_port)
        primary=start(logs,"java","-cp",str(BUILD),"Server","--role=primary",f"--port={primary_port}",f"--peer=127.0.0.1:{repl_port}",*common)
        wait_port(primary_port)
        time.sleep(1.25)  # garante pelo menos um heartbeat

        # 1) standby nao pode formar partidas enquanto o primario vive.
        standby_rejects_new_client(backup_port)

        # 2) nomes iguais devem ser distinguidos por token no STATE.
        a=Client("MesmoNome",primary_port); b=Client("MesmoNome",primary_port); clients += [a,b]
        sa=a.state(); sb=b.state()
        assert sa["p1token"] != sa["p2token"], "STATE nao distingue jogadores por token"
        assert {sa["p1token"],sa["p2token"]} == {a.token,b.token}, "tokens dos jogadores incorretos no STATE"

        # Prepara uma segunda partida encerrada com um pedido de revanche pendente.
        c=Client("ReplayA",primary_port); d=Client("ReplayB",primary_port); clients += [c,d]
        rc=c.state(); rd=d.state()
        state=rc
        for i in range(12):
            if state["status"] == "FINISHED": break
            actor = c if state["turn"] == c.token else d
            actor.send("WORD|" + enc("PALAVRA_QUE_NAO_EXISTE_123"))
            state = c.state_after(state["version"])
            dstate = d.state_after(state["version"] - 1)
            assert state["raw"] == dstate["raw"]
        assert state["status"] == "FINISHED", "nao foi possivel encerrar a partida de revanche"
        replay_winner = c if state["winner"] == c.token else d
        replay_loser = d if replay_winner is c else c
        replay_winner.send("REPLAY")
        pending_w = replay_winner.state_after(state["version"])
        pending_l = replay_loser.state_after(state["version"])
        assert replay_winner.token in pending_w["replay"].split(","), "replayReady nao entrou no snapshot"
        replay_old_id = pending_w["id"]

        # 3) failover nao pode reiniciar o cronometro e deve preservar replayReady.
        initial_remaining=sa["remaining"]
        time.sleep(.7)
        a.close(); b.close(); c.close(); d.close()
        terminate(primary); primary=None
        time.sleep(1.35)
        ar=Client("MesmoNome",backup_port,a.token); br=Client("MesmoNome",backup_port,b.token); clients += [ar,br]
        cr=Client("ReplayA",backup_port,c.token); dr=Client("ReplayB",backup_port,d.token); clients += [cr,dr]
        fa=ar.state(); fb=br.state()
        assert fa["id"] == sa["id"] == fb["id"], "partida mudou no failover"
        assert fa["remaining"] < initial_remaining - 1500, (
            f"cronometro reiniciou no failover: inicial={initial_remaining}, backup={fa['remaining']}")

        cstate=cr.state(); dstate=dr.state()
        assert cstate["id"] == replay_old_id == dstate["id"], "partida finalizada nao foi recuperada no backup"
        assert replay_winner.token in cstate["replay"].split(","), "replayReady se perdeu no failover"
        replay_loser_r = dr if replay_loser is d else cr
        replay_winner_r = cr if replay_winner is c else dr
        replay_loser_r.send("REPLAY")
        nr1 = replay_winner_r.state()
        while nr1["id"] == replay_old_id:
            nr1 = replay_winner_r.state()
        nr2 = replay_loser_r.state()
        while nr2["id"] == replay_old_id:
            nr2 = replay_loser_r.state()
        assert nr1["id"] == nr2["id"], "revanche apos failover nao iniciou"

        # Deixa a primeira partida terminar pelo timeout para testar deadline autoritativo.
        current = ar if fa["turn"] == ar.token else br
        other = br if current is ar else ar
        time.sleep(max(0.0, fa["remaining"] / 1000.0 + .08))
        current.send("GUESS|" + enc("Z"))
        ended = other.state_after(fa["version"])
        assert ended["status"] == "FINISHED", "jogada depois do deadline foi aceita"
        assert ended["winner"] == other.token and "W.O." in ended["message"], "deadline nao gerou W.O. autoritativo"

        print("OK: standby seguro, tokens para nomes iguais, timer preservado no failover, deadline autoritativo e revanche sincronizada.")
        return 0
    finally:
        for c in clients:
            try: c.close()
            except Exception: pass
        terminate(primary); terminate(backup)
        if os.environ.get("FORCA_KEEP_TEST_LOGS") != "1":
            for f in logs:
                try: os.unlink(f)
                except OSError: pass

if __name__ == "__main__":
    raise SystemExit(main())
