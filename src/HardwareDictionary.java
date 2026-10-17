import java.util.*;

// ============================================================================
// HARDWARE DICTIONARY — Stage 1 + Phase 2 components
// ============================================================================
public class HardwareDictionary {

    // ========================================================================
    // ─── WIRE CLASS & AMAZING CONNECTION SYNTAX ──────────────────────────────
    // ========================================================================
    /**
     * Hardware Wire with intuitive, fluent syntax for establishing:
     * 1. Wire-to-Wire connections (direct electrical junctions / taps / splices).
     * 2. Wire-to-Component connections with strict UNIQUENESS ENFORCEMENT [one to many] or [one to one].
     */
    public static class Wire extends CpsDomain.Wire {

        public Wire(String name, CpsDomain.Pin a, CpsDomain.Pin b) {
            super(name, a, b);
        }

        public Wire(String name, CpsDomain.Pin a) {
            super(name, a, null);
        }

        public Wire(String name) {
            super(name, null, null);
        }

        public Wire(CpsDomain.Pin a, CpsDomain.Pin b) {
            super(a, b);
        }

        // ─── FLUENT BUILDER & FACTORY SYNTAX ─────────────────────────────────

        /**
         * Fluent DSL entry point:
         * Wire w = Wire.named("W_MAIN").from(pinA).to(pinB);
         * Wire tap = Wire.named("W_TAP").from(pinC).toWire(w);
         * Wire compTap = Wire.named("W_SENSOR_TAP").from(pinD).toComponent(pirSensor, wires);
         */
        public static WireBuilder named(String name) {
            return new WireBuilder(name);
        }

        public static Wire between(CpsDomain.Pin a, CpsDomain.Pin b) {
            return new Wire("W_" + System.currentTimeMillis(), a, b);
        }

        public static Wire between(String name, CpsDomain.Pin a, CpsDomain.Pin b) {
            return new Wire(name, a, b);
        }

        public static Wire tapInto(CpsDomain.Wire trunkWire, String tapWireName, CpsDomain.Pin branchPin) {
            Wire branch = new Wire(tapWireName, branchPin, null);
            branch.connectToWire(trunkWire);
            return branch;
        }

        public static Wire tapIntoComponent(Object component, String tapWireName, CpsDomain.Pin branchPin, Collection<CpsDomain.Wire> workspaceWires) {
            Wire branch = new Wire(tapWireName, branchPin, null);
            branch.connectToComponent(component, workspaceWires);
            return branch;
        }

        public static class WireBuilder {
            private final String name;
            private CpsDomain.Pin pinA;

            public WireBuilder(String name) {
                this.name = name;
            }

            public WireBuilder from(CpsDomain.Pin pin) {
                this.pinA = pin;
                return this;
            }

            public Wire to(CpsDomain.Pin pinB) {
                return new Wire(name, pinA, pinB);
            }

            public Wire toWire(CpsDomain.Wire targetWire) {
                Wire w = new Wire(name, pinA, null);
                w.connectToWire(targetWire);
                return w;
            }

            public Wire toComponent(Object component, Collection<CpsDomain.Wire> workspaceWires) {
                Wire w = new Wire(name, pinA, null);
                w.connectToComponent(component, workspaceWires);
                return w;
            }

            public Wire toComponent(CpsDomain.Board board) {
                Wire w = new Wire(name, pinA, null);
                w.connectToComponent(board);
                return w;
            }
        }
    }

    // ─── TOP-LEVEL HARDWARE DICTIONARY SYNTAX SHORTCUTS ──────────────────────

    /** Connects wire1 to wire2 directly (fluent wire-to-wire connection). */
    public static CpsDomain.Wire connect(CpsDomain.Wire wire1, CpsDomain.Wire wire2) {
        if (wire1 == null) throw new IllegalArgumentException("wire1 cannot be null");
        return wire1.connectToWire(wire2);
    }

    /** Connects wire to the unique wire of the specified component. */
    public static CpsDomain.Wire connect(CpsDomain.Wire wire, Object component, Collection<CpsDomain.Wire> workspaceWires) {
        if (wire == null) throw new IllegalArgumentException("wire cannot be null");
        return wire.connectToComponent(component, workspaceWires);
    }

