@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic port...
    java -cp out SimulatorApp --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting CPS Hardware Simulator GUI ^(connecting to %HOST%:%PORT%^)...
    java -cp out SimulatorApp %HOST% %PORT%
)

