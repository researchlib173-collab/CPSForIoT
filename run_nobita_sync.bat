@echo off
set HOST=%1
set S_PORT=%2
set T_PORT=%3
if "%HOST%"=="" set HOST=localhost
if "%S_PORT%"=="" set S_PORT=9091
if "%T_PORT%"=="" set T_PORT=9090
echo ==============================================================
echo   STARTING NOBITA DEPARTURE CPS VIDEO SYNC APP
echo   Video Target: Timeline 1.mov (Exit Trigger at t = 3.0s)
echo   Sensors Bus: %HOST%:%S_PORT%
echo   Telemetry Bus: %HOST%:%T_PORT%
echo ==============================================================
java -cp out NobitaDepartureSyncApp %HOST% %S_PORT% %T_PORT%
