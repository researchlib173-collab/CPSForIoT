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
  src\SmartRoomCLI.java ^
  src\SimulatorApp.java ^
  src\SmartRoomDashboard.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo [2/2] Build successful! Binaries in .\out\
echo.
echo  Run the CLI workbench  :  run_cli.bat
echo  Run the I/O Simulator  :  run_sim.bat [host] [port]
echo  Run the Room Dashboard :  run_dashboard.bat [host] [port]
echo ============================================
