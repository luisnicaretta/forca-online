#!/usr/bin/env python3
"""Teste real: lobby, turnos, sincronizacao, replicacao e failover."""
from __future__ import annotations

import base64
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def enc(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def dec(value: str) -> str:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode()


class TestClient:
    def __init__(self, name: str, port: int, token: str = "-"):
        self.name = name
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=4)
        self.sock.settimeout(6)
        self.reader = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self.send(f"HELLO|{enc(name)}|{token}")
        welcome = self.read_until("WELCOME")
        self.token = welcome.split("|")[1]

    def send(self, message: str):
        self.sock.sendall((message + "\n").encode())

    def read_until(self, kind: str) -> str:
        while True:
            line = self.reader.readline()
            if not line:
                raise RuntimeError(f"{self.name}: conexao fechada esperando {kind}")
            line = line.strip()
            if line.split("|", 1)[0] == kind:
                return line

    def state(self) -> list[str]:
        return self.read_until("STATE").split("|")

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.reader.close()
            self.sock.close()
        except OSError:
            pass


def start_server(*args: str) -> subprocess.Popen:
    return subprocess.Popen(
        ["java", "server/Server.java", *args],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )


def wait_port(port: int, timeout: float = 20):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.2):
                return
        except OSError:
            time.sleep(.15)
    raise RuntimeError(f"porta {port} nao abriu")


def stop(process: subprocess.Popen):
    process.terminate()
    try:
        process.wait(timeout=4)
    except subprocess.TimeoutExpired:
        process.kill()


def assert_state_equal(a: list[str], b: list[str]):
    assert a[1:13] == b[1:13], f"os jogadores receberam estados diferentes:\nA={a}\nB={b}"


def main():
    backup = start_server("--role=backup", "--port=15052", "--replication-port=15051", "--words=tests/test_words.txt")
    primary = start_server("--role=primary", "--port=15050", "--peer=127.0.0.1:15051", "--words=tests/test_words.txt")
    clients: list[TestClient] = []
    try:
        wait_port(15051)
        wait_port(15050)
        wait_port(15052)

        p1 = TestClient("Luis", 15050)
        clients.append(p1)
        p2 = TestClient("Joao", 15050)
        clients.append(p2)
        initial1, initial2 = p1.state(), p2.state()
        assert_state_equal(initial1, initial2)
        assert initial1[7] == p1.token, "primeiro turno deveria ser do jogador 1"
        assert dec(initial1[14]) == "TESTE", "categoria nao chegou aos clientes"

        # Mais dois clientes formam outra partida, comprovando multiplas salas.
        p3 = TestClient("Ana", 15050)
        clients.append(p3)
        p4 = TestClient("Bia", 15050)
        clients.append(p4)
        second_match_1, second_match_2 = p3.state(), p4.state()
        assert_state_equal(second_match_1, second_match_2)
        assert second_match_1[1] != initial1[1], "quatro jogadores nao foram separados em duas partidas"
        p3.close()
        p4.close()
        clients.remove(p3)
        clients.remove(p4)

        # A tentativa de palavra inteira errada consome uma chance e alterna o turno.
        p1.send(f"WORD|{enc('RESPOSTA ERRADA')}")
        state1, state2 = p1.state(), p2.state()
        assert_state_equal(state1, state2)
        assert int(state1[4]) == 1, "erro do jogador 1 nao foi registrado"
        assert state1[7] == p2.token, "turno nao alternou"

        p2.send(f"WORD|{enc('OUTRA RESPOSTA ERRADA')}")
        state1, state2 = p1.state(), p2.state()
        assert_state_equal(state1, state2)
        assert int(state1[6]) == 1, "erro do jogador 2 nao foi registrado"
        assert state1[7] == p1.token

        # Aguarda a replica mais nova e derruba o principal.
        time.sleep(1.2)
        old_p1_token, old_p2_token = p1.token, p2.token
        stop(primary)
        p1.close()
        p2.close()
        clients.clear()

        r1 = TestClient("Luis", 15052, old_p1_token)
        clients.append(r1)
        recovered1 = r1.state()
        r2 = TestClient("Joao", 15052, old_p2_token)
        clients.append(r2)
        recovered2 = r2.state()
        recovered1_after_p2 = r1.state()
        assert int(recovered1[4]) == 1 and int(recovered1[6]) == 1
        assert int(recovered2[4]) == 1 and int(recovered2[6]) == 1
        assert_state_equal(recovered1_after_p2, recovered2)

        r1.send(f"WORD|{enc('AINDA ERRADA')}")
        after1, after2 = r1.state(), r2.state()
        assert_state_equal(after1, after2)
        assert int(after1[4]) == 2, "partida nao continuou apos failover"

        # O jogador 2 acerta a palavra completa.
        r2.send(f"WORD|{enc('TESTE')}")
        finished1, finished2 = r1.state(), r2.state()
        assert_state_equal(finished1, finished2)
        assert finished1[9] == "FINISHED" and finished1[10] == r2.token

        # Os dois pedem revanche; uma nova GameSession deve nascer zerada.
        old_match = finished1[1]
        r1.send("REPLAY")
        waiting_replay1, waiting_replay2 = r1.state(), r2.state()
        assert_state_equal(waiting_replay1, waiting_replay2)
        r2.send("REPLAY")
        replay1, replay2 = r1.state(), r2.state()
        assert_state_equal(replay1, replay2)
        assert replay1[1] != old_match, "revanche reutilizou a partida encerrada"
        assert replay1[9] == "PLAYING" and int(replay1[4]) == 0 and int(replay1[6]) == 0
        assert dec(replay1[14]) == "TESTE"
        print("OK: lobby, palavra inteira, revanche, semaforo, replicacao e failover validados.")
        return 0
    except Exception as exc:
        print(f"FALHA: {exc}", file=sys.stderr)
        return 1
    finally:
        for client in clients:
            client.close()
        if primary.poll() is None:
            stop(primary)
        stop(backup)


if __name__ == "__main__":
    raise SystemExit(main())
