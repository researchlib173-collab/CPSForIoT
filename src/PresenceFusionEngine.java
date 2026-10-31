import java.util.concurrent.*;
import java.util.*;

// ============================================================================
// PRESENCE FUSION ENGINE — Stateful hysteresis room occupancy state machine
// ============================================================================
public class PresenceFusionEngine {

    // ─── Room occupancy states ────────────────────────────────────────────────
    public enum RoomState { VACANT, OCCUPIED, HYSTERESIS }

    // ─── Listener interface (used by GUI to react to state changes) ───────────
    public interface StateListener {
        void onStateChanged(RoomState newState, String reason);
    }

    // ─── Configuration ────────────────────────────────────────────────────────
    private final long hysteresisMs;          // Grace period before lights off
    private final double radarThresholdPct;   // % confidence to count as present

    // ─── Live state ──────────────────────────────────────────────────────────
    private volatile RoomState currentState = RoomState.VACANT;
    private volatile long       vacancyStartMs = 0;          // When last became "not detected"
    private volatile boolean    doorWasOpened  = false;       // Entry latch

    // ─── Sensor inputs (set by the tick loop or GUI) ─────────────────────────
    private volatile boolean pirMotion    = false;
    private volatile boolean doorOpen     = false;
    private volatile boolean radarPresent = false;
    private volatile double  radarConf    = 0.0;

    // ─── Relay outputs (computed every tick) ─────────────────────────────────
    private volatile boolean lightRelay = false;
    private volatile boolean hvacRelay  = false;

    // ─── Event log (thread-safe ring buffer) ─────────────────────────────────
    private final LinkedList<String> eventLog = new LinkedList<>();
    private static final int MAX_LOG = 200;

    // ─── Listeners ───────────────────────────────────────────────────────────
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    // ─── Constructor ─────────────────────────────────────────────────────────
    /**
     * @param hysteresisSeconds  Grace period before declaring vacancy (default 900 = 15 min)
     * @param radarThresholdPct  Radar confidence threshold 0–100 % (default 60)
     */
    public PresenceFusionEngine(int hysteresisSeconds, double radarThresholdPct) {
        this.hysteresisMs      = hysteresisSeconds * 1000L;
        this.radarThresholdPct = radarThresholdPct;
    }

    /** Default: 15-minute hysteresis, 60 % radar confidence. */
    public PresenceFusionEngine() { this(900, 60.0); }

    // ─── Sensor setters (called by CLI or GUI) ────────────────────────────────
    public synchronized void setPir(boolean motion)         { this.pirMotion    = motion; }
    public synchronized void setDoorOpen(boolean open) {
        if (open && !doorWasOpened) doorWasOpened = true;   // Latch entry event
        this.doorOpen = open;
    }
    public synchronized void setRadar(boolean present, double confPct) {
        this.radarPresent = present;
        this.radarConf    = confPct;
    }

    // ─── Main evaluation tick (called from simulation scheduler) ─────────────
    public synchronized void evaluate() {
        boolean fusedPresence = pirMotion || (radarPresent && radarConf >= radarThresholdPct);

        switch (currentState) {
            case VACANT -> {
                if (fusedPresence || doorWasOpened) {
                    transition(RoomState.OCCUPIED,
                        fusedPresence ? "Presence detected (PIR=" + pirMotion +
                                        ", Radar=" + String.format("%.0f", radarConf) + "%)"
                                      : "Door entry event latched");
                    doorWasOpened = false;
                }
            }
            case OCCUPIED -> {
                if (!fusedPresence) {
                    // Start hysteresis countdown instead of immediate turn-off
                    vacancyStartMs = System.currentTimeMillis();
                    transition(RoomState.HYSTERESIS,
                        "No presence detected — starting " + (hysteresisMs / 1000 / 60) + "-min grace timer");
                }
            }
            case HYSTERESIS -> {
                if (fusedPresence) {
                    // Presence detected again — cancel hysteresis
                    transition(RoomState.OCCUPIED, "Presence re-detected during grace period — resetting");
                } else if (System.currentTimeMillis() - vacancyStartMs >= hysteresisMs) {
                    transition(RoomState.VACANT, "Grace period expired — room confirmed vacant");
                }
            }
        }

        // ── Compute relay outputs based on state ──────────────────────────────
        lightRelay = (currentState == RoomState.OCCUPIED || currentState == RoomState.HYSTERESIS);
        hvacRelay  = (currentState == RoomState.OCCUPIED);  // HVAC only when definitely occupied
    }

