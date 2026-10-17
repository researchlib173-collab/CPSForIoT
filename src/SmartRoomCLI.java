import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import javax.swing.*;

// ============================================================================
// SMART ROOM CLI — Cyber-Physical Workbench with Layout & Board Persistence
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
    public static SmartRoomFirmware fusionFirmware = null; // Flashed on-board firmware
    public static CpsDomain.PhysicalTerminalBusBridge terminalBridge = null; // PORT 2: Physical Netlist Terminal Bus
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
            if (!sc.hasNextLine()) {
                // If input was piped and reached EOF, keep running background simulation if active
                if (isRunning) {
                    System.out.println("\n[SIM] Piped setup complete. Simulation running in background.");
                    try {
                        while (isRunning) Thread.sleep(5000);
                    } catch (InterruptedException ignored) {}
                }
                break;
            }
            String raw = sc.nextLine().trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) continue;
            if (raw.equalsIgnoreCase("exit")) { isRunning = false; scheduler.shutdown(); break; }
            process(raw);
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     IoT CPS WORKBENCH — Two-Port Cyber-Physical Simulation   ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  PORT 1 (Radio):    applet link <BOARD> tcp <PORT> (9090)    ║");
        System.out.println("║  PORT 2 (Terminals):workspace terminal link tcp <PORT> (9091)║");
        System.out.println("║                                                              ║");
        System.out.println("║  workspace create|use|list [NAME] manage workspaces          ║");
        System.out.println("║  layout save|load|list [FILE]     save/restore full circuit  ║");
        System.out.println("║  board save|load|list [B] [FILE]  save/load board & firmware ║");
        System.out.println("║  import <MODEL> as <NAME>         ESP32/NODEMCU/ARDUINO/     ║");
        System.out.println("║                                   RELAY_HV_MODULE            ║");
        System.out.println("║  create board <NAME>              blank custom board         ║");
        System.out.println("║  add pin <BOARD> <PIN> <MODE>     DIGITAL_IN/OUT ANALOG_IN   ║");
        System.out.println("║  connect <B1> <P1> to <B2> <P2>   connect with wire          ║");
        System.out.println("║  set <BOARD> <PIN> <voltage>      force pin voltage          ║");
        System.out.println("║  flash <BOARD> <FIRMWARE> [sec]   flash on-board firmware    ║");
        System.out.println("║  pinfix <BOARD> [IMAGE]           calibrate pin dots on image║");
        System.out.println("║  autoroute / aroute               visual circuit schematic   ║");
        System.out.println("║  sim start|stop|status            clock engine control       ║");
        System.out.println("║  list boards|wires|workspaces     inspect <BOARD>            ║");
        System.out.println("║  exit                                                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    // ─── Command dispatcher ───────────────────────────────────────────────────
    public static void process(String raw) {
        String[] parts = raw.split("\\s+");
        try {
            switch (parts[0].toLowerCase()) {

                // ── workspace create / use / terminal link tcp 9091 ────────────
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
                        case "terminal" -> {
                            // workspace terminal link tcp 9091
                            if (parts[2].equalsIgnoreCase("link")) {
                                int port = Integer.parseInt(parts[4]);
                                terminalBridge = CpsDomain.PhysicalTerminalBusBridge.getOrCreate(port, boards(), wires);
                                terminalBridge.setOnPinChangeCallback(SmartRoomCLI::tick);
                                System.out.printf("[PORT 2 - TERMINALS] Physical Netlist Terminal Bus open on port %d%n", port);
                            }
                        }
                        case "list" -> workspaces.forEach((n, b) ->
                            System.out.printf("  %-20s  boards=%d %s%n", n, b.size(),
                                n.equals(activeWorkspace) ? "← active" : ""));
                    }
                }

                // ── layout save <filename> / layout load <filename> / layout list ─
                case "layout" -> {
                    if (parts.length < 2) {
                        System.out.println("[SYNTAX] layout save <file> | layout load <file> | layout list");
                        return;
                    }
                    switch (parts[1].toLowerCase()) {
                        case "save" -> {
                            if (parts.length < 3) {
                                System.out.println("[SYNTAX] layout save <filename>");
                                return;
                            }
                            String filename = parts[2];
                            if (!filename.endsWith(".json")) filename += ".json";
                            File f = resolveLayoutFile(filename);
                            int tPort = (terminalBridge != null) ? terminalBridge.getPort() : 0;
                            CpsPersistence.saveLayout(activeWorkspace, boards(), wires, tPort, f);
                            System.out.printf("[LAYOUT] Successfully saved workspace layout '%s' (%d boards, %d wires, terminalPort=%d) to: %s%n",
                                activeWorkspace, boards().size(), wires.size(), tPort, f.getPath());
                        }
                        case "load" -> {
                            if (parts.length < 3) {
                                System.out.println("[SYNTAX] layout load <filename>");
                                return;
                            }
                            String filename = parts[2];
                            if (!filename.endsWith(".json")) filename += ".json";
                            File f = resolveLayoutFile(filename);
                            CpsPersistence.LayoutData data = CpsPersistence.loadLayout(f);
                            activeWorkspace = data.workspaceName;
                            workspaces.put(activeWorkspace, data.boards);
                            wires = data.wires;
                            for (CpsDomain.Board b : data.boards.values()) {
                                if (b.getFlashedFirmware() instanceof SmartRoomFirmware srf) {
                                    fusionFirmware = srf;
                                }
                            }
                            if (data.terminalPort > 0) {
                                terminalBridge = CpsDomain.PhysicalTerminalBusBridge.getOrCreate(data.terminalPort, boards(), wires);
                                terminalBridge.setOnPinChangeCallback(SmartRoomCLI::tick);
                            } else if (terminalBridge != null) {
                                terminalBridge.updateContext(boards(), wires);
                                terminalBridge.setOnPinChangeCallback(SmartRoomCLI::tick);
                            }

                            // Automatically start simulation clock so firmware ticks continuously
                            if (!isRunning) {
                                isRunning = true;
                                scheduler.scheduleAtFixedRate(SmartRoomCLI::tick, 0, 1000, TimeUnit.MILLISECONDS);
                                System.out.println("[SIM]       ▶ Simulation clock automatically started (1 Hz)");
                            }
                            // Execute immediate first tick for initial telemetry broadcast
                            tick();

                            System.out.printf("[LAYOUT] Successfully loaded layout '%s' (%d boards, %d wires) from: %s%n",
                                activeWorkspace, data.boards.size(), data.wires.size(), f.getPath());
                        }
                        case "list" -> {
                            File dir = new File("layouts");
                            if (!dir.exists() || dir.listFiles() == null) {
                                System.out.println("  No saved layouts found in layouts/ directory.");
                            } else {
                                File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                                if (files == null || files.length == 0) {
                                    System.out.println("  No saved layouts found in layouts/ directory.");
                                } else {
                                    System.out.println("  Saved Layouts (in layouts/):");
                                    for (File f : files) {
                                        System.out.printf("    %-25s  (%d KB)%n", f.getName(), f.length() / 1024 + 1);
                                    }
                                }
                            }
                        }
                        default -> System.out.println("[SYNTAX] layout save <file> | layout load <file> | layout list");
                    }
                }

                // ── board save <board> [filename] / board load <file> [as <name>] / board list ─
                case "board" -> {
                    if (parts.length < 2) {
                        System.out.println("[SYNTAX] board save <board> [file] | board load <file> [as <name>] | board list");
                        return;
                    }
                    switch (parts[1].toLowerCase()) {
                        case "save" -> {
                            if (parts.length < 3) {
                                System.out.println("[SYNTAX] board save <BOARD> [filename]");
                                return;
                            }
                            String bName = parts[2];
                            CpsDomain.Board b = boards().get(bName);
                            if (b == null) { System.out.println("[ERR] Board not found: " + bName); return; }
                            String filename = (parts.length > 3) ? parts[3] : (bName + ".json");
                            if (!filename.endsWith(".json")) filename += ".json";
                            File f = resolveBoardFile(filename);
                            CpsPersistence.saveBoard(b, f);
                            System.out.printf("[BOARD] Saved board '%s' [%s] with firmware '%s' to: %s%n",
                                bName, b.model,
                                (b.getFlashedFirmware() != null ? b.getFlashedFirmware().getName() : "none"),
                                f.getPath());
                        }
                        case "load" -> {
                            if (parts.length < 3) {
                                System.out.println("[SYNTAX] board load <filename> [as <NEW_NAME>]");
                                return;
                            }
                            String filename = parts[2];
                            if (!filename.endsWith(".json")) filename += ".json";
                            File f = resolveBoardFile(filename);
                            String newName = (parts.length >= 5 && parts[3].equalsIgnoreCase("as")) ? parts[4] : null;
                            CpsDomain.Board b = CpsPersistence.loadBoard(f, newName);
                            boards().put(b.getId(), b);
                            if (b.getFlashedFirmware() instanceof SmartRoomFirmware srf) {
                                fusionFirmware = srf;
                            }
                            System.out.printf("[BOARD] Loaded board '%s' [%s] with firmware '%s' into workspace '%s'%n",
                                b.getId(), b.model,
                                (b.getFlashedFirmware() != null ? b.getFlashedFirmware().getName() : "none"),
                                activeWorkspace);
                        }
                        case "list", "list-saved" -> {
                            File dir = new File("saved_boards");
                            if (!dir.exists() || dir.listFiles() == null) {
                                System.out.println("  No saved boards found in saved_boards/ directory.");
                            } else {
                                File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                                if (files == null || files.length == 0) {
                                    System.out.println("  No saved boards found in saved_boards/ directory.");
                                } else {
                                    System.out.println("  Saved Boards (in saved_boards/):");
                                    for (File f : files) {
                                        System.out.printf("    %-25s  (%d KB)%n", f.getName(), f.length() / 1024 + 1);
                                    }
                                }
                            }
                        }
                        default -> System.out.println("[SYNTAX] board save <board> [file] | board load <file> [as <name>] | board list");
                    }
                }

                // ── pinfix <boardname> [imagePath] ───────────────────────────
                case "pinfix" -> {
                    if (parts.length < 2) {
                        System.out.println("[SYNTAX] pinfix <boardname/component_name> [optional_image_path]");
                        return;
                    }
                    String boardName = parts[1];
                    CpsDomain.Board b = boards().get(boardName);
                    if (b == null) {
                        System.out.println("[ERR] Board not found in active workspace: " + boardName);
                        return;
                    }

                    File imgFile = null;
                    if (parts.length >= 3) {
                        imgFile = new File(parts[2]);
                    } else if (b.imagePath != null && !b.imagePath.isEmpty()) {
                        File existing = new File(b.imagePath);
                        if (existing.exists()) imgFile = existing;
                    }

                    final CpsDomain.Board targetBoard = b;
                    if (imgFile == null || !imgFile.exists()) {
                        System.out.println("[PINFIX] Launching file chooser to browse for component image...");
                        SwingUtilities.invokeLater(() -> {
                            JFileChooser chooser = new JFileChooser(new File("assets"));
                            chooser.setDialogTitle("Select Component Image for: " + targetBoard.getId());
                            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                                "Image Files (*.png, *.jpg, *.jpeg, *.bmp)", "png", "jpg", "jpeg", "bmp"));
                            int ret = chooser.showOpenDialog(null);
                            if (ret == JFileChooser.APPROVE_OPTION) {
                                File selected = chooser.getSelectedFile();
                                System.out.println("[PINFIX] Selected image: " + selected.getAbsolutePath());
                                PinFixCalibrator.launch(targetBoard, selected, () -> {
                                    System.out.printf("[PINFIX] Saved %d calibrated pin positions for %s%n",
                                        targetBoard.pinPositions.size(), targetBoard.getId());
                                });
                            } else {
                                System.out.println("[PINFIX] Calibration cancelled (no image selected).");
                            }
                        });
                    } else {
                        System.out.println("[PINFIX] Opening PinFix calibrator with image: " + imgFile.getAbsolutePath());
                        final File selected = imgFile;
                        PinFixCalibrator.launch(targetBoard, selected, () -> {
                            System.out.printf("[PINFIX] Saved %d calibrated pin positions for %s%n",
                                targetBoard.pinPositions.size(), targetBoard.getId());
                        });
                    }
                }

                // ── autoroute or aroute ───────────────────────────────────────
                case "autoroute", "aroute" -> {
                    System.out.printf("[AUTOROUTE] Opening visual circuit schematic for workspace '%s' (%d boards, %d wires)...%n",
                        activeWorkspace, boards().size(), wires.size());
                    CircuitAutoRouteGUI.launch(activeWorkspace, boards(), wires);
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

                // ── flash RoomMasterMCU SmartRoomFirmware [30] ────────────────
                case "flash" -> {
                    CpsDomain.Board b = boards().get(parts[1]);
                    if (b == null) { System.out.println("[ERR] Board not found: " + parts[1]); return; }
                    String fwName = (parts.length > 2) ? parts[2] : "SmartRoomFirmware";
                    if (fwName.toLowerCase().contains("thermal")) {
                        ThermalBenchFirmware fw = new ThermalBenchFirmware();
                        b.flashFirmware(fw);
                        System.out.printf("[FLASH] Successfully flashed %s on %s%n", fw.getName(), parts[1]);
                    } else {
                        int hysSec = (parts.length > 3) ? Integer.parseInt(parts[3]) : 30;
                        SmartRoomFirmware fw = new SmartRoomFirmware(hysSec);
                        b.flashFirmware(fw);
                        fusionFirmware = fw;
                        System.out.printf("[FLASH] Successfully flashed %s on %s (Hysteresis = %ds)%n",
                            fw.getName(), parts[1], hysSec);
                    }
                }

                // ── attach fusion RoomMasterMCU [900] (backward compatibility) ─
                case "attach" -> {
                    if (parts[1].equalsIgnoreCase("fusion")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        int hysSec = (parts.length > 3) ? Integer.parseInt(parts[3]) : 30;
                        SmartRoomFirmware fw = new SmartRoomFirmware(hysSec);
                        b.flashFirmware(fw);
                        fusionFirmware = fw;
                        System.out.printf("[FLASH] Flashed %s onto %s (hysteresis=%ds)%n",
                            fw.getName(), parts[2], hysSec);
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
                            if (fusionFirmware != null) {
                                System.out.printf("[FIRMWARE]  %s  State=%s  LightRelay=%b  HvacRelay=%b%n",
                                    fusionFirmware.getName(),
                                    fusionFirmware.getState(),
                                    fusionFirmware.isLightRelayOn(),
                                    fusionFirmware.isHvacRelayOn());
                            }
                        }
                    }
                }

                // ── list boards / wires / workspaces ──────────────────────────
                case "list" -> {
                    switch (parts[1].toLowerCase()) {
                        case "boards" -> boards().forEach((n, b) ->
                            System.out.printf("  %-20s  model=%-20s  pins=%d  fw=%s%n",
                                n, b.model, b.pins.size(),
                                (b.getFlashedFirmware() != null ? b.getFlashedFirmware().getName() : "none")));
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
                    if (b.getFlashedFirmware() != null) {
                        System.out.printf("    [FIRMWARE] %s (ON-CHIP RUNNING)%n", b.getFlashedFirmware().getName());
                    }
                    b.pins.forEach((pid, pin) ->
                        System.out.printf("    %-12s  mode=%-12s  V=%.3f  state=%s%n",
                            pid, pin.mode, pin.voltage, pin.digitalState));
                    b.components.forEach((cid, c) ->
                        System.out.printf("    [COMP] %-12s  type=%s%n", cid, c.getType()));
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
                boolean aIsInput = (w.pinA.mode == CpsDomain.PinMode.DIGITAL_IN || w.pinA.mode == CpsDomain.PinMode.ANALOG_IN);
                boolean bIsInput = (w.pinB.mode == CpsDomain.PinMode.DIGITAL_IN || w.pinB.mode == CpsDomain.PinMode.ANALOG_IN);
                
                if (bIsInput && !aIsInput) {
                    w.pinB.voltage = w.pinA.voltage;
                    w.pinB.digitalState = w.pinA.digitalState;
                } else if (aIsInput && !bIsInput) {
                    w.pinA.voltage = w.pinB.voltage;
                    w.pinA.digitalState = w.pinB.digitalState;
                } else {
                    w.pinB.voltage = w.pinA.voltage;
                    w.pinB.digitalState = w.pinA.digitalState;
                }
            }
        }
        for (CpsDomain.Board b : boards().values()) b.tickLogic();
        if (terminalBridge != null) terminalBridge.broadcastNetlistTick();
    }

    private static File resolveLayoutFile(String filename) {
        File f = new File(filename);
        if (f.isAbsolute() || filename.contains("/") || filename.contains("\\")) return f;
        return new File("layouts", filename);
    }

    private static File resolveBoardFile(String filename) {
        File f = new File(filename);
        if (f.isAbsolute() || filename.contains("/") || filename.contains("\\")) return f;
        return new File("saved_boards", filename);
    }
}