    /** Connects wire to the unique wire of the specified board. */
    public static CpsDomain.Wire connect(CpsDomain.Wire wire, CpsDomain.Board board) {
        if (wire == null) throw new IllegalArgumentException("wire cannot be null");
        return wire.connectToComponent(board);
    }

    /** Taps a branch wire into a trunk wire. */
    public static CpsDomain.Wire tap(CpsDomain.Wire trunkWire, CpsDomain.Wire branchWire) {
        return connect(branchWire, trunkWire);
    }

    /** Slices / merges two wires into an equipotential junction. */
    public static CpsDomain.Wire splice(CpsDomain.Wire wireA, CpsDomain.Wire wireB) {
        return connect(wireA, wireB);
    }

    /** Alias to CpsDomain.AmbiguousWireConnectionException for dictionary consumers. */
    public static class AmbiguousWireConnectionException extends CpsDomain.AmbiguousWireConnectionException {
        public AmbiguousWireConnectionException(String message) {
            super(message);
        }
    }

    // ─── PASSIVE COMPONENTS ──────────────────────────────────────────────────

    public static class Resistor extends CpsDomain.ElectronicComponent {
        public Resistor(String id, double ohms) {
            super(id, "RESISTOR");
            setProperty("resistance_ohms", ohms);
        }
    }

    public static class Capacitor extends CpsDomain.ElectronicComponent {
        public Capacitor(String id, double farads) {
            super(id, "CAPACITOR");
            setProperty("capacitance_f", farads);
        }
    }

    public static class LED extends CpsDomain.ElectronicComponent {
        public LED(String id) {
            super(id, "LED");
            this.baseLogic = () -> {
                double vIn = getDoubleProperty("v_in", 0.0);
                setProperty("is_illuminated", vIn >= 2.0);
                setProperty("brightness_pct", Math.min(100.0, Math.max(0.0, (vIn - 2.0) / 1.3 * 100.0)));
            };
        }
    }

    // ─── SENSORS ─────────────────────────────────────────────────────────────

