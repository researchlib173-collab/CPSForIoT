import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;
import java.awt.geom.Point2D;

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
        public List<Wire> connectedWires = new CopyOnWriteArrayList<>();
        public Board parentBoard = null;
        public String boardId = null;

        public interface VoltageListener { void onVoltageChanged(double newVoltage); }
        private List<VoltageListener> listeners = new CopyOnWriteArrayList<>();

        public Pin(String pinId, PinMode mode) { this.pinId = pinId; this.mode = mode; }
        public Pin(String pinId, PinMode mode, Board parentBoard) {
            this.pinId = pinId;
            this.mode = mode;
            this.parentBoard = parentBoard;
            if (parentBoard != null) this.boardId = parentBoard.getId();
        }

        public void attachWire(Wire w) {
            if (w != null && !connectedWires.contains(w)) {
                connectedWires.add(w);
            }
        }

        public void detachWire(Wire w) {
            connectedWires.remove(w);
        }

        public void addVoltageListener(VoltageListener l) { listeners.add(l); }

        public void setVoltage(double v) {
            this.voltage = v;
            this.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            for (Wire w : connectedWires) w.propagate(this, v);
            for (VoltageListener l : listeners) l.onVoltageChanged(v);
        }

        public Board getParentBoard() { return parentBoard; }
        public String getBoardId() {
            return (parentBoard != null) ? parentBoard.getId() : (boardId != null ? boardId : "UNKNOWN");
        }
    }

    public static class AmbiguousWireConnectionException extends RuntimeException {
        public AmbiguousWireConnectionException(String message) {
            super(message);
        }
    }

    public static class Wire {
        public String wireName;
        public Pin pinA, pinB;
        public List<Wire> connectedWires = new CopyOnWriteArrayList<>();

        public Wire(String name, Pin a, Pin b) {
            this.wireName = (name != null && !name.isEmpty()) ? name : "W_" + System.currentTimeMillis();
            this.pinA = a;
            this.pinB = b;
            if (a != null) a.attachWire(this);
            if (b != null) b.attachWire(this);
        }

        public Wire(String name, Pin a) {
            this(name, a, null);
        }

        public Wire(String name) {
            this(name, null, null);
        }

        public Wire(Pin a, Pin b) {
            this("W_" + System.currentTimeMillis(), a, b);
        }

        // ─── 1. WIRE-TO-WIRE DIRECT CONNECTION (Syntax: wireA.connect(wireB)) ──────────

        /**
         * Connects this wire directly to another wire, establishing an electrical junction / tap.
         * Bidirectional: both wires will reference each other and share electrical potential.
         * Returns this wire for fluent chaining.
         */
        public Wire connectToWire(Wire otherWire) {
            if (otherWire == null) throw new IllegalArgumentException("Target wire cannot be null");
            if (otherWire == this) return this;

            if (!this.connectedWires.contains(otherWire)) {
                this.connectedWires.add(otherWire);
            }
            if (!otherWire.connectedWires.contains(this)) {
                otherWire.connectedWires.add(this);
            }

            // Immediately synchronize electrical potentials across the entire junction net
            syncNet();
            return this;
        }

        /** Fluent alias: wireA.connect(wireB) */
        public Wire connect(Wire otherWire) {
            return connectToWire(otherWire);
        }

        /** Fluent alias: wireA.tap(wireB) — Taps this wire into target wire */
        public Wire tap(Wire targetWire) {
            return connectToWire(targetWire);
        }

        /** Fluent alias: wireA.splice(wireB) — Slices / merges with target wire */
        public Wire splice(Wire targetWire) {
            return connectToWire(targetWire);
        }

        /** Fluent alias: wireA.join(wireB) */
        public Wire join(Wire targetWire) {
            return connectToWire(targetWire);
        }

        /** Fluent alias: wireA.to(wireB) — Natural language wire connection */
        public Wire to(Wire otherWire) {
            return connectToWire(otherWire);
        }

        /** Fluent alias: wireA.branchFrom(wireB) */
        public Wire branchFrom(Wire trunkWire) {
            return connectToWire(trunkWire);
        }

        /** Fluent alias: wireA.branchTo(wireB) */
        public Wire branchTo(Wire branchWire) {
            return connectToWire(branchWire);
        }

        /** Static fluent connector: Wire.connect(wireA, wireB) */
        public static Wire connect(Wire a, Wire b) {
            if (a == null) throw new IllegalArgumentException("Wire a cannot be null");
            return a.connectToWire(b);
        }

        /** Static fluent tap: Wire.tap(sourceWire, newWireName, tapPin) */
        public static Wire tap(Wire sourceWire, String newWireName, Pin tapPin) {
            Wire tapWire = new Wire(newWireName, tapPin, null);
            tapWire.connectToWire(sourceWire);
            return tapWire;
        }

        // ─── 2. WIRE-TO-COMPONENT CONNECTION WITH UNIQUENESS ENFORCEMENT ────────

        /**
         * Discovers all wires attached to a target component (Board, Component, or Component ID).
         */
        public static Set<Wire> findWiresForComponent(Object componentOrId, Collection<Wire> candidateWires, Map<String, Board> boards) {
            Set<Wire> result = new LinkedHashSet<>();
            if (componentOrId == null) return result;

            Board targetBoard = null;
            String targetId = null;

            if (componentOrId instanceof Board b) {
                targetBoard = b;
                targetId = b.getId();
            } else if (componentOrId instanceof ElectronicComponent ec) {
                targetId = ec.getId();
            } else if (componentOrId instanceof String s) {
                targetId = s;
                if (boards != null && boards.containsKey(s)) {
                    targetBoard = boards.get(s);
                }
            }

            // 1. If targetBoard is known, inspect all its pins directly
            if (targetBoard != null) {
                for (Pin p : targetBoard.pins.values()) {
                    result.addAll(p.connectedWires);
                }
            }

            // 2. Also search candidateWires if available
            if (candidateWires != null && targetId != null) {
                for (Wire w : candidateWires) {
                    if (w == null) continue;
                    if (w.pinA != null) {
                        String bId = w.pinA.getBoardId();
                        if (targetId.equalsIgnoreCase(bId)) result.add(w);
                    }
                    if (w.pinB != null) {
                        String bId = w.pinB.getBoardId();
                        if (targetId.equalsIgnoreCase(bId)) result.add(w);
                    }
                    if (targetBoard != null) {
                        if (targetBoard.pins.containsValue(w.pinA) || targetBoard.pins.containsValue(w.pinB)) {
                            result.add(w);
                        }
                    }
                }
            }

            return result;
        }

        /**
         * Connects this wire to another wire by specifying the target component to which that wire is connected.
         * Enforces the UNIQUENESS RULE:
         * - If 0 wires are connected to the component: throws AmbiguousWireConnectionException.
         * - If exactly 1 wire is connected to the component: connects to that unique wire [one to one] or [one to many]!
         * - If > 1 wires are connected to the component: throws AmbiguousWireConnectionException (connection is ambiguous).
         *
         * @param componentOrId The target Board, Component, or Component ID
         * @param candidateWires Collection of wires in the workspace (or null to use component's own pin wires)
         * @param boards Map of workspace boards (or null)
         * @return this Wire for chaining
         * @throws AmbiguousWireConnectionException if the connection is not strictly unique (0 or >1)
         */
        public Wire connectToComponent(Object componentOrId, Collection<Wire> candidateWires, Map<String, Board> boards) {
            Set<Wire> matchedWires = findWiresForComponent(componentOrId, candidateWires, boards);

            // Exclude 'this' wire if it was already connected to the component
            matchedWires.remove(this);

            String compName = (componentOrId instanceof Board b) ? b.getId()
                            : (componentOrId instanceof ElectronicComponent ec) ? ec.getId()
                            : String.valueOf(componentOrId);

            if (matchedWires.isEmpty()) {
                throw new AmbiguousWireConnectionException(String.format(
                    "Cannot connect wire '%s' to component '%s': No wires are currently connected to this component. " +
                    "A target wire must already exist on the component to establish a wire-to-wire connection.",
                    this.wireName, compName
                ));
            }

            if (matchedWires.size() > 1) {
                List<String> wireNames = new ArrayList<>();
                for (Wire mw : matchedWires) wireNames.add(mw.wireName);
                throw new AmbiguousWireConnectionException(String.format(
                    "Cannot connect wire '%s' to component '%s': Connection is AMBIGUOUS. " +
                    "Component has %d connected wires: %s. " +
                    "A wire can connect to another wire by component reference IF AND ONLY IF that connection is UNIQUE [one to many] or [one to one]. " +
                    "Please specify the exact wire name (e.g. wire.connectToWire(\"%s\")) or pin instead.",
                    this.wireName, compName, matchedWires.size(), wireNames, wireNames.get(0)
                ));
            }

            // Exactly ONE unique wire found! [one to one] or [one to many]
            Wire targetWire = matchedWires.iterator().next();
            return connectToWire(targetWire);
        }

        public Wire connectToComponent(Board board) {
            return connectToComponent(board, null, null);
        }

        public Wire connectToComponent(Object componentOrId, Collection<Wire> candidateWires) {
            return connectToComponent(componentOrId, candidateWires, null);
        }

        public Wire connectToWireOf(Board board) {
            return connectToComponent(board, null, null);
        }

        public Wire connectToWireOf(Object componentOrId, Collection<Wire> candidateWires) {
            return connectToComponent(componentOrId, candidateWires, null);
        }

        public Wire connect(Object target, Collection<Wire> candidateWires) {
            if (target instanceof Wire w) {
                return connectToWire(w);
            }
            return connectToComponent(target, candidateWires, null);
        }

        public Wire connect(Object target) {
            if (target instanceof Wire w) {
                return connectToWire(w);
            }
            return connectToComponent(target, null, null);
        }

        // ─── 3. NETLIST & ELECTRICAL PROPAGATION ────────────────────────────────

        public void propagate(Pin source, double v) {
            propagate(source, v, new HashSet<>());
        }

        public void propagate(Pin source, double v, Set<Wire> visited) {
            if (!visited.add(this)) return; // Prevents cycles across connected wires

            if (source == pinA && pinB != null) {
                pinB.voltage = v;
                pinB.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            } else if (source == pinB && pinA != null) {
                pinA.voltage = v;
                pinA.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
            } else if (source == null) {
                // Incoming from connected wire junction: drive all non-null pins attached to this wire
                if (pinA != null) {
                    pinA.voltage = v;
                    pinA.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
                }
                if (pinB != null) {
                    pinB.voltage = v;
                    pinB.digitalState = (v > 1.65) ? SignalState.HIGH : SignalState.LOW;
                }
            }

            // Propagate across all connected wires in the junction network
            for (Wire cw : connectedWires) {
                if (cw != null && !visited.contains(cw)) {
                    cw.propagate(null, v, visited);
                }
            }
        }

        /**
         * Gathers all reachable wires and pins in this equipotential electrical net
         * and synchronizes all connected inputs to match the active driving voltage.
         */
        public void syncNet() {
            Set<Wire> netWires = new HashSet<>();
            gatherNetWires(netWires);

            double driverVoltage = 0.0;
            boolean hasDriver = false;

            for (Wire w : netWires) {
                if (w.pinA != null && (w.pinA.mode == PinMode.DIGITAL_OUT || w.pinA.mode == PinMode.PWM_OUT)) {
                    driverVoltage = Math.max(driverVoltage, w.pinA.voltage);
                    hasDriver = true;
                }
                if (w.pinB != null && (w.pinB.mode == PinMode.DIGITAL_OUT || w.pinB.mode == PinMode.PWM_OUT)) {
                    driverVoltage = Math.max(driverVoltage, w.pinB.voltage);
                    hasDriver = true;
                }
            }

            if (!hasDriver) {
                for (Wire w : netWires) {
                    if (w.pinA != null) driverVoltage = Math.max(driverVoltage, w.pinA.voltage);
                    if (w.pinB != null) driverVoltage = Math.max(driverVoltage, w.pinB.voltage);
                }
            }

            for (Wire w : netWires) {
                if (w.pinA != null && (w.pinA.mode == PinMode.DIGITAL_IN || w.pinA.mode == PinMode.ANALOG_IN)) {
                    w.pinA.voltage = driverVoltage;
                    w.pinA.digitalState = (driverVoltage > 1.65) ? SignalState.HIGH : SignalState.LOW;
                }
                if (w.pinB != null && (w.pinB.mode == PinMode.DIGITAL_IN || w.pinB.mode == PinMode.ANALOG_IN)) {
                    w.pinB.voltage = driverVoltage;
                    w.pinB.digitalState = (driverVoltage > 1.65) ? SignalState.HIGH : SignalState.LOW;
                }
            }
        }

        private void gatherNetWires(Set<Wire> visited) {
            if (!visited.add(this)) return;
            for (Wire cw : connectedWires) {
                if (cw != null) cw.gatherNetWires(visited);
            }
        }

        public boolean isJunction() {
            return !connectedWires.isEmpty();
        }

        public List<Wire> getConnectedWires() {
            return Collections.unmodifiableList(connectedWires);
        }

        public List<String> getConnectedWireNames() {
            List<String> names = new ArrayList<>();
            for (Wire cw : connectedWires) names.add(cw.wireName);
            return names;
        }

        public Set<Wire> getAllWiresInNet() {
            Set<Wire> set = new LinkedHashSet<>();
            gatherNetWires(set);
            return set;
        }

        public Set<Pin> getAllPinsInNet() {
            Set<Pin> pins = new LinkedHashSet<>();
            for (Wire w : getAllWiresInNet()) {
                if (w.pinA != null) pins.add(w.pinA);
                if (w.pinB != null) pins.add(w.pinB);
            }
            return pins;
        }

        public boolean isConnectedTo(Wire other) {
            return getAllWiresInNet().contains(other);
        }

        public boolean isAttachedTo(Board b) {
            if (b == null) return false;
            return b.pins.containsValue(pinA) || b.pins.containsValue(pinB);
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

        // Component graphic & visual layout coordinates
        public String imagePath = null;
        public Map<String, Point2D.Double> pinPositions = new LinkedHashMap<>();
        public int layoutX = 100, layoutY = 100;
        
        // On-board flashed firmware & HAL
        private Firmware flashedFirmware = null;
        private final McuHAL hal = new McuHAL(this);

        // PORT 1: Virtual Wireless Radio (Wi-Fi / BLE Telemetry Bridge)
        public ExternalAppletBridge radioBridge = null;
        public ExternalAppletBridge socketBridge = null; // backward compatibility alias

        public interface TickListener { void onTick(Board board); }
        private List<TickListener> tickListeners = new ArrayList<>();

        public Board(String id, String model) { super(id); this.model = model; }
        public void addPin(String pinId, PinMode mode) {
            Pin p = new Pin(pinId, mode, this);
            pins.put(pinId, p);
        }
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
