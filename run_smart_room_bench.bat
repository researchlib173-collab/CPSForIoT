@echo off
set HOST=%1
set S_PORT=%2
set T_PORT=%3
if "%HOST%"=="" set HOST=localhost
if "%S_PORT%"=="" set S_PORT=9091
if "%T_PORT%"=="" set T_PORT=9090

echo ====================================================================
echo  Smart Room Bench - Realistic Architectural Cyber-Physical Simulator
echo ====================================================================
echo  Connecting to Physical Sensors Bus at : %HOST%:%S_PORT%
echo  Connecting to MCU Telemetry Stream at  : %HOST%:%T_PORT%
echo.
echo Starting SmartRoomBenchApp...
java -cp out SmartRoomBenchApp %HOST% %S_PORT% %T_PORT%
