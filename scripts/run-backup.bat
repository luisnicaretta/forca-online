@echo off
cd /d "%~dp0\.."
set PRIMARY_IP=%1
if "%PRIMARY_IP%"=="" set PRIMARY_IP=192.168.56.11
java server\Server.java --role=backup --port=5050 --replication-port=5051 --peer=%PRIMARY_IP%:5051 --words=words.txt
