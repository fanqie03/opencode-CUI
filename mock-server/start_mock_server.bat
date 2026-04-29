@echo off
set HOST=%1
set PORT=%2

if "%HOST%"=="" set HOST=0.0.0.0
if "%PORT%"=="" set PORT=18080

cd /d "%~dp0"
python -m uvicorn mock_server.app:app --host %HOST% --port %PORT%
