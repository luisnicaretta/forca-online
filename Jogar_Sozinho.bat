@echo off
cd /d "%~dp0"
java client\GameLauncher.java
if errorlevel 1 pause
