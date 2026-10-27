@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic sensors port...
    java -cp out SensorInputApplet --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting Sensor Input Applet ^(connecting to Terminal Bus at %HOST%:%PORT%^)...
    java -cp out SensorInputApplet %HOST% %PORT%
)
