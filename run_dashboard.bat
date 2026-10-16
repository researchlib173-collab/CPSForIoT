@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9090
echo Starting Smart Room Dashboard ^(connecting to %HOST%:%PORT%^)...
java -cp out SmartRoomDashboard %HOST% %PORT%
