#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER="${1:-127.0.0.1:5050}"
NAME="${2:-Jogador}"
exec java client/Client.java --name="$NAME" --servers="$SERVER"
