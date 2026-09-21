@echo off
cd /d "%~dp0\.."
java server\Server.java --role=backup --port=5050 --replication-port=5051 --words=words.txt
