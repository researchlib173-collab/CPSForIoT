import java.text.SimpleDateFormat;
import java.util.*;

// ============================================================================
// ON-BOARD MICROCONTROLLER FIRMWARE: SMART ROOM ENERGY MANAGEMENT
// ============================================================================
// This class represents the compiled firmware code flashed directly into the
// microcontroller ROM/Flash. It runs autonomously inside the MCU loop().
// ============================================================================
public class SmartRoomFirmware implements CpsDomain.Firmware {

    // ─── Operational States of the Room ──────────────────────────────────────
    public enum RoomState { VACANT, OCCUPIED, HYSTERESIS }

    // ─── Hardware Pin Mapping (MCU GPIO Layout) ──────────────────────────────
    private final String pirPin;          // e.g. "GPIO_12" (Digital Input from PIR)
    private final String doorPin;         // e.g. "GPIO_14" (Digital Input from Magnetic Door Switch)
    private final String radarPin;        // e.g. "GPIO_27" (Analog Input from 60GHz mmWave Radar)
    private final String lightRelayPin;   // e.g. "GPIO_21" (Digital Output to HV Light Relay)
    private final String hvacRelayPin;    // e.g. "GPIO_22" (Digital Output to HV HVAC Relay)

    // ─── Algorithm Constants & Parameters ────────────────────────────────────
    private final long hysteresisDurationMs;  // Grace duration (e.g. 30 sec or 15 mins)
    private final double radarThresholdV;     // Voltage above which radar confirms presence (1.5V)

    // ─── Internal RAM State on the MCU ───────────────────────────────────────
    private RoomState state = RoomState.VACANT;
    private long vacancyDetectedAtMs = 0;
    private boolean doorLatched = false;
    private boolean lastDoorSample = false;

    // Output mirror registers
    private boolean lightRelayState = false;
    private boolean hvacRelayState  = false;

    // ─── Constructors ────────────────────────────────────────────────────────
    public SmartRoomFirmware(String pirPin, String doorPin, String radarPin,
                             String lightRelayPin, String hvacRelayPin,
                             int hysteresisSec, double radarThresholdV) {
        this.pirPin = pirPin;
        this.doorPin = doorPin;
        this.radarPin = radarPin;
        this.lightRelayPin = lightRelayPin;
        this.hvacRelayPin = hvacRelayPin;
        this.hysteresisDurationMs = hysteresisSec * 1000L;
        this.radarThresholdV = radarThresholdV;
    }

    public SmartRoomFirmware(int hysteresisSec) {
        this("GPIO_12", "GPIO_14", "GPIO_27", "GPIO_21", "GPIO_22", hysteresisSec, 1.5);
    }

    public SmartRoomFirmware() {
        this(30); // 30-second hysteresis default for simulation testing
    }

    @Override
    public String getName() {
        return "SmartRoom_Firmware_v2.0";
    }

    // =========================================================================
    // setup() — Executed once on boot / flashing
    // =========================================================================
    @Override
    public void setup(CpsDomain.McuHAL mcu) {
        System.out.printf("[%s] Booting on %s [%s]...%n", getName(), mcu.getBoardId(), mcu.getModel());
        
        state = RoomState.VACANT;
        vacancyDetectedAtMs = 0;
        doorLatched = false;
        lastDoorSample = false;
        lightRelayState = false;
        hvacRelayState = false;

        // Ensure safety on boot: relays start DE-ENERGIZED (OFF)
        mcu.digitalWrite(lightRelayPin, false);
        mcu.digitalWrite(hvacRelayPin, false);

        System.out.printf("[%s] Initialized: Relays OFF, State=VACANT, Hysteresis=%ds.%n",
            getName(), hysteresisDurationMs / 1000);
    }

