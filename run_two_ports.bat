@echo off
title CPS Simulation Engine - Two Ports (9090 & 9091)
cd /d "C:\Users\BBala\OneDrive\Documents\Projects'\CpsWorkbench"
echo ==============================================================
echo   STARTING TWO-PORT CPS SIMULATION BACKEND
echo   Port 1: 9090 (Virtual Wi-Fi / Radio Telemetry)
echo   Port 2: 9091 (Physical Netlist Terminal Bus)
echo ==============================================================
type setup_phase2.txt | java -cp out SmartRoomCLI
pause
