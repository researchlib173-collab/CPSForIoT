@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic telemetry port...
    java -cp out RoomDashboardApplet --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting Smart Room Dashboard Applet ^(listening to Radio Telemetry at %HOST%:%PORT%^)...
    java -cp out RoomDashboardApplet %HOST% %PORT%
)