    public static class TemperatureSensor extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        public TemperatureSensor(String id, double baseTemp) {
            super(id, "TEMP_SENSOR");
            setProperty("base_temp_c", baseTemp);
            setProperty("temp_c", baseTemp);
            this.baseLogic = () -> {
                double base  = getDoubleProperty("base_temp_c", 25.0);
                double drift = (rng.nextDouble() - 0.5) * 0.4;
                double temp  = base + drift;
                setProperty("temp_c", temp);
                setProperty("v_out", Math.max(0, Math.min(3.3, temp / 100.0 * 3.3)));
            };
        }
    }

    /** Binary PIR sensor — digital HIGH when motion detected. */
    public static class PIRSensor extends CpsDomain.ElectronicComponent {
        public PIRSensor(String id) {
            super(id, "PIR_SENSOR");
            setProperty("motion_detected", false);
            this.baseLogic = () -> {
                boolean motion = (boolean) getProperty("motion_detected", false);
                setProperty("v_out", motion ? 3.3 : 0.0);
            };
        }
        public void setMotion(boolean detected) { setProperty("motion_detected", detected); }
        public boolean isMotionDetected()       { return (boolean) getProperty("motion_detected", false); }
    }

    /** Reed-switch magnetic door/window contact sensor. */
    public static class DoorContactSensor extends CpsDomain.ElectronicComponent {
        public DoorContactSensor(String id) {
            super(id, "DOOR_CONTACT");
            setProperty("door_open", false);
            this.baseLogic = () -> {
                boolean open = (boolean) getProperty("door_open", false);
                setProperty("v_out", open ? 3.3 : 0.0);
            };
        }
        public void setDoorOpen(boolean open) { setProperty("door_open", open); }
        public boolean isDoorOpen()           { return (boolean) getProperty("door_open", false); }
    }

    /**
     * 60 GHz mmWave radar — detects micro-motion (breathing, sleeping).
     * Output voltage reflects presence confidence: 0–3.3 V.
     */
    public static class MmWaveRadar extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        public MmWaveRadar(String id) {
            super(id, "MMWAVE_RADAR");
            setProperty("presence_detected", false);
            setProperty("confidence_pct", 0.0);
            this.baseLogic = () -> {
                boolean present = (boolean) getProperty("presence_detected", false);
                // Add slight analogue noise for realism
                double noise = (rng.nextDouble() - 0.5) * 0.05;
                double confidence = present ? Math.min(1.0, 0.85 + noise) : Math.max(0.0, 0.05 + noise);
                setProperty("confidence_pct", confidence * 100.0);
                setProperty("v_out", confidence * 3.3);
            };
        }
        public void setPresence(boolean present)  { setProperty("presence_detected", present); }
        public boolean isPresenceDetected()        { return (boolean) getProperty("presence_detected", false); }
        public double  getConfidence()             { return getDoubleProperty("confidence_pct", 0.0); }
    }

    /** High-voltage relay module — controlled by 3.3V/5V digital signal. */
    public static class RelayModule extends CpsDomain.ElectronicComponent {
        public RelayModule(String id) {
            super(id, "RELAY_HV");
            setProperty("coil_energized", false);
            setProperty("load_active", false);
            this.baseLogic = () -> {
                double vIn = getDoubleProperty("v_in", 0.0);
                boolean energized = vIn > 1.65;
                setProperty("coil_energized", energized);
                setProperty("load_active",    energized);   // Normally-open relay
            };
        }
        public boolean isLoadActive() { return (boolean) getProperty("load_active", false); }
    }

    // ─── MICROPROCESSOR FACTORIES ────────────────────────────────────────────

    public static CpsDomain.Board createESP32(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "ESP32_WROOM");
        // Left Column Header (15 standard pins)
        board.addPin("EN",      CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("GPIO_36", CpsDomain.PinMode.ANALOG_IN); // SENSOR_VP / ADC1_CH0
        board.addPin("GPIO_39", CpsDomain.PinMode.ANALOG_IN); // SENSOR_VN / ADC1_CH3
        board.addPin("GPIO_34", CpsDomain.PinMode.ANALOG_IN); // ADC1_CH6
        board.addPin("GPIO_35", CpsDomain.PinMode.ANALOG_IN); // ADC1_CH7
        board.addPin("GPIO_32", CpsDomain.PinMode.ANALOG_IN); // ADC1_CH4 / Touch 9
        board.addPin("GPIO_33", CpsDomain.PinMode.ANALOG_IN); // ADC1_CH5 / Touch 8
        board.addPin("GPIO_25", CpsDomain.PinMode.ANALOG_IN); // DAC1 / ADC2_CH8
        board.addPin("GPIO_26", CpsDomain.PinMode.ANALOG_IN); // DAC2 / ADC2_CH9
        board.addPin("GPIO_27", CpsDomain.PinMode.ANALOG_IN); // Touch 7 / ADC2_CH7
        board.addPin("GPIO_14", CpsDomain.PinMode.DIGITAL_IN); // HSPI_CLK
        board.addPin("GPIO_12", CpsDomain.PinMode.DIGITAL_IN); // HSPI_MISO
        board.addPin("GPIO_13", CpsDomain.PinMode.DIGITAL_IN); // HSPI_MOSI
        board.addPin("GND",     CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("VIN",     CpsDomain.PinMode.DIGITAL_OUT); // 5V External Power In

        // Right Column Header (15 standard pins)
        board.addPin("GPIO_23", CpsDomain.PinMode.DIGITAL_OUT); // VSPI_MOSI
        board.addPin("GPIO_22", CpsDomain.PinMode.PWM_OUT);     // I2C_SCL
        board.addPin("GPIO_01", CpsDomain.PinMode.DIGITAL_OUT); // UART0_TX
        board.addPin("GPIO_03", CpsDomain.PinMode.DIGITAL_IN);  // UART0_RX
        board.addPin("GPIO_21", CpsDomain.PinMode.PWM_OUT);     // I2C_SDA
        board.addPin("GPIO_19", CpsDomain.PinMode.DIGITAL_IN);  // VSPI_MISO
        board.addPin("GPIO_18", CpsDomain.PinMode.PWM_OUT);     // VSPI_CLK
        board.addPin("GPIO_05", CpsDomain.PinMode.DIGITAL_OUT); // VSPI_CS
        board.addPin("GPIO_17", CpsDomain.PinMode.DIGITAL_OUT); // UART2_TX
        board.addPin("GPIO_16", CpsDomain.PinMode.DIGITAL_IN);  // UART2_RX
        board.addPin("GPIO_04", CpsDomain.PinMode.ANALOG_IN);  // Touch 0 / ADC2_CH0
        board.addPin("GPIO_00", CpsDomain.PinMode.DIGITAL_IN);  // BOOT Strapping Pin
        board.addPin("GPIO_02", CpsDomain.PinMode.DIGITAL_OUT); // On-board Blue LED
        board.addPin("GPIO_15", CpsDomain.PinMode.DIGITAL_OUT); // HSPI_CS
        board.addPin("3V3",     CpsDomain.PinMode.DIGITAL_OUT); // 3.3V Regulated Output

        board.getPin("3V3").setVoltage(3.3);
        if (board.getPin("VIN") != null) board.getPin("VIN").setVoltage(5.0);

        board.imagePath = "assets/esp32_wroom.png";

        // Pre-calibrated pin positions for standard 30-pin ESP32-WROOM DevKit (400x560 px):
        String[] leftPins = {"EN", "GPIO_36", "GPIO_39", "GPIO_34", "GPIO_35", "GPIO_32", "GPIO_33",
                             "GPIO_25", "GPIO_26", "GPIO_27", "GPIO_14", "GPIO_12", "GPIO_13", "GND", "VIN"};
        for (int i = 0; i < leftPins.length; i++) {
            double v = (80.0 + i * 26.0) / 560.0;
            board.pinPositions.put(leftPins[i], new java.awt.geom.Point2D.Double(0.100, Math.round(v * 1000.0) / 1000.0));
        }

        String[] rightPins = {"GPIO_23", "GPIO_22", "GPIO_01", "GPIO_03", "GPIO_21", "GPIO_19", "GPIO_18",
                              "GPIO_05", "GPIO_17", "GPIO_16", "GPIO_04", "GPIO_00", "GPIO_02", "GPIO_15", "3V3"};
        for (int i = 0; i < rightPins.length; i++) {
            double v = (80.0 + i * 26.0) / 560.0;
            board.pinPositions.put(rightPins[i], new java.awt.geom.Point2D.Double(0.900, Math.round(v * 1000.0) / 1000.0));
        }

        board.baseLogic = () -> {
            if (board.socketBridge != null) {
                String payload = String.format(
                    "{\"device\":\"%s\",\"model\":\"ESP32_WROOM\"," +
                    "\"gpio12_v\":%.3f,\"gpio14_v\":%.3f,\"gpio27_v\":%.3f," +
                    "\"gpio21_v\":%.3f,\"gpio22_v\":%.3f}",
                    name,
                    board.getPin("GPIO_12").voltage,
                    board.getPin("GPIO_14").voltage,
                    board.getPin("GPIO_27").voltage,
                    board.getPin("GPIO_21").voltage,
                    board.getPin("GPIO_22").voltage
                );
                board.socketBridge.transmit(payload);
            }
        };
        return board;
    }

    public static CpsDomain.Board createNodeMCU(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "NODEMCU_ESP8266");
        board.addPin("3V3", CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("GND", CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("A0",  CpsDomain.PinMode.ANALOG_IN);
        board.addPin("D1",  CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("D2",  CpsDomain.PinMode.PWM_OUT);
        board.getPin("3V3").setVoltage(3.3);
        board.baseLogic = () -> {
            if (board.socketBridge != null) {
                board.socketBridge.transmit(String.format(
                    "{\"device\":\"%s\",\"model\":\"NODEMCU_ESP8266\",\"A0_v\":%.3f}",
                    name, board.getPin("A0").voltage));
            }
        };
        return board;
    }

    public static CpsDomain.Board createArduinoUno(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "ARDUINO_UNO_R3");
        board.addPin("5V",  CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("GND", CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("A0",  CpsDomain.PinMode.ANALOG_IN);
        board.addPin("A1",  CpsDomain.PinMode.ANALOG_IN);
        board.addPin("D13", CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("D9",  CpsDomain.PinMode.PWM_OUT);
        board.getPin("5V").setVoltage(5.0);
        board.baseLogic = () -> {
            if (board.socketBridge != null) {
                board.socketBridge.transmit(String.format(
                    "{\"device\":\"%s\",\"model\":\"ARDUINO_UNO_R3\",\"A0_v\":%.3f,\"A1_v\":%.3f}",
                    name, board.getPin("A0").voltage, board.getPin("A1").voltage));
            }
        };
        return board;
    }

    /** Creates a standalone Relay board with IN_DC pin */
    public static CpsDomain.Board createRelayModule(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "RELAY_HV_MODULE");
        board.addPin("IN_DC", CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("VCC",   CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("GND",   CpsDomain.PinMode.DIGITAL_OUT);
        RelayModule relay = new RelayModule(name + "_relay");
        board.addComponent(relay);
        board.baseLogic = () -> {
            double v = board.getPin("IN_DC").voltage;
            relay.setProperty("v_in", v);
            relay.tickLogic();
            board.setProperty("load_active", relay.isLoadActive());
        };
        return board;
    }

    public static CpsDomain.Board createPIRSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "PIR_SENSOR");
        board.addPin("DATA", CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("VCC",  CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("GND",  CpsDomain.PinMode.DIGITAL_OUT);
        board.imagePath = "assets/pir_motion_sensor.png";
        board.pinPositions.put("VCC",  new java.awt.geom.Point2D.Double(0.36, 0.83));
        board.pinPositions.put("DATA", new java.awt.geom.Point2D.Double(0.50, 0.83));
        board.pinPositions.put("GND",  new java.awt.geom.Point2D.Double(0.64, 0.83));
        PIRSensor pir = new PIRSensor(name + "_pir");
        board.addComponent(pir);
        board.baseLogic = () -> {
            pir.tickLogic();
            board.getPin("DATA").setVoltage(pir.getDoubleProperty("v_out", 0.0));
        };
        return board;
    }

    public static CpsDomain.Board createDoorSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "DOOR_CONTACT");
        board.addPin("STATE", CpsDomain.PinMode.DIGITAL_OUT);
        board.imagePath = "assets/magnetic_door_sensor.png";
        DoorContactSensor door = new DoorContactSensor(name + "_door");
        board.addComponent(door);
        board.baseLogic = () -> {
            door.tickLogic();
            board.getPin("STATE").setVoltage(door.getDoubleProperty("v_out", 0.0));
        };
        return board;
    }

    public static CpsDomain.Board createRadarSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "MMWAVE_RADAR");
        board.addPin("PRESENCE_SIG", CpsDomain.PinMode.ANALOG_IN);
        board.imagePath = "assets/radar_sensor.png";
        MmWaveRadar radar = new MmWaveRadar(name + "_radar");
        board.addComponent(radar);
        board.baseLogic = () -> {
            radar.tickLogic();
            board.getPin("PRESENCE_SIG").setVoltage(radar.getDoubleProperty("v_out", 0.0));
        };
        return board;
    }

    public static CpsDomain.Board createLedModule(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "LED_MODULE");
        board.addPin("ANODE",   CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("CATHODE", CpsDomain.PinMode.DIGITAL_IN);
        board.imagePath = "assets/led_module.png";
        board.pinPositions.put("ANODE",   new java.awt.geom.Point2D.Double(0.361, 0.833));
        board.pinPositions.put("CATHODE", new java.awt.geom.Point2D.Double(0.639, 0.833));
        LED led = new LED(name + "_led");
        board.addComponent(led);
        board.baseLogic = () -> {
            double v = board.getPin("ANODE").voltage - board.getPin("CATHODE").voltage;
            led.setProperty("v_in", Math.max(0.0, v));
            led.tickLogic();
        };
        return board;
    }

    /**
     * INA219 Zero-Drift, Bidirectional Current/Power Monitor IC with I2C Interface.
     * Specification:
     * - Target Bus Voltage: 0V - 26V DC
     * - Shunt Resistor: 0.1 ohm, 2W
     * - Current Range: up to ±3.2A
     * - Resolution: 0.8mA
     * - Interface: I2C (default address 0x40)
     */
    public static class INA219 extends CpsDomain.ElectronicComponent {
        public INA219(String id) {
            super(id, "INA219");
            setProperty("bus_voltage", 12.0);
            setProperty("current_a", 0.0);
            setProperty("shunt_voltage_mv", 0.0);
            setProperty("power_w", 0.0);
            setProperty("i2c_addr", 0x40);

            this.baseLogic = () -> {
                double vBus = getDoubleProperty("bus_voltage", 12.0);
                double iLoad = getDoubleProperty("current_a", 0.0);
                double vShuntMv = iLoad * 0.1 * 1000.0; // 0.1 ohm shunt
                double pWatts = vBus * iLoad;
                setProperty("shunt_voltage_mv", vShuntMv);
                setProperty("power_w", pWatts);
            };
        }
    }

    public static CpsDomain.Board createINA219(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "INA219");
        board.addPin("VCC",       CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("GND",       CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("SCL",       CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("SDA",       CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("VIN_PLUS",  CpsDomain.PinMode.ANALOG_IN);
        board.addPin("VIN_MINUS", CpsDomain.PinMode.ANALOG_IN);
        board.imagePath = "assets/ina219_sensor.png";

        // Solder pads at bottom row: VCC, GND, SCL, SDA, VIN-, VIN+
        board.pinPositions.put("VCC",       new java.awt.geom.Point2D.Double(0.18, 0.85));
        board.pinPositions.put("GND",       new java.awt.geom.Point2D.Double(0.31, 0.85));
        board.pinPositions.put("SCL",       new java.awt.geom.Point2D.Double(0.44, 0.85));
        board.pinPositions.put("SDA",       new java.awt.geom.Point2D.Double(0.56, 0.85));
        board.pinPositions.put("VIN_MINUS", new java.awt.geom.Point2D.Double(0.69, 0.85));
        board.pinPositions.put("VIN_PLUS",  new java.awt.geom.Point2D.Double(0.82, 0.85));

        INA219 ina = new INA219(name + "_ina");
        board.addComponent(ina);
        board.baseLogic = () -> {
            ina.tickLogic();
            if (board.getPin("SDA") != null) {
                board.getPin("SDA").setVoltage(3.3);
            }
        };
        return board;
    }

    public static CpsDomain.Board createTempSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "TEMP_SENSOR");
        board.addPin("VCC",      CpsDomain.PinMode.DIGITAL_IN);
        board.addPin("GND",      CpsDomain.PinMode.DIGITAL_OUT);
        board.addPin("TEMP_OUT", CpsDomain.PinMode.DIGITAL_OUT);
        board.imagePath = "assets/temperature_sensor.png";

        board.pinPositions.put("VCC",      new java.awt.geom.Point2D.Double(0.30, 0.85));
        board.pinPositions.put("TEMP_OUT", new java.awt.geom.Point2D.Double(0.50, 0.85));
        board.pinPositions.put("GND",      new java.awt.geom.Point2D.Double(0.70, 0.85));

        TemperatureSensor ts = new TemperatureSensor(name + "_sensor", 25.0);
        board.addComponent(ts);
        board.baseLogic = () -> {
            CpsDomain.Pin pin = board.getPin("TEMP_OUT");
            if (pin != null) {
                // If externally controlled via netlist or testbed, preserve and track set temperature
                if (pin.voltage > 0.05) {
                    ts.setProperty("base_temp_c", (pin.voltage / 3.3) * 100.0);
                }
                ts.tickLogic();
                // Retain current temperature with tiny realistic measurement noise (±0.03°C)
                double currentT = ts.getDoubleProperty("base_temp_c", 25.0);
                double noiseT = currentT + (Math.random() - 0.5) * 0.06;
                double v = Math.max(0.0, Math.min(3.3, (noiseT / 100.0) * 3.3));
                pin.voltage = v;
                pin.digitalState = (v >= 1.65) ? CpsDomain.SignalState.HIGH : CpsDomain.SignalState.LOW;
            }
        };
        return board;
    }

    public static CpsDomain.Board createComponentBoard(String type, String name) {
        String t = (type != null) ? type.trim().toUpperCase() : "CUSTOM";
        return switch (t) {
            case "ESP32", "ESP32_WROOM"           -> createESP32(name);
            case "NODEMCU", "ESP8266"             -> createNodeMCU(name);
            case "ARDUINO", "ARDUINO_UNO"         -> createArduinoUno(name);
            case "RELAY", "RELAY_HV", "RELAY_HV_MODULE" -> createRelayModule(name);
            case "PIR", "PIR_SENSOR", "MOTION"   -> createPIRSensor(name);
            case "DOOR", "DOOR_SENSOR", "CONTACT" -> createDoorSensor(name);
            case "RADAR", "MMWAVE", "RADAR_SENSOR"-> createRadarSensor(name);
            case "LED", "LED_MODULE"              -> createLedModule(name);
            case "INA219", "INA219_SENSOR", "POWER_METER" -> createINA219(name);
            case "TEMP", "TEMP_SENSOR", "TEMPERATURE_SENSOR" -> createTempSensor(name);
            default                               -> new CpsDomain.Board(name, t);
        };
    }
}
