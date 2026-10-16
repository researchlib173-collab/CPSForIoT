# IoT CPS Workbench — Smart Room Energy Management System

A modular Cyber-Physical System (CPS) simulation framework in Java mimicking embedded hardware prototyping, pin netlists, wireless IoT socket bridges, sensor fusion, and interactive Swing GUI dashboards.

## Features

- **DBMS-Style Pin Netlist Routing**: Virtual boards connected dynamically via virtual wires (`CpsDomain.java`).
- **Hardware Component Dictionary**: Virtual ESP32, NodeMCU, Arduino Uno, PIR motion sensor, mmWave radar, door contact sensors, and high-voltage relays (`HardwareDictionary.java`).
- **Sensor Fusion & Stateful Hysteresis**: Occupancy state machine (`VACANT` ➔ `OCCUPIED` ➔ `HYSTERESIS`) to prevent false vacancy when occupants are sitting or sleeping (`PresenceFusionEngine.java`).
- **Wireless Socket Bridge**: Streams JSON telemetry over TCP (`localhost:9090`) to external desktop/applet dashboards.
- **Java Swing GUIs**:
  - `SmartRoomDashboard.java`: Visualizes room states, radar confidence, relays, and graceful countdown arcs.
  - `SimulatorApp.java`: Hardware input/output simulation testbed with analog gauges and sliders.

## Quick Start

### 1. Compile
```cmd
build.bat
```

### 2. Run CLI Workbench
```cmd
run_cli.bat
```

Paste the setup script into the CLI:
```text
workspace create SmartRoom_Phase2
import ESP32 as RoomMasterMCU
create board PIR_MotionSensor
add pin PIR_MotionSensor DATA DIGITAL_IN
create board DoorContactSensor
add pin DoorContactSensor STATE DIGITAL_IN
create board MillimeterWaveRadar
add pin MillimeterWaveRadar PRESENCE_SIG ANALOG_IN
import RELAY_HV_MODULE as LightRelay
import RELAY_HV_MODULE as HvacRelay
connect RoomMasterMCU GPIO_12 to PIR_MotionSensor DATA pir_wire
connect RoomMasterMCU GPIO_14 to DoorContactSensor STATE door_wire
connect RoomMasterMCU GPIO_27 to MillimeterWaveRadar PRESENCE_SIG radar_wire
connect RoomMasterMCU GPIO_21 to LightRelay IN_DC light_control_wire
connect RoomMasterMCU GPIO_22 to HvacRelay IN_DC hvac_control_wire
attach fusion RoomMasterMCU 30
applet link RoomMasterMCU tcp 9090
sim start
```

### 3. Run GUI Dashboard
```cmd
run_dashboard.bat
```
Click **Connect** to link to `localhost:9090` and monitor/control the smart room simulation.
