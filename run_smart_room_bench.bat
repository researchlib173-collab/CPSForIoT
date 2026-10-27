@echo off
set HOST=%1
set S_PORT=%2
set T_PORT=%3

echo ====================================================================
echo  Smart Room Bench - Realistic Architectural Cyber-Physical Simulator
echo ====================================================================

if "%S_PORT%"=="" (
    echo [PROMPT] No fixed ports passed. The application will ask you for
    echo          the dynamic port numbers hosted by the workspace.
    echo.
    echo Starting SmartRoomBenchApp in interactive port prompt mode...
    java -cp out SmartRoomBenchApp --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    if "%T_PORT%"=="" set T_PORT=9090
    echo Connecting to Sensors at %HOST%:%S_PORT%, Telemetry at %HOST%:%T_PORT%...
    echo Starting SmartRoomBenchApp...
    java -cp out SmartRoomBenchApp %HOST% %S_PORT% %T_PORT%
)
