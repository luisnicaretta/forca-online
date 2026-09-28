@echo off
cd /d "%~dp0\.."
echo Failback controlado: parando o backup para congelar o estado e gravar o banco...
docker compose stop backup
if errorlevel 1 goto :erro
echo Iniciando o primario, que restaura as partidas ativas do banco...
docker compose start primary
if errorlevel 1 goto :erro
timeout /t 3 /nobreak >nul
echo Reiniciando o backup como reserva...
docker compose start backup
docker compose ps
pause
exit /b 0
:erro
echo Falha durante o failback.
pause
exit /b 1
