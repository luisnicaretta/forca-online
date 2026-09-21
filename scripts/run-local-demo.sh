#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

java server/Server.java --role=backup --port=5052 --replication-port=5051 &
BACKUP_PID=$!
java server/Server.java --role=primary --port=5050 --peer=127.0.0.1:5051 &
PRIMARY_PID=$!

cleanup() {
  kill "$PRIMARY_PID" "$BACKUP_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Servidores iniciados. Abra outros dois terminais e execute:"
echo "  java client/Client.java --name=Luis --servers=127.0.0.1:5050,127.0.0.1:5052"
echo "  java client/Client.java --name=Joao --servers=127.0.0.1:5050,127.0.0.1:5052"
wait
