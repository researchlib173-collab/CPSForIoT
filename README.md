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
- **Two-Port Network Architecture**:
  - **Port 1 (9090)**: Board wireless antenna (Wi-Fi/Bluetooth) streaming JSON telemetry packets.
  - **Port 2 (9091)**: Physical netlist terminal tap for direct hardware DAQ and voltage injection.
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
