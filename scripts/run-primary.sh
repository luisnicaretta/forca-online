#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BACKUP_IP="${1:-127.0.0.1}"
exec java server/Server.java --role=primary --port=5050 --peer="${BACKUP_IP}:5051" --words=words.txt
