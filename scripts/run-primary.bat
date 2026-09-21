@echo off
cd /d "%~dp0\.."
set BACKUP_IP=%1
if "%BACKUP_IP%"=="" set BACKUP_IP=192.168.56.12
java server\Server.java --role=primary --port=5050 --peer=%BACKUP_IP%:5051 --words=words.txt
