@echo off
set HOST=%1
set S_PORT=%2
set T_PORT=%3

echo ==============================================================
echo   STARTING NOBITA CPS VIDEO & SIMULATION SYNCHRONIZER
echo   Scenario 1: Departure via Bamboo Copter (Timeline 1.mov, 3.0s cut)
echo   Scenario 2: Sleeping & Light Dimming (Nobita sleeping.mov, 4.0s cut)
echo   Scenario 3: Entering Room & Lights On (Nobita enters the room.mov, 6.6s cut)
echo   Scenario 4: PIR Motion Detection (PIR motion sensor activated.mp4, 2.3s cut)
echo ==============================================================

if "%S_PORT%"=="" (
    echo [PROMPT] No fixed ports passed. The application will ask you for
    echo          the dynamic port numbers hosted by the workspace.
    echo.
    echo Starting NobitaDepartureSyncApp in interactive port prompt mode...
    java -cp out NobitaDepartureSyncApp --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    if "%T_PORT%"=="" set T_PORT=9090
    echo Connecting to Sensors at %HOST%:%S_PORT%, Telemetry at %HOST%:%T_PORT%...
    java -cp out NobitaDepartureSyncApp %HOST% %S_PORT% %T_PORT%
)
