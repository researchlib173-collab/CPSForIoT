@echo off
setlocal

echo ============================================
echo  CPS Workbench Build Script — Phase 2
echo ============================================

if not exist "out" mkdir out

echo [1/2] Compiling all sources...
javac -d out src\*.java apps\smart_room\NobitaDepartureSyncApp.java apps\smart_room\SmartRoomBenchApp.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo [2/2] Build successful! Binaries in .\out\
echo.
echo  -- SMART ROOM WORKSPACE --
echo  1. Run the CLI simulation engine :  run_cli.bat
echo  2. Run the Sensor Input Applet   :  run_input.bat
echo  3. Run the Room Dashboard Applet :  run_dashboard.bat
echo  4. Run Realistic Room Simulator  :  run_smart_room_bench.bat
echo.
echo  -- THERMAL BENCH WORKSPACE --
echo  4. Run Bench Input Stimulator    :  run_bench_input.bat
echo  5. Run Bench Telemetry Dashboard :  run_bench_dashboard.bat
echo.
echo  -- ESP32 SIMPLE BLINK WORKSPACE --
echo  6. Run LED Blink Visualizer      :  run_led_applet.bat
echo  7. Run AutoRoute Circuit CAD     :  run_blink_autoroute.bat
echo ============================================
