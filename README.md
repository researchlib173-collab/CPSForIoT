# IoT CPS Workbench — Smart Room Energy Management System

A modular Cyber-Physical System (CPS) simulation framework in Java mimicking embedded hardware prototyping, pin netlists, on-board microcontroller firmware, wireless IoT socket bridges, and interactive Swing GUI dashboards.

## System Architecture

```text
                    ┌───────────────────────────────────────────────────────────┐
                    │               WORKSPACE PHYSICAL NETLIST BUS              │
                    │   PIR_MotionSensor  ──[Wire]──> GPIO_12                   │
                    │   DoorContactSensor ──[Wire]──> GPIO_14  [RoomMasterMCU]  │
                    │   mmWaveRadar       ──[Wire]──> GPIO_27         │         │
                    │   LightRelay        <──[Wire]── GPIO_21         │         │
                    │   HvacRelay         <──[Wire]── GPIO_22         │         │
                    └───────────────▲─────────────────────────▲───────┴─────────┘
                                    │                         │
               PHYSICAL TERMINAL    │                         │ WIRELESS ANTENNA
               I/O TAP              │                         │ TELEMETRY
                                    ▼                         ▼
            ┌───────────────────────────────┐     ┌───────────────────────────────┐
            │ PORT 2: PHYSICAL TERMINAL BUS │     │     PORT 1: WIRELESS BRIDGE   │
            │          (Port 9091)          │     │          (Port 9090)          │
            └───────────────▲───────────────┘     └───────────────▲───────────────┘
                            │                                     │
                            └───────────────────┬─────────────────┘
                                                │
                                    ┌───────────┴───────────┐
                                    ▼                       ▼
                        ┌───────────────────────┐ ┌───────────────────┐
                        │  SmartRoomDashboard   │ │   SimulatorApp    │
                        │    (Desktop GUI)      │ │   (Desktop GUI)   │
                        └───────────────────────┘ └───────────────────┘
```

## Key Modules

- **Core Netlist Engine (`src/CpsDomain.java`)**: Models electrical pins (`DIGITAL_IN`, `DIGITAL_OUT`, `ANALOG_IN`, `PWM_OUT`), wires, propagation, and boards.
- **Hardware Abstraction Layer (`McuHAL`)**: Provides Arduino/ESP-IDF equivalent hardware API: `digitalRead()`, `digitalWrite()`, `analogReadVoltage()`, `analogWriteVoltage()`, and `radioTransmit()`.
- **On-Board Microcontroller Firmware (`src/SmartRoomFirmware.java`)**: The compiled control logic burned directly onto the chip:
  - Samples physical pins (`GPIO_12`, `GPIO_14`, `GPIO_27`).
  - Evaluates multi-modal sensor fusion (PIR motion + mmWave radar + door contact latches).
  - Runs stateful hysteresis (`VACANT` ➔ `OCCUPIED` ➔ `HYSTERESIS`) preventing lights from extinguishing when occupants sit still or sleep.
  - Actuates high-voltage relays (`GPIO_21` lights, `GPIO_22` HVAC).
  - Broadcasts wireless telemetry over Port 1.
- **Canonical 3-Port Architecture (Strictly Fixed Across ALL Projects)**:
  - **Port 9090 (`PORT_TELEMETRY`)**: MCU wireless radio antenna (Wi-Fi/Bluetooth) streaming JSON telemetry packets (`RoomDashboardApplet`, `ThermalBenchDashboardApplet`).
  - **Port 9091 (`PORT_SENSORS`)**: All physical sensor stimuli and netlist inputs across all projects (`SensorInputApplet`, `ThermalBenchInputApplet`).
  - **Port 9092 (`PORT_ACTUATORS`)**: All physical actuator states, hardware outputs, and control bus (`LedBlinkApplet`, relay & load visualizers).
- **Interactive Swing GUIs**:
  - `SmartRoomDashboard.java`: Animated room monitor, relay glow circles, radar confidence meters, and hysteresis countdown arcs.
  - `SimulatorApp.java`: Hardware input/output testbed with analog dials and voltage sliders.

---

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

# Flash on-board firmware directly onto the MCU ROM
flash RoomMasterMCU SmartRoomFirmware 30

# Open Port 1 (Wireless telemetry) & Port 2 (Physical terminal bus)
applet link RoomMasterMCU tcp 9090
workspace terminal link tcp 9091

sim start
sim status
inspect RoomMasterMCU
```

### 3. Run GUI Dashboard
```cmd
run_dashboard.bat
```
Click **Connect** to connect to both Port 1 (`9090`) and Port 2 (`9091`). Toggling sensors in the GUI drives real physical netlist voltages, triggering the flashed firmware on the MCU to actuate the relays in real time!

---

## Layout & Board Persistence

### 1. Saving & Loading Entire Workspace Layouts
Save the active circuit topology (all boards, their pinouts, voltage states, flashed firmware, and connecting wires) into a portable JSON layout file:

```text
# Save active workspace layout
layout save smart_room.json

# List all saved layouts
layout list

# Restore a layout into the workbench
layout load smart_room.json
```

### 2. Saving & Loading Standalone Boards with Firmware
Export an individual board definition, including its model, pin layout, internal components, and on-board firmware configuration:

```text
# Save an individual board with its flashed firmware
board save RoomMasterMCU esp32_room_master.json

# List saved boards
board list

# Load and replicate the board into any workspace
board load esp32_room_master.json as Room2MCU
```

---

## ESP32 Simple Blink Project & Interactive LED Visualizer

A clean, focused hardware simulation and visualizer workspace for the classic **ESP32 LED Blink**:

### 1. Project Components & Topology
* **ESP32_BlinkNode:** ESP32-WROOM MCU running `Esp32BlinkFirmware`, driving `GPIO_02` (3.3V / 0.0V) and broadcasting real-time JSON telemetry over Port 9090 (`PORT_TELEMETRY`).
* **StatusLEDModule:** 5mm status LED breakout board (`assets/led_module.png`) with current-limiting resistor, wired `GPIO_02 → ANODE` and `GND → CATHODE`, monitored on Port 9092 (`PORT_ACTUATORS`).
* **Workspace Layout:** `layouts/esp32_blink.json`

### 2. Running the LED Blink Applet
```cmd
run_led_applet.bat
```
* **Photorealistic 3D Glowing 5mm LED:** Cylindrical epoxy dome, internal cathode anvil and anode bond wire, realistic specular glass reflections, and smooth dynamic radial bloom flares when active.
* **Rolling Logic Analyzer Scope:** Real-time square wave graph tracking high/low logic transitions over time.
* **Dual-Mode Operation:** Seamlessly locks onto live Port 9092 actuator bus / Port 9090 radio telemetry, or runs with its internal simulation clock in standalone mode.
* **Interactive Controls:** Frequency slider (0.2Hz to 5.0Hz), quick presets (0.5Hz, 1.0Hz, 2.0Hz, 5.0Hz), color theme selector (Emerald Green, Neon Cyan, Ruby Red, Amber Gold, Electric Violet), manual toggle, and pause/resume.
* **Typography:** `Space Mono` primary font face across all UI elements and custom graphics.

### 3. Circuit CAD Auto-Router & Pin Calibration
```cmd
run_blink_autoroute.bat
# or in CLI:
autoroute blink
```
* Renders real component images with calibrated pin positions and auto-routes Manhattan orthogonal wires with live voltage color coding.

