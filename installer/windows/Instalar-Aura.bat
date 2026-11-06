@echo off
rem Instalador de Aura para Fire TV: doble clic y sigue las instrucciones.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Instalar-Aura.ps1" %*
