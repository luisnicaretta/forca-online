#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Parando o servidor primario. O HAProxy deve assumir o backup..."
docker compose stop primary
docker compose ps