    private void transition(RoomState next, String reason) {
        RoomState prev = currentState;
        currentState = next;
        String entry = String.format("[%s] %s → %s | %s",
            new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()), prev, next, reason);
        synchronized (eventLog) {
            eventLog.addFirst(entry);
            if (eventLog.size() > MAX_LOG) eventLog.removeLast();
        }
        System.out.println("[FUSION] " + entry);
        for (StateListener l : listeners) l.onStateChanged(next, reason);
    }

    // ─── Build the Runnable firmware to inject into an ESP32 board ────────────
    /**
     * Returns a Runnable compatible with board.setExternalLogic().
     * The returned logic reads pin voltages → updates fusion engine → drives relay pins.
     */
    public Runnable buildFirmwareFor(CpsDomain.Board board) {
        return () -> {
            // Read sensor pins
            double pirV   = board.getPin("GPIO_12") != null ? board.getPin("GPIO_12").voltage : 0;
            double doorV  = board.getPin("GPIO_14") != null ? board.getPin("GPIO_14").voltage : 0;
            double radarV = board.getPin("GPIO_27") != null ? board.getPin("GPIO_27").voltage : 0;

            setPir(pirV > 2.0);
            setDoorOpen(doorV > 2.0);
            double confPct = (radarV / 3.3) * 100.0;
            setRadar(radarV > 1.5, confPct);

            evaluate();

            // Drive relay output pins
            if (board.getPin("GPIO_21") != null)
                board.getPin("GPIO_21").setVoltage(lightRelay ? 3.3 : 0.0);
            if (board.getPin("GPIO_22") != null)
                board.getPin("GPIO_22").setVoltage(hvacRelay  ? 3.3 : 0.0);

            // Emit fused telemetry JSON with physical hardware latency profiles
            if (board.socketBridge != null) {
                String payload = String.format(Locale.US,
                    "{\"device\":\"%s\",\"state\":\"%s\"," +
                    "\"pir\":%b,\"door\":%b,\"radar\":%.1f," +
                    "\"light_relay\":%b,\"hvac_relay\":%b," +
                    "\"hysteresis_remaining_s\":%d," +
                    "\"latency\":{\"pir_resp_ms\":180,\"radar_filter_ms\":140,\"relay_pickup_ms\":16,\"door_debounce_ms\":20}}",
                    board.id, currentState,
                    pirMotion, doorOpen, radarConf,
                    lightRelay, hvacRelay,
                    currentState == RoomState.HYSTERESIS
                        ? Math.max(0, (hysteresisMs - (System.currentTimeMillis() - vacancyStartMs)) / 1000)
                        : 0
                );
                board.socketBridge.transmit(payload);
            }
        };
    }

    // ─── Getters for GUI ─────────────────────────────────────────────────────
    public RoomState getCurrentState()    { return currentState; }
    public boolean   isLightRelayOn()     { return lightRelay; }
    public boolean   isHvacRelayOn()      { return hvacRelay; }
    public boolean   isPirMotion()        { return pirMotion; }
    public boolean   isDoorOpen()         { return doorOpen; }
    public double    getRadarConf()       { return radarConf; }
    public long      getHysteresisMs()    { return hysteresisMs; }
    public long      getVacancyStartMs()  { return vacancyStartMs; }

    public List<String> getEventLog() {
        synchronized (eventLog) { return new ArrayList<>(eventLog); }
    }

    public void addStateListener(StateListener l) { listeners.add(l); }
}
