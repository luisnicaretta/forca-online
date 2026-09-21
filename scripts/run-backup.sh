#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec java server/Server.java --role=backup --port=5050 --replication-port=5051 --words=words.txt
