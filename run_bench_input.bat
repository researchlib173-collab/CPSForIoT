@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic sensors port...
    java -cp out ThermalBenchInputApplet --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting Thermal Bench Input Stimulator ^(connecting to Sensors Bus at %HOST%:%PORT%^)...
    java -cp out ThermalBenchInputApplet %HOST% %PORT%
)
