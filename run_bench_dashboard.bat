@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic telemetry port...
    java -cp out ThermalBenchDashboardApplet --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting Thermal Bench Telemetry Dashboard ^(listening to Radio Telemetry at %HOST%:%PORT%^)...
    java -cp out ThermalBenchDashboardApplet %HOST% %PORT%
)
