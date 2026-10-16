@echo off
setlocal

echo ============================================
echo  CPS Workbench Build Script — Phase 2
echo ============================================

if not exist "out" mkdir out

echo [1/2] Compiling all sources...
javac -d out ^
  src\CpsDomain.java ^
  src\HardwareDictionary.java ^
  src\SmartRoomFirmware.java ^
  src\PresenceFusionEngine.java ^
  src\CpsPersistence.java ^
  src\SmartRoomCLI.java ^
  src\SensorInputApplet.java ^
  src\RoomDashboardApplet.java ^
  src\SimulatorApp.java ^
  src\SmartRoomDashboard.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo [2/2] Build successful! Binaries in .\out\
echo.
echo  1. Run the CLI simulation engine :  run_cli.bat
echo  2. Run the Sensor Input Applet   :  run_input.bat
echo  3. Run the Room Dashboard Applet :  run_dashboard.bat
echo ============================================
