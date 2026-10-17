@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9094
echo Starting ESP32 LED Blink Applet (listening to Radio Telemetry at %HOST%:%PORT%)...
java -cp out LedBlinkApplet %HOST% %PORT%
