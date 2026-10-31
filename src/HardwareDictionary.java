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
        private double currentLumens = 0.0;
        private long lastTickTime = System.currentTimeMillis();

        public LED(String id) {
            super(id, "LED");
            setProperty("is_illuminated", false);
            setProperty("brightness_pct", 0.0);
            setProperty("forward_voltage_v", 2.0); // 2.0V forward threshold knee
            setProperty("phosphor_decay_ms", 2.0);  // 2ms phosphor persistence delay
            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                long dt = Math.max(1, now - lastTickTime);
                lastTickTime = now;

                double vIn = getDoubleProperty("v_in", 0.0);
                double vf = getDoubleProperty("forward_voltage_v", 2.0);
                
                // Exponential diode I-V characteristic curve: I = Is * (exp(V/Vt) - 1)
                double targetPct = 0.0;
                if (vIn >= vf) {
                    double over = Math.min(1.3, vIn - vf);
                    targetPct = Math.min(100.0, Math.pow(over / 1.3, 1.4) * 100.0);
                }

                // Phosphor decay / rise filter (RC model: 2ms persistence)
                double alpha = Math.min(1.0, dt / 2.0);
                currentLumens += (targetPct - currentLumens) * alpha;

                setProperty("is_illuminated", currentLumens > 1.0);
                setProperty("brightness_pct", currentLumens);
            };
        }
    }

    // ─── SENSORS WITH REAL-WORLD DYNAMICS & LATENCY ──────────────────────────

    public static class TemperatureSensor extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        private double sensedTempC = 25.0;
        private long lastTickTime = System.currentTimeMillis();

        public TemperatureSensor(String id, double baseTemp) {
            super(id, "TEMP_SENSOR");
            this.sensedTempC = baseTemp;
            setProperty("base_temp_c", baseTemp);
            setProperty("temp_c", baseTemp);
            setProperty("thermal_tau_ms", 3500.0); // 3.5s thermal package mass inertia
            setProperty("self_heating_c", 0.04);   // +0.04°C self heating
            setProperty("adc_noise_c", 0.03);      // ±0.03°C thermal Johnson noise

            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                long dt = Math.max(1, now - lastTickTime);
                lastTickTime = now;

                double targetTemp = getDoubleProperty("base_temp_c", 25.0);
                double tau = getDoubleProperty("thermal_tau_ms", 3500.0);

                // Newton's Law of Cooling / Thermal inertia lag: dT/dt = (T_ambient - T) / tau
                double alpha = Math.min(1.0, dt / tau);
                sensedTempC += (targetTemp - sensedTempC) * alpha;

                // Self heating offset + Gaussian ADC thermal noise
                double noise = (rng.nextDouble() - 0.5) * 0.06;
                double reportedTemp = sensedTempC + 0.04 + noise;

                setProperty("temp_c", reportedTemp);
                // 10mV/°C scale (e.g. TMP36) scaled to 0-3.3V
                double vOut = Math.max(0.0, Math.min(3.3, (reportedTemp / 100.0) * 3.3));
                setProperty("v_out", vOut);
            };
        }

        public void setTargetTemperature(double t) {
            setProperty("base_temp_c", t);
        }
    }

    /**
     * Binary Passive Infrared (PIR) Sensor — HC-SR501 / Panasonic AMN physical model.
     * Incorporates real-world pyroelectric heating latency (180ms),
     * monostable multivibrator dwell hold time (3.0s),
     * re-trigger inhibit/blocking delay (500ms), and power-on thermal stabilization.
     */
    public static class PIRSensor extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        private boolean rawMotionStimulus = false;
        private long scheduledTripTime = 0;
        private long holdUntilTime = 0;
        private long blockedUntilTime = 0;
        private final long bootTime = System.currentTimeMillis();

        // Real-world physical constants
        public static final long RESPONSE_LATENCY_MS = 180; // 180ms pyroelectric heating & comparator rise
        public static final long MONOSTABLE_HOLD_MS  = 3000; // 3.0s RC monostable hold time
        public static final long BLOCKING_INTERVAL_MS= 500;  // 500ms dead-time after pulse falls
        public static final long WARMUP_DURATION_MS  = 1200; // 1.2s cold boot thermal stabilization

        public PIRSensor(String id) {
            super(id, "PIR_SENSOR");
            setProperty("motion_detected", false);
            setProperty("v_out", 0.0);
            setProperty("is_warmed_up", false);
            setProperty("is_blocked", false);
            setProperty("response_latency_ms", (double) RESPONSE_LATENCY_MS);
            setProperty("hold_duration_ms", (double) MONOSTABLE_HOLD_MS);
            setProperty("blocking_interval_ms", (double) BLOCKING_INTERVAL_MS);

            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                boolean isWarmedUp = (now - bootTime >= WARMUP_DURATION_MS);
                setProperty("is_warmed_up", isWarmedUp);

                boolean isBlocked = (now < blockedUntilTime);
                setProperty("is_blocked", isBlocked);

                boolean outputHigh = false;

                if (isWarmedUp) {
                    // Check if scheduled trip time has arrived
                    if (scheduledTripTime > 0 && now >= scheduledTripTime) {
                        outputHigh = true;
                    }

                    // Check if within monostable hold window
                    if (now < holdUntilTime) {
                        outputHigh = true;
                    } else if (scheduledTripTime > 0 && now >= holdUntilTime) {
                        // Hold expired -> transition to LOW and enter blocking interval
                        scheduledTripTime = 0;
                        blockedUntilTime = now + BLOCKING_INTERVAL_MS;
                    }
                }

                // Add slight analog electrical jitter to 3.3V rail when HIGH
                double vOut = 0.0;
                if (outputHigh) {
                    double jitter = (rng.nextDouble() - 0.5) * 0.02;
                    vOut = Math.max(3.25, Math.min(3.33, 3.30 + jitter));
                } else {
                    double floor = rng.nextDouble() * 0.015;
                    vOut = floor;
                }

                setProperty("v_out", vOut);
                setProperty("motion_detected", outputHigh);
            };
        }

        public void setMotion(boolean detected) {
            long now = System.currentTimeMillis();
            if (detected && !rawMotionStimulus) {
                // Rising edge of physical human motion in field of view
                rawMotionStimulus = true;
                if (now >= blockedUntilTime && now - bootTime >= WARMUP_DURATION_MS) {
                    // Schedule rising edge with pyroelectric response latency (180ms)
                    scheduledTripTime = now + RESPONSE_LATENCY_MS;
                    holdUntilTime = scheduledTripTime + MONOSTABLE_HOLD_MS;
                }
            } else if (!detected && rawMotionStimulus) {
                // Motion ceased: output continues to hold until holdUntilTime
                rawMotionStimulus = false;
            } else if (detected && rawMotionStimulus) {
                // Re-triggering while still in motion extends the hold window
                holdUntilTime = now + MONOSTABLE_HOLD_MS;
            }
        }

        public boolean isMotionDetected() { return (boolean) getProperty("motion_detected", false); }
        public boolean isWarmedUp()       { return (boolean) getProperty("is_warmed_up", false); }
        public boolean isBlocked()        { return (boolean) getProperty("is_blocked", false); }
    }

    /**
     * Reed-switch magnetic door/window contact sensor with physical contact bounce.
     * Generates 12ms mechanical spring chatter before settling.
     */
    public static class DoorContactSensor extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        private boolean physicalDoorOpen = false;
        private long lastTransitionTime = 0;
        public static final long BOUNCE_DURATION_MS = 12; // 12ms mechanical contact chatter

        public DoorContactSensor(String id) {
            super(id, "DOOR_CONTACT");
            setProperty("door_open", false);
            setProperty("v_out", 0.0);
            setProperty("contact_bouncing", false);
            setProperty("bounce_duration_ms", (double) BOUNCE_DURATION_MS);

            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                long elapsed = now - lastTransitionTime;
                boolean isBouncing = (elapsed < BOUNCE_DURATION_MS && lastTransitionTime > 0);
                setProperty("contact_bouncing", isBouncing);

                double vOut;
                if (isBouncing) {
                    // During mechanical chatter bounce, contacts rapidly make and break
                    boolean chatter = rng.nextBoolean();
                    vOut = chatter ? 3.3 : 0.0;
                } else {
                    vOut = physicalDoorOpen ? 3.3 : 0.0;
                }
                setProperty("v_out", vOut);
                setProperty("door_open", physicalDoorOpen && !isBouncing);
            };
        }

        public void setDoorOpen(boolean open) {
            if (open != physicalDoorOpen) {
                physicalDoorOpen = open;
                lastTransitionTime = System.currentTimeMillis();
            }
        }

        public boolean isDoorOpen() { return (boolean) getProperty("door_open", false); }
    }

    /**
     * 60 GHz mmWave Radar — Texas Instruments / HLK-LD2410 physical model.
     * Incorporates 120ms Range-Doppler 2D FFT pipeline accumulation latency,
     * first-order analog/DSP low-pass filter exponential slew dynamics (tau = 140ms),
     * physiological micro-Doppler respiratory chest oscillation (0.28 Hz), and RF clutter floor.
     */
    public static class MmWaveRadar extends CpsDomain.ElectronicComponent {
        private final Random rng = new Random();
        private double targetVoltage = 0.05;
        private double filteredVoltage = 0.05;
        private long lastTickTime = System.currentTimeMillis();

        // Real-world physical parameters
        public static final long PIPELINE_LATENCY_MS = 120; // 120ms 2D FFT & CFAR window lag
        public static final double SLEW_TAU_MS        = 140.0; // 140ms RC filter time constant
        public static final double BREATH_FREQ_HZ     = 0.28; // 0.28 Hz (~17 breaths/min)
        public static final double BREATH_AMP_V       = 0.09; // 90mV chest wall displacement micro-motion

        // Pipeline delay FIFO queue
        private final ArrayDeque<TimestampedSample> delayPipeline = new ArrayDeque<>();

        private static class TimestampedSample {
            long targetTime;
            double targetV;
            TimestampedSample(long t, double v) { targetTime = t; targetV = v; }
        }

        public MmWaveRadar(String id) {
            super(id, "MMWAVE_RADAR");
            setProperty("presence_detected", false);
            setProperty("confidence_pct", 0.0);
            setProperty("v_out", 0.05);
            setProperty("pipeline_latency_ms", (double) PIPELINE_LATENCY_MS);
            setProperty("filter_tau_ms", SLEW_TAU_MS);

            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                long dt = Math.max(1, now - lastTickTime);
                lastTickTime = now;

                // Push current target into pipeline
                delayPipeline.addLast(new TimestampedSample(now + PIPELINE_LATENCY_MS, targetVoltage));

                // Pop samples that have completed pipeline accumulation
                double currentDelayedTarget = targetVoltage;
                while (!delayPipeline.isEmpty() && delayPipeline.peekFirst().targetTime <= now) {
                    currentDelayedTarget = delayPipeline.pollFirst().targetV;
                }

                // 1st-order exponential low-pass filter (RC slew dynamics)
                double alpha = Math.min(1.0, dt / SLEW_TAU_MS);
                filteredVoltage += (currentDelayedTarget - filteredVoltage) * alpha;

                // Physiological Micro-Doppler respiratory modulation (if presence confidence active)
                double breathModulation = 0.0;
                if (filteredVoltage > 1.2) {
                    double phase = (now % 100000) * 0.001 * 2.0 * Math.PI * BREATH_FREQ_HZ;
                    breathModulation = BREATH_AMP_V * Math.sin(phase);
                }

                // RF noise and clutter floor
                double rfNoise = (rng.nextDouble() - 0.5) * 0.03;
                double vFinal = Math.max(0.0, Math.min(3.3, filteredVoltage + breathModulation + rfNoise));

                double confidencePct = Math.min(100.0, (vFinal / 3.3) * 100.0);
                setProperty("confidence_pct", confidencePct);
                setProperty("v_out", vFinal);
                setProperty("presence_detected", confidencePct >= 35.0);
            };
        }

        public void setPresence(boolean present) {
            this.targetVoltage = present ? 2.85 : 0.05;
        }

        public void setRawTargetVoltage(double v) {
            this.targetVoltage = Math.max(0.0, Math.min(3.3, v));
        }

        public boolean isPresenceDetected() { return (boolean) getProperty("presence_detected", false); }
        public double getConfidence()       { return getDoubleProperty("confidence_pct", 0.0); }
    }

    /**
     * High-Voltage Relay Module & Contactor — physical electro-mechanical model.
     * Incorporates inductive coil pickup latency (16ms),
     * spring-return dropout break latency (11ms),
     * and AC zero-crossing synchronization delay (8ms).
     */
    public static class RelayModule extends CpsDomain.ElectronicComponent {
        private boolean coilEnergizedTarget = false;
        private boolean contactsClosed = false;
        private long scheduledContactChangeTime = 0;

        // Electromagnetic relay physics
        public static final long PICKUP_LATENCY_MS  = 16; // 16ms coil L/R build-up and armature transit
        public static final long DROPOUT_LATENCY_MS = 11; // 11ms flyback collapse and spring break
        public static final long AC_ZERO_CROSSING_MS= 8;  // 8ms zero-crossing sync for AC load

        public RelayModule(String id) {
            super(id, "RELAY_HV");
            setProperty("coil_energized", false);
            setProperty("load_active", false);
            setProperty("pickup_latency_ms", (double) PICKUP_LATENCY_MS);
            setProperty("dropout_latency_ms", (double) DROPOUT_LATENCY_MS);
            setProperty("contact_resistance_ohms", 0.04);

            this.baseLogic = () -> {
                double vIn = getDoubleProperty("v_in", 0.0);
                boolean energized = (vIn > 1.65);
                long now = System.currentTimeMillis();

                if (energized != coilEnergizedTarget) {
                    coilEnergizedTarget = energized;
                    // Scheduled contact change with mechanical delay
                    scheduledContactChangeTime = now + (energized ? (PICKUP_LATENCY_MS + AC_ZERO_CROSSING_MS) : DROPOUT_LATENCY_MS);
                }

                if (scheduledContactChangeTime > 0 && now >= scheduledContactChangeTime) {
                    contactsClosed = coilEnergizedTarget;
                    scheduledContactChangeTime = 0;
                }

                setProperty("coil_energized", coilEnergizedTarget);
                setProperty("load_active", contactsClosed);
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

    /** Creates a standalone Relay board with IN_DC pin and physical coil pickup/dropout latency */
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
            board.setProperty("coil_energized", relay.getProperty("coil_energized", false));
        };
        return board;
    }

    /** Creates a PIR sensor board with physical pyroelectric response latency & monostable dwell */
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
            Object stim = board.getProperty("stimulus_active", null);
            if (stim instanceof Boolean b) {
                pir.setMotion(b);
            }
            pir.tickLogic();
            board.getPin("DATA").setVoltage(pir.getDoubleProperty("v_out", 0.0));
        };
        return board;
    }

    /** Creates a Magnetic Reed Door Contact board with contact bounce dynamics */
    public static CpsDomain.Board createDoorSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "DOOR_CONTACT");
        board.addPin("STATE", CpsDomain.PinMode.DIGITAL_OUT);
        board.imagePath = "assets/magnetic_door_sensor.png";
        DoorContactSensor door = new DoorContactSensor(name + "_door");
        board.addComponent(door);
        board.baseLogic = () -> {
            Object stim = board.getProperty("stimulus_active", null);
            if (stim instanceof Boolean b) {
                door.setDoorOpen(b);
            }
            door.tickLogic();
            board.getPin("STATE").setVoltage(door.getDoubleProperty("v_out", 0.0));
        };
        return board;
    }

    /** Creates a 60GHz mmWave Radar board with pipeline accumulation latency & exponential low-pass slew */
    public static CpsDomain.Board createRadarSensor(String name) {
        CpsDomain.Board board = new CpsDomain.Board(name, "MMWAVE_RADAR");
        board.addPin("PRESENCE_SIG", CpsDomain.PinMode.ANALOG_IN);
        board.imagePath = "assets/radar_sensor.png";
        MmWaveRadar radar = new MmWaveRadar(name + "_radar");
        board.addComponent(radar);
        board.baseLogic = () -> {
            Object stim = board.getProperty("stimulus_active", null);
            if (stim instanceof Boolean b) {
                radar.setPresence(b);
            }
            Object rawV = board.getProperty("stimulus_voltage", null);
            if (rawV instanceof Number n) {
                radar.setRawTargetVoltage(n.doubleValue());
            }
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
        private final Random rng = new Random();
        private long lastConversionTime = System.currentTimeMillis();
        public static final long CONVERSION_TIME_MS = 1; // 532 µs conversion rounded
        public static final long I2C_LATENCY_MS     = 1; // 1.2ms I2C transaction framing

        public INA219(String id) {
            super(id, "INA219");
            setProperty("bus_voltage", 12.0);
            setProperty("current_a", 0.0);
            setProperty("shunt_voltage_mv", 0.0);
            setProperty("power_w", 0.0);
            setProperty("i2c_addr", 0x40);
            setProperty("i2c_latency_ms", (double) I2C_LATENCY_MS);

            this.baseLogic = () -> {
                long now = System.currentTimeMillis();
                if (now - lastConversionTime >= CONVERSION_TIME_MS) {
                    lastConversionTime = now;
                    double vBus = getDoubleProperty("bus_voltage", 12.0);
                    double iLoad = getDoubleProperty("current_a", 0.0);
                    // Shunt temperature coefficient drift (50 ppm/°C)
                    double drift = 1.0 + (rng.nextDouble() - 0.5) * 0.002;
                    double vShuntMv = iLoad * 0.1 * 1000.0 * drift;
                    double pWatts = vBus * iLoad;
                    setProperty("shunt_voltage_mv", vShuntMv);
                    setProperty("power_w", pWatts);
                }
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
                double currentT = ts.getDoubleProperty("temp_c", 25.0);
                double v = Math.max(0.0, Math.min(3.3, (currentT / 100.0) * 3.3));
                pin.voltage = v;
                pin.digitalState = (v >= 1.65) ? CpsDomain.SignalState.HIGH : CpsDomain.SignalState.LOW;
            }
        };
        return board;
    }

    public static CpsDomain.Board createComponentBoard(String type, String name) {
        String t = (type != null) ? type.trim().toUpperCase() : "CUSTOM";
        if (t.equals("CUSTOM") && name != null) {
            String nUpper = name.toUpperCase();
            if (nUpper.contains("PIR") || nUpper.contains("MOTION")) t = "PIR";
            else if (nUpper.contains("DOOR") || nUpper.contains("REED") || nUpper.contains("CONTACT")) t = "DOOR";
            else if (nUpper.contains("RADAR") || nUpper.contains("MMWAVE")) t = "RADAR";
            else if (nUpper.contains("RELAY")) t = "RELAY";
            else if (nUpper.contains("TEMP")) t = "TEMP";
            else if (nUpper.contains("LED")) t = "LED";
            else if (nUpper.contains("INA219") || nUpper.contains("POWER")) t = "INA219";
            else if (nUpper.contains("ESP32")) t = "ESP32";
        }
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
