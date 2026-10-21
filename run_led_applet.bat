@echo off
set HOST=%1
set PORT=%2
if "%HOST%"=="" set HOST=localhost
if "%PORT%"=="" set PORT=9092
echo Starting ESP32 LED Blink Applet (connecting to Actuators Bus at %HOST%:%PORT%)...
java -cp out LedBlinkApplet %HOST% %PORT%
