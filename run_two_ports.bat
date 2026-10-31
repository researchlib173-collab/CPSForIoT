@echo off
title CPS Simulation Engine - Canonical 3 Ports (9090, 9091, 9092)
cd /d "C:\Users\BBala\OneDrive\Documents\Projects'\CpsWorkbench"
echo ==============================================================
echo   STARTING CANONICAL 3-PORT CPS SIMULATION BACKEND
echo   Port 9090: Wi-Fi Telemetry (All Projects IoT Radio Stream)
echo   Port 9091: All Sensors Bus (All Projects Physical Inputs)
echo   Port 9092: All Actuators Bus (All Projects Physical Outputs)
echo ==============================================================
type setup_phase2.txt | java -cp out SmartRoomCLI
pause
