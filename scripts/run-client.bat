@echo off
cd /d "%~dp0\.."
set SERVER=%1
set PLAYER=%2
if "%SERVER%"=="" set SERVER=192.168.56.100:5050
if "%PLAYER%"=="" set PLAYER=Jogador
java client\Client.java --name=%PLAYER% --servers=%SERVER%