    // =========================================================================
    // loop() — Executed continuously on every clock tick
    // =========================================================================
    @Override
    public void loop(CpsDomain.McuHAL mcu) {
        // ── 1. SENSOR SAMPLING (Hardware Input with Real-World Debounce Filtering) ──
        // Mechanical reed switch debounced for 20ms to eliminate contact chatter
        boolean doorOpen      = mcu.isHighDebounced(doorPin, 20);
        boolean pirMotion     = mcu.isHigh(pirPin);
        double  radarVoltage  = mcu.analogReadVoltage(radarPin);
        boolean radarDetected = (radarVoltage >= radarThresholdV);
        double  radarConfPct  = Math.min(100.0, (radarVoltage / 3.3) * 100.0);

        // Rising-edge latch: if door was opened, remember someone may have walked in
        if (doorOpen && !lastDoorSample) {
            doorLatched = true;
        }
        lastDoorSample = doorOpen;

        // Sensor fusion rule: presence is confirmed by micro-radar OR PIR motion
        boolean fusedPresence = pirMotion || radarDetected;

        // ── 2. STATE MACHINE & HYSTERESIS EVALUATION ─────────────────────────
        RoomState prevState = state;
        long now = mcu.millis();

        switch (state) {
            case VACANT -> {
                // If presence detected or someone entered through the door
                if (fusedPresence || doorLatched) {
                    state = RoomState.OCCUPIED;
                    doorLatched = false;
                    logTransition(mcu, prevState, state,
                        String.format("Presence detected (PIR=%b, Radar=%.2fV, DoorLatched=%b)",
                            pirMotion, radarVoltage, doorLatched));
                }
            }
            case OCCUPIED -> {
                // When active presence drops off (e.g. user sits down or goes to sleep)
                if (!fusedPresence) {
                    state = RoomState.HYSTERESIS;
                    vacancyDetectedAtMs = now;
                    logTransition(mcu, prevState, state,
                        String.format("No motion/radar detected. Starting %ds grace hysteresis window...",
                            hysteresisDurationMs / 1000));
                }
            }
            case HYSTERESIS -> {
                if (fusedPresence) {
                    // Micro-movement or breath detected during grace window!
                    state = RoomState.OCCUPIED;
                    logTransition(mcu, prevState, state,
                        "Micro-movement / presence re-detected! Grace period prevented false shutoff.");
                } else if (now - vacancyDetectedAtMs >= hysteresisDurationMs) {
                    // Grace window fully expired — room is definitely vacant
                    state = RoomState.VACANT;
                    logTransition(mcu, prevState, state,
                        "Grace timer expired with zero presence. Powering down room.");
                }
            }
        }

        // ── 3. ACTUATION LOGIC (Driving Relays) ───────────────────────────────
        // Lights stay ON during OCCUPIED and during the HYSTERESIS grace period!
        lightRelayState = (state == RoomState.OCCUPIED || state == RoomState.HYSTERESIS);
        
        // HVAC only stays active when confirmed OCCUPIED (energy-saving feature)
        hvacRelayState  = (state == RoomState.OCCUPIED);

        mcu.digitalWrite(lightRelayPin, lightRelayState);
        mcu.digitalWrite(hvacRelayPin, hvacRelayState);

        // ── 4. WIRELESS TELEMETRY (Port 1 Virtual Radio Stream) ───────────────
        long hystRemainingSec = (state == RoomState.HYSTERESIS)
            ? Math.max(0, (hysteresisDurationMs - (now - vacancyDetectedAtMs)) / 1000)
            : 0;

        String telemetryPacket = String.format(Locale.US,
            "{\"device\":\"%s\",\"model\":\"%s\",\"firmware\":\"%s\",\"state\":\"%s\"," +
            "\"pir\":%b,\"door\":%b,\"radar\":%.1f,\"radar_v\":%.3f," +
            "\"light_relay\":%b,\"hvac_relay\":%b,\"hysteresis_remaining_s\":%d," +
            "\"latency\":{\"pir_resp_ms\":180,\"radar_filter_ms\":140,\"relay_pickup_ms\":16,\"door_debounce_ms\":20}}",
            mcu.getBoardId(), mcu.getModel(), getName(), state,
            pirMotion, doorOpen, radarConfPct, radarVoltage,
            lightRelayState, hvacRelayState, hystRemainingSec
        );
        mcu.radioTransmit(telemetryPacket);
    }

    private void logTransition(CpsDomain.McuHAL mcu, RoomState from, RoomState to, String reason) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        System.out.printf("[FW %s @ %s] %s -> %s | %s%n",
            mcu.getBoardId(), time, from, to, reason);
    }

    // ─── Getters & Serialization ────────────────────────────────────────────
    public RoomState getState()         { return state; }
    public boolean   isLightRelayOn()   { return lightRelayState; }
    public boolean   isHvacRelayOn()    { return hvacRelayState; }
    public long      getHysteresisMs()  { return hysteresisDurationMs; }
    public int       getHysteresisSec() { return (int) (hysteresisDurationMs / 1000); }
    public double    getRadarThresholdV() { return radarThresholdV; }
    public String    getPirPin()        { return pirPin; }
    public String    getDoorPin()       { return doorPin; }
    public String    getRadarPin()      { return radarPin; }
    public String    getLightRelayPin() { return lightRelayPin; }
    public String    getHvacRelayPin()  { return hvacRelayPin; }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("hysteresisSec", getHysteresisSec());
        params.put("radarThresholdV", radarThresholdV);
        params.put("pirPin", pirPin);
        params.put("doorPin", doorPin);
        params.put("radarPin", radarPin);
        params.put("lightRelayPin", lightRelayPin);
        params.put("hvacRelayPin", hvacRelayPin);
        return params;
    }
}
