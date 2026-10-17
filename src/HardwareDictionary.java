import java.util.*;

// ============================================================================
// HARDWARE DICTIONARY — Stage 1 + Phase 2 components
// ============================================================================
public class HardwareDictionary {

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
}
