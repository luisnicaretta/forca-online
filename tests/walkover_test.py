#!/usr/bin/env python3
"""Testa W.O., timeout de jogada, saida voluntaria, revanche sem adversario e concorrencia."""
from __future__ import annotations

import random
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from integration_test import ROOT, TestClient, dec, enc, start_server, stop, wait_port  # noqa: E402

PORT = 15060


def read_finished(client: TestClient, limit: float) -> list[str]:
    """Le STATEs ate achar FINISHED (ou estourar o limite)."""
    end = time.time() + limit
    while time.time() < end:
        st = client.state()
        if st[9] == "FINISHED":
            return st
    raise AssertionError(f"{client.name}: partida nao terminou em {limit}s")


def pair(name_a: str, name_b: str) -> tuple[TestClient, TestClient]:
    a = TestClient(name_a, PORT)
    b = TestClient(name_b, PORT)
    a.state(), b.state()
    return a, b


def test_disconnect_walkover():
    a, b = pair("A1", "B1")
    b.close()  # some de vez
    warn = a.state()
    assert "perdeu a conexao" in dec(warn[11]), f"faltou aviso de queda: {dec(warn[11])!r} {warn[9]}"
    t0 = time.time()
    fin = read_finished(a, 8)
    assert fin[10] == a.token, "quem ficou deveria vencer"
    assert "W.O." in dec(fin[11])
    assert time.time() - t0 < 6, "W.O. demorou mais que o esperado"

    # Revanche sem adversario: volta para a fila e pareia com um novo jogador.
    a.send("REPLAY")
    waiting = a.read_until("WAITING").split("|")
    assert len(waiting) > 2 and "Procurando novo adversario" in dec(waiting[2])
    c = TestClient("C1", PORT)
    sa, sc = a.state(), c.state()
    assert sa[1] == sc[1] and sa[9] == "PLAYING" and int(sa[4]) == 0
    a.close(); c.close()


def test_reconnect_within_grace_keeps_game():
    a, b = pair("A2", "B2")
    token_b = b.token
    b.close()
    time.sleep(0.7)  # menos que --grace
    b2 = TestClient("B2", PORT, token_b)
    st = b2.state()
    assert st[9] == "PLAYING", "reconexao dentro do prazo nao deveria dar W.O."
    time.sleep(2.5)  # passa do grace original: nao pode dar W.O.
    a.send(f"GUESS|{enc('Z')}")  # so precisa nao ter finalizado
    a.close(); b2.close()


def test_turn_timeout():
    a, b = pair("A3", "B3")  # A tem a vez e nao joga
    fin = read_finished(b, 9)
    assert fin[10] == b.token and "nao jogou" in dec(fin[11])
    a.close(); b.close()


def test_quit_is_immediate_and_message_kept():
    a, b = pair("A4", "B4")
    t0 = time.time()
    a.send("QUIT")
    fin = read_finished(b, 3)
    assert time.time() - t0 < 1.5, "QUIT deveria dar W.O. imediato"
    assert fin[10] == b.token
    assert "saiu da partida" in dec(fin[11])
    time.sleep(0.5)
    b.sock.settimeout(0.8)
    try:  # nenhuma mensagem posterior pode sobrescrever o resultado
        extra = b.state()
        assert "perdeu a conexao" not in dec(extra[11])
    except Exception:
        pass
    b.close()


def test_first_player_leaves_lobby_before_match():
    a = TestClient("A5", PORT)
    a.close()
    time.sleep(0.3)
    b = TestClient("B5", PORT)
    c = TestClient("C5", PORT)
    sb, sc = b.state(), c.state()
    assert sb[1] == sc[1], "B e C deveriam formar a partida (A5 saiu da fila)"
    b.close(); c.close()


