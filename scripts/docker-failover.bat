@echo off
cd /d "%~dp0\.."
echo Parando o servidor primario. O HAProxy deve assumir o backup...
docker compose stop primary
docker compose ps
pause
