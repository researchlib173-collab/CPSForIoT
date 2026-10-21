@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9090
echo Starting Thermal Bench Telemetry Dashboard ^(listening to Radio Telemetry at %HOST%:%PORT%^)...
java -cp out ThermalBenchDashboardApplet %HOST% %PORT%