def test_concurrent_load(server_log: Path):
    """Muitos pares jogando ao mesmo tempo + reconexoes: nao pode haver excecao no servidor."""
    errors: list[str] = []
    pairs = []
    for i in range(12):  # conexao em sequencia => pareamento deterministico (1-2, 3-4, ...)
        a = TestClient(f"Sa{i}", PORT)
        time.sleep(0.05)  # cada conexao tem sua thread: espaca para a fila manter a ordem
        b = TestClient(f"Sb{i}", PORT)
        time.sleep(0.05)
        pairs.append((i, a, b))

    def play(idx: int, a: TestClient, b: TestClient):
        try:
            st = a.state(); b.state()
            letters = list("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            random.shuffle(letters)
            for n, letter in enumerate(letters):
                if st[9] == "FINISHED":
                    break
                mover, other = (a, b) if st[7] == a.token else (b, a)
                mover.send(f"GUESS|{enc(letter)}")
                st = mover.state(); other.state()
                if idx % 3 == 0 and n == 2 and st[9] == "PLAYING":  # reconecta no meio do jogo
                    tok, name = other.token, other.name
                    stayer = mover
                    other.close()
                    time.sleep(0.3)  # o servidor precisa registrar a queda antes da volta
                    fresh = TestClient(name, PORT, tok)
                    fresh.state()
                    stayer.state()          # aviso de queda
                    st = stayer.state()     # aviso de reconexao (estado mais recente)
                    if other is a: a = fresh
                    else: b = fresh
            assert st[9] == "FINISHED", "partida nao terminou"
            a.close(); b.close()
        except Exception as exc:  # noqa: BLE001
            errors.append(f"par {idx}: {exc!r}")

    threads = [threading.Thread(target=play, args=p) for p in pairs]
    for t in threads: t.start()
    for t in threads: t.join(60)
    assert not errors, errors
    log = server_log.read_text(errors="replace")
    for bad in ("ConcurrentModification", "NullPointer", "Exception in thread", "Erro no watchdog"):
        assert bad not in log, f"servidor registrou {bad}"


def test_failover_walkover():
    """Apos o failover, quem nao volta ao reserva perde por W.O. (e o reserva NAO cobra W.O. antes de assumir)."""
    import subprocess
    common = ["--words=tests/test_words.txt", "--grace=2", "--turn-timeout=0"]
    backup = start_server("--role=backup", "--port=15072", "--replication-port=15071", *common)
    primary = start_server("--role=primary", "--port=15070", "--peer=127.0.0.1:15071", *common)
    try:
        wait_port(15071); wait_port(15070); wait_port(15072)
        a = TestClient("FA", 15070); b = TestClient("FB", 15070)
        a.state(); b.state()
        time.sleep(3.5)  # reserva em standby por mais que o grace: nao pode cobrar W.O.
        ta, tb = a.token, b.token
        stop(primary)
        a.close(); b.close()
        ra = TestClient("FA", 15072, ta)  # so o A volta
        st = ra.state()
        assert st[9] == "PLAYING", "reserva deu W.O. antes de assumir"
        fin = read_finished(ra, 8)
        assert fin[10] == ra.token and "W.O." in dec(fin[11])
        ra.close()
    finally:
        if primary.poll() is None: stop(primary)
        stop(backup)


def main() -> int:
    tmp = tempfile.NamedTemporaryFile("w+", suffix=".log", delete=False)
    import subprocess
    server = subprocess.Popen(
        ["java", "server/Server.java", "--role=primary", f"--port={PORT}", "--words=tests/test_words.txt",
         "--grace=2", "--turn-timeout=4"],
        cwd=ROOT, stdout=tmp, stderr=subprocess.STDOUT, text=True)
    try:
        wait_port(PORT)
        for test in (test_disconnect_walkover, test_reconnect_within_grace_keeps_game, test_turn_timeout,
                     test_quit_is_immediate_and_message_kept, test_first_player_leaves_lobby_before_match):
            test()
            print("ok:", test.__name__)
        test_failover_walkover()
        print("ok: test_failover_walkover")
        test_concurrent_load(Path(tmp.name))
        print("ok: test_concurrent_load")
        print("OK: W.O. por queda, inatividade e saida, revanche sem adversario e concorrencia validados.")
        return 0
    except Exception as exc:  # noqa: BLE001
        import traceback; traceback.print_exc()
        print(f"FALHA: {exc}", file=sys.stderr)
        return 1
    finally:
        stop(server)


if __name__ == "__main__":
    raise SystemExit(main())
