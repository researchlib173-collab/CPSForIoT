import java.util.*;
import java.util.concurrent.*;

// ============================================================================
// SMART ROOM CLI — Phase 2: workspace, create board, add pin, sensor fusion
// ============================================================================
public class SmartRoomCLI {

    // ─── Global simulation state ──────────────────────────────────────────────
    public static final  Map<String, Map<String, CpsDomain.Board>> workspaces = new LinkedHashMap<>();
    public static volatile String activeWorkspace = "default";

    // Convenience accessors for the active workspace
    public static Map<String, CpsDomain.Board> boards() {
        return workspaces.computeIfAbsent(activeWorkspace, k -> new LinkedHashMap<>());
    }

    public static List<CpsDomain.Wire> wires    = new ArrayList<>();
    public static PresenceFusionEngine fusion   = null;   // Set when presence engine is attached
    private static volatile boolean isRunning   = false;
    private static final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2);

    // ─── Entry point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        workspaces.put("default", new LinkedHashMap<>());
        Scanner sc = new Scanner(System.in);
        printBanner();
        while (true) {
            System.out.print("\u001B[32m[" + activeWorkspace + "] CPS > \u001B[0m");
            if (!sc.hasNextLine()) break;
            String raw = sc.nextLine().trim();
            if (raw.isEmpty()) continue;
            if (raw.equalsIgnoreCase("exit")) { scheduler.shutdown(); break; }
            process(raw);
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     IoT CPS WORKBENCH — Phase 2: Smart Room Fusion Engine    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  workspace create <NAME>          create a workspace         ║");
        System.out.println("║  workspace use <NAME>             switch active workspace     ║");
        System.out.println("║  import <MODEL> as <NAME>         ESP32/NODEMCU/ARDUINO/      ║");
        System.out.println("║                                   RELAY_HV_MODULE             ║");
        System.out.println("║  create board <NAME>              blank custom board          ║");
        System.out.println("║  add pin <BOARD> <PIN> <MODE>     DIGITAL_IN/OUT ANALOG_IN    ║");
        System.out.println("║                                   PWM_OUT                     ║");
        System.out.println("║  connect <B1> <P1> to <B2> <P2> [wire]                       ║");
        System.out.println("║  set <BOARD> <PIN> <voltage>      force pin voltage           ║");
        System.out.println("║  attach fusion <BOARD> [hysteresis_sec]  attach SM           ║");
        System.out.println("║  applet link <BOARD> tcp <PORT>                               ║");
        System.out.println("║  sim start|stop|status                                        ║");
        System.out.println("║  list boards|wires|workspaces     inspect <BOARD>             ║");
        System.out.println("║  exit                                                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    // ─── Command dispatcher ───────────────────────────────────────────────────
    public static void process(String raw) {
        String[] parts = raw.split("\\s+");
        try {
            switch (parts[0].toLowerCase()) {

                // ── workspace create SmartRoom_Phase2 ──────────────────────────
                case "workspace" -> {
                    switch (parts[1].toLowerCase()) {
                        case "create" -> {
                            String wName = parts[2];
                            workspaces.put(wName, new LinkedHashMap<>());
                            activeWorkspace = wName;
                            System.out.printf("[WORKSPACE] Created & switched to: %s%n", wName);
                        }
                        case "use" -> {
                            String wName = parts[2];
                            if (!workspaces.containsKey(wName)) {
                                System.out.println("[ERR] Workspace not found: " + wName); return;
                            }
                            activeWorkspace = wName;
                            System.out.printf("[WORKSPACE] Switched to: %s%n", wName);
                        }
                        case "list" -> workspaces.forEach((n, b) ->
                            System.out.printf("  %-20s  boards=%d %s%n", n, b.size(),
                                n.equals(activeWorkspace) ? "← active" : ""));
                    }
                }

                // ── import ESP32 as RoomMasterMCU ─────────────────────────────
                case "import" -> {
                    String model = parts[1], name = parts[3]; // parts[2]="as"
                    CpsDomain.Board b = switch (model.toUpperCase()) {
                        case "ESP32"           -> HardwareDictionary.createESP32(name);
                        case "NODEMCU"         -> HardwareDictionary.createNodeMCU(name);
                        case "ARDUINO"         -> HardwareDictionary.createArduinoUno(name);
                        case "RELAY_HV_MODULE" -> HardwareDictionary.createRelayModule(name);
                        default                -> new CpsDomain.Board(name, model.toUpperCase());
                    };
                    boards().put(name, b);
                    System.out.printf("[IMPORTED]  %-18s → %s%n", model, name);
                }

                // ── create board PIR_MotionSensor ─────────────────────────────
                case "create" -> {
                    if (parts[1].equalsIgnoreCase("board")) {
                        String name = parts[2];
                        boards().put(name, new CpsDomain.Board(name, "CUSTOM"));
                        System.out.printf("[CREATED]   Custom board: %s%n", name);
                    }
                }

                // ── add pin PIR_MotionSensor DATA DIGITAL_IN ──────────────────
                case "add" -> {
                    if (parts[1].equalsIgnoreCase("pin")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        String pinId = parts[3];
                        CpsDomain.PinMode mode = switch (parts[4].toUpperCase()) {
                            case "DIGITAL_IN"  -> CpsDomain.PinMode.DIGITAL_IN;
                            case "DIGITAL_OUT" -> CpsDomain.PinMode.DIGITAL_OUT;
                            case "ANALOG_IN"   -> CpsDomain.PinMode.ANALOG_IN;
                            case "PWM_OUT"     -> CpsDomain.PinMode.PWM_OUT;
                            default            -> CpsDomain.PinMode.DIGITAL_IN;
                        };
                        b.addPin(pinId, mode);
                        System.out.printf("[PIN ADDED] %s.%s [%s]%n", parts[2], pinId, mode);
                    }
                }

                // ── connect B1 PIN to B2 PIN [wireName] ───────────────────────
                case "connect" -> {
                    CpsDomain.Board b1 = boards().get(parts[1]);
                    CpsDomain.Board b2 = boards().get(parts[4]);
                    if (b1 == null) { System.out.println("[ERR] Board not found: " + parts[1]); return; }
                    if (b2 == null) { System.out.println("[ERR] Board not found: " + parts[4]); return; }
                    CpsDomain.Pin p1 = b1.getPin(parts[2]);
                    CpsDomain.Pin p2 = b2.getPin(parts[5]);
                    if (p1 == null) { System.out.printf("[ERR] Pin not found: %s.%s%n", parts[1], parts[2]); return; }
                    if (p2 == null) { System.out.printf("[ERR] Pin not found: %s.%s%n", parts[4], parts[5]); return; }
                    // Strip surrounding *asterisks* if user typed them
                    String wName = (parts.length > 6)
                        ? parts[6].replaceAll("\\*", "")
                        : "W_" + System.currentTimeMillis();
                    CpsDomain.Wire w = new CpsDomain.Wire(wName, p1, p2);
                    p1.attachWire(w); p2.attachWire(w);
                    wires.add(w);
                    System.out.printf("[WIRED]     %s.%s ←→ %s.%s  [%s]%n",
                        parts[1], parts[2], parts[4], parts[5], wName);
                }

                // ── set Node1 GPIO_04 2.5 ─────────────────────────────────────
                case "set" -> {
                    CpsDomain.Board b = boards().get(parts[1]);
                    if (b == null) { System.out.println("[ERR] Board not found: " + parts[1]); return; }
                    CpsDomain.Pin p = b.getPin(parts[2]);
                    if (p == null) { System.out.printf("[ERR] Pin not found: %s.%s%n", parts[1], parts[2]); return; }
                    double v = Double.parseDouble(parts[3]);
                    p.setVoltage(v);
                    System.out.printf("[SET]       %s.%s = %.3fV (%s)%n",
                        parts[1], parts[2], v, p.digitalState);
                }

                // ── attach fusion RoomMasterMCU [900] ─────────────────────────
                case "attach" -> {
                    if (parts[1].equalsIgnoreCase("fusion")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        int hysSec = (parts.length > 3) ? Integer.parseInt(parts[3]) : 900;
                        fusion = new PresenceFusionEngine(hysSec, 60.0);
                        b.setExternalLogic(fusion.buildFirmwareFor(b));
                        System.out.printf("[FUSION]    Presence SM attached to %s  (hysteresis=%ds)%n",
                            parts[2], hysSec);
                    }
                }

                // ── applet link RoomMasterMCU tcp 9090 ────────────────────────
                case "applet" -> {
                    if (parts[1].equalsIgnoreCase("link")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        int port = Integer.parseInt(parts[4]);
                        b.enableSocketBridge(port);
                        System.out.printf("[BRIDGE]    %s → localhost:%d%n", parts[2], port);
                    }
                }

                // ── sim start / stop / status ─────────────────────────────────
                case "sim" -> {
                    switch (parts[1].toLowerCase()) {
                        case "start" -> {
                            if (!isRunning) {
                                isRunning = true;
                                scheduler.scheduleAtFixedRate(SmartRoomCLI::tick, 0, 1000, TimeUnit.MILLISECONDS);
                                System.out.println("[SIM]       ▶ Started (1 Hz)");
                            } else System.out.println("[SIM]       Already running.");
                        }
                        case "stop"   -> { isRunning = false; System.out.println("[SIM]       ■ Stopped."); }
                        case "status" -> {
                            System.out.printf("[SIM]       %s  Boards=%d  Wires=%d  Workspace=%s%n",
                                isRunning ? "▶ RUNNING" : "■ STOPPED",
                                boards().size(), wires.size(), activeWorkspace);
                            if (fusion != null)
                                System.out.printf("[FUSION]    RoomState=%s  Light=%b  HVAC=%b%n",
                                    fusion.getCurrentState(),
                                    fusion.isLightRelayOn(), fusion.isHvacRelayOn());
                        }
                    }
                }

                // ── list boards / wires / workspaces ──────────────────────────
                case "list" -> {
                    switch (parts[1].toLowerCase()) {
                        case "boards" -> boards().forEach((n, b) ->
                            System.out.printf("  %-20s  model=%-20s  pins=%d%n", n, b.model, b.pins.size()));
                        case "wires"  -> wires.forEach(w ->
                            System.out.printf("  %-20s  %s ←→ %s%n",
                                w.wireName, w.pinA.pinId, w.pinB.pinId));
                        case "workspaces" -> workspaces.forEach((n, b) ->
                            System.out.printf("  %-20s  boards=%d%s%n",
                                n, b.size(), n.equals(activeWorkspace) ? "  ← active" : ""));
                    }
                }

                // ── inspect RoomMasterMCU ─────────────────────────────────────
                case "inspect" -> {
                    CpsDomain.Board b = boards().get(parts[1]);
                    if (b == null) { System.out.println("[ERR] Board not found: " + parts[1]); return; }
                    System.out.printf("  Board : %s  [%s]%n", b.id, b.model);
                    b.pins.forEach((pid, pin) ->
                        System.out.printf("    %-12s  mode=%-12s  V=%.3f  state=%s%n",
                            pid, pin.mode, pin.voltage, pin.digitalState));
                    b.components.forEach((cid, c) ->
                        System.out.printf("    [COMP] %-12s  type=%s%n", cid, c.getType()));
                    if (fusion != null && b.externalOverrideLogic != null)
                        System.out.printf("    [FUSION] state=%s  light=%b  hvac=%b%n",
                            fusion.getCurrentState(),
                            fusion.isLightRelayOn(), fusion.isHvacRelayOn());
                }

                default -> System.out.println("[ERR] Unknown command. Type 'exit' to quit.");
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("[SYNTAX] Missing argument.");
        } catch (NumberFormatException e) {
            System.out.println("[SYNTAX] Expected a number: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }

    // ─── Simulation tick ──────────────────────────────────────────────────────
    private static void tick() {
        if (!isRunning) return;
        for (CpsDomain.Wire w : wires) {
            if (w.pinA != null && w.pinB != null) {
                w.pinB.voltage      = w.pinA.voltage;
                w.pinB.digitalState = w.pinA.digitalState;
            }
        }
        for (CpsDomain.Board b : boards().values()) b.tickLogic();
    }
}
