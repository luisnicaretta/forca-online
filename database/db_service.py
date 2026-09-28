#!/usr/bin/env python3
import base64
import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DB_PATH = Path(os.environ.get("FORCA_DB_PATH", "/data/forca.db"))
HOST = os.environ.get("FORCA_DB_HOST", "0.0.0.0")
PORT = int(os.environ.get("FORCA_DB_PORT", "8080"))


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def connect():
    con = sqlite3.connect(DB_PATH, timeout=5)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA busy_timeout=5000")
    return con


def init_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with connect() as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS matches (
                game_id TEXT PRIMARY KEY,
                version INTEGER NOT NULL,
                status TEXT NOT NULL,
                snapshot TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS snapshot_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_matches_status ON matches(status);
            CREATE INDEX IF NOT EXISTS idx_history_game ON snapshot_history(game_id, version);
            """
        )


class Handler(BaseHTTPRequestHandler):
    server_version = "ForcaDB/1.0"

    def log_message(self, fmt, *args):
        print("[db] " + (fmt % args), flush=True)

    def send_text(self, code, body, content_type="text/plain; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.send_text(200, "OK\n")
            return

        if self.path == "/snapshots":
            with connect() as con:
                rows = con.execute(
                    "SELECT snapshot FROM matches WHERE status = 'PLAYING' "
                    "ORDER BY CAST(game_id AS INTEGER)"
                ).fetchall()
            encoded = [base64.urlsafe_b64encode(row[0].encode("utf-8")).decode("ascii") for row in rows]
            self.send_text(200, "\n".join(encoded) + ("\n" if encoded else ""))
            return

        if self.path == "/matches":
            with connect() as con:
                rows = con.execute(
                    "SELECT game_id, version, status, updated_at FROM matches "
                    "ORDER BY CAST(game_id AS INTEGER) DESC LIMIT 100"
                ).fetchall()
            payload = [
                {"gameId": r[0], "version": r[1], "status": r[2], "updatedAt": r[3]}
                for r in rows
            ]
            self.send_text(200, json.dumps(payload, ensure_ascii=False, indent=2) + "\n", "application/json; charset=utf-8")
            return

        if self.path == "/stats":
            with connect() as con:
                total = con.execute("SELECT COUNT(*) FROM matches").fetchone()[0]
                active = con.execute("SELECT COUNT(*) FROM matches WHERE status='PLAYING'").fetchone()[0]
                finished = con.execute("SELECT COUNT(*) FROM matches WHERE status='FINISHED'").fetchone()[0]
                history = con.execute("SELECT COUNT(*) FROM snapshot_history").fetchone()[0]
            self.send_text(
                200,
                json.dumps({"matches": total, "active": active, "finished": finished, "snapshots": history}, indent=2) + "\n",
                "application/json; charset=utf-8",
            )
            return

        self.send_text(404, "not found\n")

    def do_POST(self):
        if self.path != "/snapshot":
            self.send_text(404, "not found\n")
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1_000_000:
                raise ValueError("invalid body length")
            snapshot = self.rfile.read(length).decode("utf-8")
            fields = snapshot.split("|")
            if not (13 <= len(fields) <= 17):
                raise ValueError("invalid snapshot")
            game_id = self.headers.get("X-Game-Id") or fields[0]
            version = int(self.headers.get("X-Version") or fields[12])
            status = self.headers.get("X-Status") or fields[10]
            if status not in {"PLAYING", "FINISHED"}:
                raise ValueError("invalid status")
            updated = now_iso()
            with connect() as con:
                con.execute(
                    """
                    INSERT INTO matches(game_id, version, status, snapshot, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(game_id) DO UPDATE SET
                        version=excluded.version,
                        status=excluded.status,
                        snapshot=excluded.snapshot,
                        updated_at=excluded.updated_at
                    WHERE excluded.version >= matches.version
                    """,
                    (game_id, version, status, snapshot, updated),
                )
                con.execute(
                    "INSERT INTO snapshot_history(game_id, version, status, created_at) VALUES (?, ?, ?, ?)",
                    (game_id, version, status, updated),
                )
            self.send_text(200, "OK\n")
        except Exception as exc:
            self.send_text(400, f"ERROR: {exc}\n")


def main():
    init_db()
    print(f"Forca DB em http://{HOST}:{PORT} usando {DB_PATH}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
