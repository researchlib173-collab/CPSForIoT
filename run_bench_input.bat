@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9091
echo Starting Thermal Bench Input Stimulator ^(connecting to Sensors Bus at %HOST%:%PORT%^)...
java -cp out ThermalBenchInputApplet %HOST% %PORT%
