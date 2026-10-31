@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9091
echo Starting Sensor Input Applet ^(connecting to Terminal Bus at %HOST%:%PORT%^)...
java -cp out SensorInputApplet %HOST% %PORT%
