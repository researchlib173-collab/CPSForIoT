@echo off
title CPS Simulation Engine - Dynamic Random Ports
cd /d "C:\Users\BBala\OneDrive\Documents\Projects'\CpsWorkbench"
echo ==============================================================
echo   STARTING CPS SIMULATION BACKEND (DYNAMIC RANDOM PORTS)
echo   Ports are allocated instantly at random without prefixed values.
echo   Note down the ports displayed below to enter in your GUI apps.
echo ==============================================================
type setup_phase2.txt | java -cp out SmartRoomCLI
pause
