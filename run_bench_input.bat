@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9093
echo Starting Thermal Bench Input Stimulator ^(connecting to Terminal Bus at %HOST%:%PORT%^)...
java -cp out ThermalBenchInputApplet %HOST% %PORT%
