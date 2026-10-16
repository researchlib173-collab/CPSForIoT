import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;

// ============================================================================
// PART 1: CYBER-PHYSICAL DOMAIN CLASSES (Two-Port Cyber-Physical Architecture)
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
            if (source == pinA && pinB != null) {
                pinB.voltage = v;
                pinB.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            } else if (source == pinB && pinA != null) {
                pinA.voltage = v;
                pinA.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            }
        }
    }

    // --- 3. HARDWARE ENTITIES (Components & Boards) ---
    public static abstract class ElectronicComponent extends SimulationObject {
        protected String type;
        public ElectronicComponent(String id, String type) { super(id); this.type = type; }
        public String getType() { return type; }
    }

    // --- HARDWARE ABSTRACTION LAYER (HAL) FOR ON-BOARD FIRMWARE ---
    public interface Firmware {
        String getName();
        void setup(McuHAL mcu);
        void loop(McuHAL mcu);
        default Map<String, Object> getParameters() { return Collections.emptyMap(); }
    }

    public static class McuHAL {
        private final Board board;
        public McuHAL(Board board) { this.board = board; }

        public String getBoardId() { return board.getId(); }
        public String getModel()   { return board.model; }

        public SignalState digitalRead(String pinId) {
            Pin p = board.getPin(pinId);
            return p != null ? p.digitalState : SignalState.LOW;
        }

        public boolean isHigh(String pinId) {
            return digitalRead(pinId) == SignalState.HIGH;
        }

        public boolean isLow(String pinId) {
            return digitalRead(pinId) == SignalState.LOW;
        }

        public void digitalWrite(String pinId, SignalState state) {
            Pin p = board.getPin(pinId);
            if (p != null) p.setVoltage(state == SignalState.HIGH ? 3.3 : 0.0);
        }

        public void digitalWrite(String pinId, boolean high) {
            digitalWrite(pinId, high ? SignalState.HIGH : SignalState.LOW);
        }

        public double analogReadVoltage(String pinId) {
            Pin p = board.getPin(pinId);
            return p != null ? p.voltage : 0.0;
        }

        public int analogRead10Bit(String pinId) {
            double v = analogReadVoltage(pinId);
            return (int) Math.round((v / 3.3) * 1023.0);
        }

        public void analogWriteVoltage(String pinId, double voltage) {
            Pin p = board.getPin(pinId);
            if (p != null) p.setVoltage(voltage);
        }

        public void radioTransmit(String jsonPayload) {
            if (board.radioBridge != null) {
                board.radioBridge.transmit(jsonPayload);
            }
        }

        public long millis() {
            return System.currentTimeMillis();
        }

        public void setProperty(String key, Object val) { board.setProperty(key, val); }
        public Object getProperty(String key, Object def) { return board.getProperty(key, def); }
    }

    public static class Board extends SimulationObject {
        public String model;
        public Map<String, Pin> pins = new LinkedHashMap<>();
        public Map<String, ElectronicComponent> components = new LinkedHashMap<>();
        
        // On-board flashed firmware & HAL
        private Firmware flashedFirmware = null;
        private final McuHAL hal = new McuHAL(this);

        // PORT 1: Virtual Wireless Radio (Wi-Fi / BLE Telemetry Bridge)
        public ExternalAppletBridge radioBridge = null;
        public ExternalAppletBridge socketBridge = null; // backward compatibility alias

        public interface TickListener { void onTick(Board board); }
        private List<TickListener> tickListeners = new ArrayList<>();

        public Board(String id, String model) { super(id); this.model = model; }
        public void addPin(String pinId, PinMode mode) { pins.put(pinId, new Pin(pinId, mode)); }
        public Pin getPin(String id) { return pins.get(id); }
        public void addComponent(ElectronicComponent c) { components.put(c.id, c); }
        public void addTickListener(TickListener l) { tickListeners.add(l); }

        public void flashFirmware(Firmware fw) {
            this.flashedFirmware = fw;
            if (fw != null) {
                System.out.printf("[FLASH] Flashing %s onto board '%s' [%s]...%n", fw.getName(), id, model);
                fw.setup(hal);
            }
        }

        public Firmware getFlashedFirmware() { return flashedFirmware; }
        public McuHAL getHal() { return hal; }

        public void enableRadioBridge(int port) {
            this.radioBridge = ExternalAppletBridge.getOrCreate(port, "RadioBridge-" + id);
            this.socketBridge = this.radioBridge;
        }

        // Backward compatibility
        public void enableSocketBridge(int port) {
            enableRadioBridge(port);
        }

        @Override
        public void tickLogic() {
            // Priority 1: Flashed on-board Firmware
            if (flashedFirmware != null) {
                flashedFirmware.loop(hal);
            } else if (externalOverrideLogic != null) {
                externalOverrideLogic.run();
            } else {
                baseLogic.run();
            }
            for (ElectronicComponent c : components.values()) c.tickLogic();
            for (TickListener l : tickListeners) l.onTick(this);
        }
    }

    // --- 4. PORT 1: WIRELESS IOT BRIDGE (Wi-Fi / Bluetooth Radio) ---
    public static class ExternalAppletBridge {
        private static final Map<Integer, ExternalAppletBridge> activeBridges = new ConcurrentHashMap<>();

        public static synchronized ExternalAppletBridge getOrCreate(int port, String bridgeName) {
            return activeBridges.computeIfAbsent(port, p -> new ExternalAppletBridge(p, bridgeName));
        }

        private final int port;
        private final String bridgeName;
        private final List<PrintWriter> activeClients = new CopyOnWriteArrayList<>();
        private final List<MessageListener> listeners = new ArrayList<>();

        public interface MessageListener { void onMessage(String msg); }

        public ExternalAppletBridge(int port, String bridgeName) {
            this.port = port;
            this.bridgeName = bridgeName;
            Thread t = new Thread(this::startServer, bridgeName + "-" + port);
            t.setDaemon(true);
            t.start();
        }

        public ExternalAppletBridge(int port) {
            this(port, "Bridge");
        }

        private void startServer() {
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.printf("[PORT 1 - WIRELESS] %s active on port %d. Waiting for telemetry client...%n", bridgeName, port);
                while (true) {
                    Socket client = server.accept();
                    Thread ct = new Thread(() -> handleClient(client), "RadioClient-" + client.getRemoteSocketAddress());
                    ct.setDaemon(true);
                    ct.start();
                }
            } catch (IOException e) {
                System.out.printf("[PORT 1 ERROR] %s (port %d): %s%n", bridgeName, port, e.getMessage());
            }
        }

        private void handleClient(Socket client) {
            System.out.printf("[PORT 1 - WIRELESS] Telemetry client connected from %s%n", client.getRemoteSocketAddress());
            PrintWriter out = null;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                 PrintWriter pw = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true)) {
                out = pw;
                activeClients.add(out);
                String line;
                while ((line = in.readLine()) != null) {
                    for (MessageListener l : listeners) l.onMessage(line);
                }
            } catch (IOException ignored) {
            } finally {
                if (out != null) activeClients.remove(out);
                System.out.printf("[PORT 1 - WIRELESS] Telemetry client disconnected from %s%n", client.getRemoteSocketAddress());
            }
        }

        public void transmit(String data) {
            for (PrintWriter client : activeClients) {
                try {
                    client.println(data);
                } catch (Exception ignored) {}
            }
        }

        public boolean isConnected() { return !activeClients.isEmpty(); }
        public int getPort() { return port; }
        public void addMessageListener(MessageListener l) { listeners.add(l); }
    }

    // --- 5. PORT 2: PHYSICAL TERMINAL BUS BRIDGE (Workspace Pin Netlist Tap) ---
    public static class PhysicalTerminalBusBridge {
        private static final Map<Integer, PhysicalTerminalBusBridge> activeBridges = new ConcurrentHashMap<>();

        public static synchronized PhysicalTerminalBusBridge getOrCreate(int port, Map<String, Board> boards, List<Wire> wires) {
            PhysicalTerminalBusBridge existing = activeBridges.get(port);
            if (existing != null) {
                existing.updateContext(boards, wires);
                return existing;
            }
            PhysicalTerminalBusBridge created = new PhysicalTerminalBusBridge(port, boards, wires);
            activeBridges.put(port, created);
            return created;
        }

        private final int port;
        private Map<String, Board> boards;
        private List<Wire> wires;
        private final List<PrintWriter> activeClients = new CopyOnWriteArrayList<>();
        private volatile boolean isRunning = true;
        private Runnable onPinChangeCallback = null;
        public void setOnPinChangeCallback(Runnable cb) { this.onPinChangeCallback = cb; }

        public PhysicalTerminalBusBridge(int port, Map<String, Board> boards, List<Wire> wires) {
            this.port = port;
            this.boards = boards;
            this.wires = wires;
            Thread t = new Thread(this::startServer, "TerminalBusBridge-" + port);
            t.setDaemon(true);
            t.start();
        }

        public int getPort() { return port; }

        public void updateContext(Map<String, Board> newBoards, List<Wire> newWires) {
            this.boards = newBoards;
            this.wires = newWires;
        }

        private void startServer() {
            try (ServerSocket server = new ServerSocket(port)) {
                System.out.printf("[PORT 2 - TERMINALS] Physical Terminal Bus open on port %d! Waiting for DAQ/testbed...%n", port);
                while (isRunning) {
                    Socket client = server.accept();
                    Thread ct = new Thread(() -> handleClient(client), "TerminalClient-" + client.getRemoteSocketAddress());
                    ct.setDaemon(true);
                    ct.start();
                }
            } catch (IOException e) {
                System.out.printf("[PORT 2 ERROR] Terminal bridge (port %d): %s%n", port, e.getMessage());
            }
        }

        private void handleClient(Socket client) {
            System.out.printf("[PORT 2 - TERMINALS] Physical I/O Testbed tapped into terminal bus from %s%n", client.getRemoteSocketAddress());
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true)) {
                
                activeClients.add(out);
                out.println("{\"type\":\"TERMINAL_BUS_READY\",\"status\":\"CONNECTED\",\"port\":" + port + "}");
                
                String line;
                while ((line = in.readLine()) != null) {
                    processTerminalCommand(line.trim(), out);
                }
            } catch (IOException ignored) {
            } finally {
                System.out.println("[PORT 2 - TERMINALS] Testbed tap disconnected.");
            }
        }

        /**
         * Supported commands on Port 2:
         * 1. SET <board> <pin> <voltage>   -> e.g. "SET PIR_MotionSensor DATA 3.3"
         * 2. READ <board> <pin>            -> e.g. "READ LightRelay IN_DC"
         * 3. SNAPSHOT                      -> dumps all pin voltages in the workspace netlist
         * 4. JSON: {"cmd":"SET_PIN","board":"PIR_MotionSensor","pin":"DATA","voltage":3.3}
         */
        public void processTerminalCommand(String cmd, PrintWriter responder) {
            if (cmd.isEmpty()) return;

            // Handle JSON commands from GUIs
            if (cmd.startsWith("{") && cmd.endsWith("}")) {
                handleJsonTerminalCommand(cmd, responder);
                return;
            }

            // Handle plain-text terminal commands
            String[] tokens = cmd.split("\\s+");
            String action = tokens[0].toUpperCase();

            switch (action) {
                case "SET" -> {
                    // SET <board> <pin> <voltage>
                    if (tokens.length >= 4) {
                        String bName = tokens[1];
                        String pName = tokens[2];
                        try {
                            double v = Double.parseDouble(tokens[3]);
                            Board b = boards.get(bName);
                            if (b != null) {
                                Pin p = b.getPin(pName);
                                if (p != null) {
                                    p.setVoltage(v);
                                    if (onPinChangeCallback != null) {
                                        try { onPinChangeCallback.run(); } catch (Exception ignored) {}
                                    }
                                    responder.printf("{\"status\":\"OK\",\"action\":\"SET\",\"terminal\":\"%s.%s\",\"voltage\":%.3f,\"state\":\"%s\"}%n",
                                        bName, pName, v, p.digitalState);
                                    broadcastTerminalEvent(String.format("{\"event\":\"PIN_CHANGED\",\"terminal\":\"%s.%s\",\"voltage\":%.3f,\"state\":\"%s\"}",
                                        bName, pName, v, p.digitalState), responder);
                                } else responder.printf("{\"status\":\"ERR\",\"msg\":\"Pin not found: %s\"}%n", pName);
                            } else responder.printf("{\"status\":\"ERR\",\"msg\":\"Board not found: %s\"}%n", bName);
                        } catch (NumberFormatException e) {
                            responder.println("{\"status\":\"ERR\",\"msg\":\"Invalid voltage value\"}");
                        }
                    }
                }
                case "READ" -> {
                    // READ <board> <pin>
                    if (tokens.length >= 3) {
                        String bName = tokens[1];
                        String pName = tokens[2];
                        Board b = boards.get(bName);
                        if (b != null) {
                            Pin p = b.getPin(pName);
                            if (p != null) {
                                responder.printf("{\"status\":\"OK\",\"action\":\"READ\",\"terminal\":\"%s.%s\",\"voltage\":%.3f,\"state\":\"%s\"}%n",
                                    bName, pName, p.voltage, p.digitalState);
                            } else responder.printf("{\"status\":\"ERR\",\"msg\":\"Pin not found: %s\"}%n", pName);
                        } else responder.printf("{\"status\":\"ERR\",\"msg\":\"Board not found: %s\"}%n", bName);
                    }
                }
                case "SNAPSHOT" -> {
                    responder.println(getNetlistSnapshotJson());
                }
                default -> responder.println("{\"status\":\"ERR\",\"msg\":\"Unknown terminal command\"}");
            }
        }

        private void handleJsonTerminalCommand(String json, PrintWriter responder) {
            // Check for SET_PIN
            if (json.contains("\"cmd\":\"SET_PIN\"") || json.contains("\"SET_PIN\"")) {
                String bName = extractJsonVal(json, "board");
                String pName = extractJsonVal(json, "pin");
                double v = extractJsonDouble(json, "voltage", 0.0);
                Board b = boards.get(bName);
                if (b != null) {
                    Pin p = b.getPin(pName);
                    if (p != null) {
                        p.setVoltage(v);
                        if (onPinChangeCallback != null) {
                            try { onPinChangeCallback.run(); } catch (Exception ignored) {}
                        }
                        responder.printf("{\"status\":\"OK\",\"action\":\"SET\",\"terminal\":\"%s.%s\",\"voltage\":%.3f}%n", bName, pName, v);
                        broadcastTerminalEvent(String.format("{\"event\":\"PIN_CHANGED\",\"terminal\":\"%s.%s\",\"voltage\":%.3f,\"state\":\"%s\"}",
                            bName, pName, v, p.digitalState), responder);
                        return;
                    }
                }
                responder.println("{\"status\":\"ERR\",\"msg\":\"Board or Pin not found\"}");
            } else if (json.contains("\"SNAPSHOT\"")) {
                responder.println(getNetlistSnapshotJson());
            }
        }

        public String getNetlistSnapshotJson() {
            StringBuilder sb = new StringBuilder("{\"type\":\"NETLIST_SNAPSHOT\",\"terminals\":{");
            boolean firstB = true;
            for (Map.Entry<String, Board> be : boards.entrySet()) {
                String bName = be.getKey();
                Board b = be.getValue();
                for (Map.Entry<String, Pin> pe : b.pins.entrySet()) {
                    if (!firstB) sb.append(",");
                    firstB = false;
                    sb.append(String.format("\"%s.%s\":{\"voltage\":%.3f,\"state\":\"%s\",\"mode\":\"%s\"}",
                        bName, pe.getKey(), pe.getValue().voltage, pe.getValue().digitalState, pe.getValue().mode));
                }
            }
            sb.append("}}");
            return sb.toString();
        }

        public void broadcastTerminalEvent(String jsonMsg, PrintWriter source) {
            for (PrintWriter client : activeClients) {
                if (client != source) {
                    client.println(jsonMsg);
                }
            }
        }

        public void broadcastNetlistTick() {
            String snapshot = getNetlistSnapshotJson();
            for (PrintWriter client : activeClients) {
                client.println(snapshot);
            }
        }

        private String extractJsonVal(String j, String k) {
            String s = "\"" + k + "\":\""; int i = j.indexOf(s);
            if (i < 0) return ""; i += s.length(); int e = j.indexOf('"', i);
            return e < 0 ? "" : j.substring(i, e);
        }

        private double extractJsonDouble(String j, String k, double def) {
            String s = "\"" + k + "\":"; int i = j.indexOf(s);
            if (i < 0) return def; i += s.length(); int e = i;
            while (e < j.length() && (Character.isDigit(j.charAt(e)) || j.charAt(e) == '.' || j.charAt(e) == '-')) e++;
            try { return Double.parseDouble(j.substring(i, e)); } catch (Exception x) { return def; }
        }
    }
}
