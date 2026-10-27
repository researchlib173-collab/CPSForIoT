@echo off
set HOST=%1
set PORT=%2
if "%PORT%"=="" (
    echo [PROMPT] Asking for workspace dynamic actuator port...
    java -cp out LedBlinkApplet --prompt
) else (
    if "%HOST%"=="" set HOST=localhost
    echo Starting ESP32 LED Blink Applet (connecting to Actuators Bus at %HOST%:%PORT%)...
    java -cp out LedBlinkApplet %HOST% %PORT%
)
