import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;

// ============================================================================
// PART 1: CYBER-PHYSICAL DOMAIN CLASSES
// ============================================================================
public class CpsDomain {

    public enum PinMode { DIGITAL_IN, DIGITAL_OUT, ANALOG_IN, PWM_OUT }
    public enum SignalState { LOW, HIGH }

    // --- 1. THE BASE PROGRAMMABLE OBJECT ---
    public static abstract class SimulationObject {
        protected String id;
        protected Runnable baseLogic = () -> {};
        protected Runnable externalOverrideLogic = null;
        protected Map<String, Object> properties = new ConcurrentHashMap<>();

        public SimulationObject(String id) { this.id = id; }
        public String getId() { return id; }
        public void setProperty(String key, Object value) { properties.put(key, value); }
        public Object getProperty(String key, Object def) { return properties.getOrDefault(key, def); }
        public double getDoubleProperty(String key, double def) {
            Object v = properties.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            return def;
        }
        public void setExternalLogic(Runnable logic) { this.externalOverrideLogic = logic; }

        public void tickLogic() {
            if (externalOverrideLogic != null) externalOverrideLogic.run();
            else baseLogic.run();
        }
    }

    // --- 2. PIN & WIRE (DBMS NETLIST) ---
    public static class Pin {
        public String pinId;
        public PinMode mode;
        public double voltage = 0.0;
        public SignalState digitalState = SignalState.LOW;
        public List<Wire> connectedWires = new ArrayList<>();
        /** Optional listener called whenever voltage changes */
        public interface VoltageListener { void onVoltageChanged(double newVoltage); }
        private List<VoltageListener> listeners = new ArrayList<>();

        public Pin(String pinId, PinMode mode) { this.pinId = pinId; this.mode = mode; }
        public void attachWire(Wire w) { connectedWires.add(w); }
        public void addVoltageListener(VoltageListener l) { listeners.add(l); }

        public void setVoltage(double v) {
            this.voltage = v;
            this.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            for (Wire w : connectedWires) w.propagate(this, v);
            for (VoltageListener l : listeners) l.onVoltageChanged(v);
        }
    }

    public static class Wire {
        public String wireName;
        public Pin pinA, pinB;
        public Wire(String name, Pin a, Pin b) { this.wireName = name; this.pinA = a; this.pinB = b; }
        public void propagate(Pin source, double v) {
            if (source == pinA && pinB != null) { pinB.voltage = v; pinB.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW; }
            else if (source == pinB && pinA != null) { pinA.voltage = v; pinA.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW; }
        }
    }

    // --- 3. HARDWARE ENTITIES (Components & Boards) ---
    public static abstract class ElectronicComponent extends SimulationObject {
        protected String type;
        public ElectronicComponent(String id, String type) { super(id); this.type = type; }
        public String getType() { return type; }
    }

    public static class Board extends SimulationObject {
        public String model;
        public Map<String, Pin> pins = new LinkedHashMap<>();
        public Map<String, ElectronicComponent> components = new LinkedHashMap<>();
        public ExternalAppletBridge socketBridge = null;
        /** Listeners notified every tick — used by the GUI dashboard */
        public interface TickListener { void onTick(Board board); }
        private List<TickListener> tickListeners = new ArrayList<>();

        public Board(String id, String model) { super(id); this.model = model; }
        public void addPin(String pinId, PinMode mode) { pins.put(pinId, new Pin(pinId, mode)); }
        public Pin getPin(String id) { return pins.get(id); }
        public void addComponent(ElectronicComponent c) { components.put(c.id, c); }
        public void addTickListener(TickListener l) { tickListeners.add(l); }

        public void enableSocketBridge(int port) {
            this.socketBridge = new ExternalAppletBridge(port);
        }

        @Override
        public void tickLogic() {
            super.tickLogic();
            for (ElectronicComponent c : components.values()) c.tickLogic();
            for (TickListener l : tickListeners) l.onTick(this);
        }
    }

    // --- 4. NETWORK LAYER (Virtual Wi-Fi → Localhost TCP Socket) ---
    public static class ExternalAppletBridge {
        private final int port;
        private PrintWriter out;
        private BufferedReader in;
        private volatile boolean connected = false;
        private final List<MessageListener> listeners = new ArrayList<>();

        public interface MessageListener { void onMessage(String msg); }

        public ExternalAppletBridge(int port) {
            this.port = port;
            new Thread(this::startServer, "Bridge-" + port).start();
        }

        private void startServer() {
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.println("[LINK] Bridge active on port " + port + ". Waiting for client...");
                Socket client = server.accept();
                out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true);
                in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
                connected = true;
                System.out.println("[LINK] Client connected on port " + port);
                // Read incoming messages in background
                String line;
                while ((line = in.readLine()) != null) {
                    final String msg = line;
                    for (MessageListener l : listeners) l.onMessage(msg);
                }
            } catch (IOException e) {
                System.out.println("[LINK ERROR] Port " + port + ": " + e.getMessage());
            }
        }

        public void transmit(String data) { if (out != null && connected) out.println(data); }
        public boolean isConnected() { return connected; }
        public void addMessageListener(MessageListener l) { listeners.add(l); }
    }
}

