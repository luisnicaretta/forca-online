#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Failback controlado: parando o backup para congelar o estado e gravar o banco..."
docker compose stop backup
echo "Iniciando o primario, que restaura as partidas ativas do banco..."
docker compose start primary
sleep 3
echo "Reiniciando o backup como reserva..."
docker compose start backup
docker compose ps
