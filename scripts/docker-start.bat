@echo off
cd /d "%~dp0\.."
docker compose up -d --build
if errorlevel 1 pause
