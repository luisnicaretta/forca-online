#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PRIMARY_IP="${1:-127.0.0.1}"
exec java server/Server.java --role=backup --port=5050 --replication-port=5051 --peer="${PRIMARY_IP}:5051" --words=words.txt
