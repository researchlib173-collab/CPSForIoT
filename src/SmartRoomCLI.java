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
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    IoT CPS WORKBENCH — CYBER-PHYSICAL SIMULATOR              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  [WIRING & WIRE-TO-WIRE JUNCTIONS]                                           ║");
        System.out.println("║    connect <B1> <P1> to <B2> <P2>     standard pin-to-pin wire connection    ║");
        System.out.println("║    connect wire <W1> to wire <W2>     WIRE-TO-WIRE junction tap (multi-hop)  ║");
        System.out.println("║    connect wire <W> to [comp] <C>     WIRE-TO-COMPONENT (unique 1:1 or 1:N)  ║");
        System.out.println("║    disconnect <B1><P1> from <B2><P2>  disconnect wire from board pins        ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [WORKSPACES & PERSISTENCE]                                                  ║");
        System.out.println("║    workspace create|use|list [NAME]   manage active simulation workspace     ║");
        System.out.println("║    layout save|load|list [FILE]       save/restore full circuit & netlist    ║");
        System.out.println("║    board save|load|list [B] [FILE]    save/restore board & firmware state    ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [BOARDS & COMPONENTS]                                                       ║");
        System.out.println("║    import <MODEL> as <NAME>           ESP32/INA219/TEMP_SENSOR/LED/ARDUINO...║");
        System.out.println("║    create board <NAME>                create custom blank circuit board      ║");
        System.out.println("║    add pin <BOARD> <PIN> <MODE>       DIGITAL_IN/OUT, ANALOG_IN/PWM_OUT      ║");
        System.out.println("║    add component <B> <TYPE> <NAME>    attach electronic component to board   ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [SIMULATION & CONTROL]                                                      ║");
        System.out.println("║    pin set <BOARD> <PIN> <voltage>    force analog/digital pin voltage       ║");
        System.out.println("║    pin read <BOARD> <PIN>             read real-time pin voltage & state     ║");
        System.out.println("║    flash <BOARD> <FIRMWARE>           flash on-board firmware onto MCU       ║");
        System.out.println("║    sim start|stop|status              simulation clock engine (1 Hz)         ║");
        System.out.println("║    list boards|wires|workspaces       inspect active netlist objects         ║");
        System.out.println("║    inspect <BOARD>                    detailed pin & component status        ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [GRAPHICAL APPLETS & CAD]                                                   ║");
        System.out.println("║    autoroute / aroute [NAME/FILE]     visual circuit schematic CAD viewer    ║");
        System.out.println("║    applet dashboard                   Thermal Bench telemetry dashboard      ║");
        System.out.println("║    applet input                       Regulated Power Supply & motor GUI     ║");
        System.out.println("║    applet blink                       ESP32 LED blink visualizer             ║");
        System.out.println("║    pinfix <BOARD> [IMAGE]             calibrate visual pin coordinates       ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [NETWORKING PORTS]                                                          ║");
        System.out.println("║    PORT 1 (Radio Telemetry):          applet link <BOARD> tcp <PORT> (9092)  ║");
        System.out.println("║    PORT 2 (Terminal Netlist Bus):     workspace terminal link tcp (9093)     ║");
        System.out.println("║                                                                              ║");
        System.out.println("║  [HELP & EXIT]                                                               ║");
        System.out.println("║    help / menu                        reprint this command guide             ║");
        System.out.println("║    exit                               shutdown workbench & exit              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
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

                // ── autoroute or aroute [workspace|file] ───────────────────────
                case "autoroute", "aroute" -> {
                    if (parts.length > 1) {
                        String target = parts[1].toLowerCase();
                        File targetFile = null;
                        if (target.contains("blink")) {
                            targetFile = new File("layouts/esp32_blink.json");
                        } else if (target.contains("thermal")) {
                            targetFile = new File("layouts/thermal_bench_rig.json");
                        } else if (target.contains("smart") || target.contains("room")) {
                            targetFile = new File("layouts/smart_room_demo.json");
                        } else {
                            File f = new File(parts[1]);
                            if (!f.exists() && !parts[1].startsWith("layouts/")) f = new File("layouts", parts[1]);
                            if (f.exists()) targetFile = f;
                        }

                        if (targetFile != null && targetFile.exists()) {
                            try {
                                CpsPersistence.LayoutData data = CpsPersistence.loadLayout(targetFile);
                                activeWorkspace = data.workspaceName;
                                workspaces.put(activeWorkspace, data.boards);
                                wires.clear();
                                wires.addAll(data.wires);
                                System.out.printf("[LAYOUT] Switched to workspace '%s' (%d boards, %d wires) from: %s%n",
                                    activeWorkspace, boards().size(), wires.size(), targetFile.getName());
                            } catch (Exception ex) {
                                System.err.println("[LAYOUT] Error loading layout: " + ex.getMessage());
                            }
                        }
                    }
                    System.out.printf("[AUTOROUTE] Opening visual circuit schematic for workspace '%s' (%d boards, %d wires)...%n",
                        activeWorkspace, boards().size(), wires.size());
                    CircuitAutoRouteGUI.launch(activeWorkspace, boards(), wires);
                }

                // ── import ESP32 as RoomMasterMCU / import PIR as MotionDetector ─
                case "import" -> {
                    String model = parts[1], name = parts[3]; // parts[2]="as"
                    CpsDomain.Board b = HardwareDictionary.createComponentBoard(model, name);
                    boards().put(name, b);
                    System.out.printf("[IMPORTED]  %-18s → %s (Pins: %d)%n", model, name, b.pins.size());
                }

                // ── create board <NAME> [MODEL] | create component <TYPE> <NAME> ─
                case "create" -> {
                    if (parts.length < 3) {
                        System.out.println("[SYNTAX] create board <NAME> [MODEL] | create component <TYPE> <NAME>");
                        return;
                    }
                    if (parts[1].equalsIgnoreCase("board")) {
                        String name = parts[2];
                        String model = (parts.length > 3) ? parts[3] : "CUSTOM";
                        CpsDomain.Board b = HardwareDictionary.createComponentBoard(model, name);
                        boards().put(name, b);
                        System.out.printf("[CREATED]   Board '%s' [%s] (Pins: %d)%n", name, b.model, b.pins.size());
                    } else if (parts[1].equalsIgnoreCase("component")) {
                        String type = parts[2];
                        String name = parts[3];
                        CpsDomain.Board b = HardwareDictionary.createComponentBoard(type, name);
                        boards().put(name, b);
                        System.out.printf("[CREATED]   Component '%s' [%s] (Pins: %d)%n", name, b.model, b.pins.size());
                    }
                }

                // ── add pin <BOARD> <PIN> <MODE> | add component <BOARD> <TYPE> <ID> ─
                case "add" -> {
                    if (parts.length < 4) {
                        System.out.println("[SYNTAX] add pin <BOARD> <PIN> <MODE> | add component <BOARD> <TYPE> <ID>");
                        return;
                    }
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
                    } else if (parts[1].equalsIgnoreCase("component")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        String type = parts[3].toUpperCase();
                        String compId = (parts.length > 4) ? parts[4] : parts[2] + "_" + type.toLowerCase();
                        CpsDomain.ElectronicComponent ec = switch (type) {
                            case "LED"           -> new HardwareDictionary.LED(compId);
                            case "PIR"           -> new HardwareDictionary.PIRSensor(compId);
                            case "DOOR"          -> new HardwareDictionary.DoorContactSensor(compId);
                            case "RADAR"         -> new HardwareDictionary.MmWaveRadar(compId);
                            case "RELAY"         -> new HardwareDictionary.RelayModule(compId);
                            case "TEMP"          -> new HardwareDictionary.TemperatureSensor(compId, 25.0);
                            case "RESISTOR"      -> new HardwareDictionary.Resistor(compId, (parts.length > 5) ? Double.parseDouble(parts[5]) : 220.0);
                            case "CAPACITOR"     -> new HardwareDictionary.Capacitor(compId, (parts.length > 5) ? Double.parseDouble(parts[5]) : 0.0001);
                            default              -> new HardwareDictionary.LED(compId);
                        };
                        b.addComponent(ec);
                        System.out.printf("[COMP ADDED] Added %s '%s' to board '%s'%n", type, compId, parts[2]);
                    }
                }

                // ── connect B1 PIN to B2 PIN [wireName] | connect wire W1 to W2 | connect wire W1 to component COMP ──
                case "connect" -> {
                    // Check for wire-level connection: connect wire <W1> to <W2> | connect wire <W1> to component <COMP>
                    if (parts.length >= 4 && parts[1].equalsIgnoreCase("wire")) {
                        String w1Name = parts[2];
                        CpsDomain.Wire w1 = findWireByName(w1Name);
                        if (w1 == null) {
                            System.out.println("[ERR] Wire not found: " + w1Name);
                            return;
                        }

                        int targetIdx = parts[3].equalsIgnoreCase("to") ? 4 : 3;
                        if (targetIdx >= parts.length) {
                            System.out.println("[SYNTAX] connect wire <W1> to wire <W2> | connect wire <W1> to component <COMP>");
                            return;
                        }

                        String targetType = parts[targetIdx].toLowerCase();
                        if (targetType.equals("wire") && targetIdx + 1 < parts.length) {
                            String w2Name = parts[targetIdx + 1];
                            CpsDomain.Wire w2 = findWireByName(w2Name);
                            if (w2 == null) { System.out.println("[ERR] Target wire not found: " + w2Name); return; }
                            w1.connectToWire(w2);
                            System.out.printf("[WIRED JUNCTION] Wire '%s' ←→ Wire '%s' [Net size: %d wires, %d pins]%n",
                                w1.wireName, w2.wireName, w1.getAllWiresInNet().size(), w1.getAllPinsInNet().size());
                        } else if (targetType.equals("component") && targetIdx + 1 < parts.length) {
                            String compName = parts[targetIdx + 1];
                            try {
                                w1.connectToComponent(compName, wires, boards());
                                System.out.printf("[WIRED JUNCTION] Wire '%s' successfully connected to unique wire of component '%s' [Net size: %d wires, %d pins]%n",
                                    w1.wireName, compName, w1.getAllWiresInNet().size(), w1.getAllPinsInNet().size());
                            } catch (CpsDomain.AmbiguousWireConnectionException ex) {
                                System.out.println("[ERR] " + ex.getMessage());
                            }
                        } else {
                            CpsDomain.Wire w2 = findWireByName(parts[targetIdx]);
                            if (w2 != null) {
                                w1.connectToWire(w2);
                                System.out.printf("[WIRED JUNCTION] Wire '%s' ←→ Wire '%s' [Net size: %d wires, %d pins]%n",
                                    w1.wireName, w2.wireName, w1.getAllWiresInNet().size(), w1.getAllPinsInNet().size());
                            } else {
                                String compName = parts[targetIdx];
                                try {
                                    w1.connectToComponent(compName, wires, boards());
                                    System.out.printf("[WIRED JUNCTION] Wire '%s' connected to unique wire of component '%s' [Net size: %d wires, %d pins]%n",
                                        w1.wireName, compName, w1.getAllWiresInNet().size(), w1.getAllPinsInNet().size());
                                } catch (CpsDomain.AmbiguousWireConnectionException ex) {
                                    System.out.println("[ERR] " + ex.getMessage());
                                }
                            }
                        }
                        return;
                    }

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
                    wires.add(w);
                    System.out.printf("[WIRED]     %s.%s ←→ %s.%s  [%s]%n",
                        parts[1], parts[2], parts[4], parts[5], wName);
                }

                // ── disconnect <B1><P1> from <B2><P2> | disconnect <B1> <P1> from <B2> <P2> | disconnect wire <W> ──
                case "disconnect" -> {
                    if (parts.length < 2) {
                        System.out.println("[SYNTAX] disconnect <board_name><pin_name> from <board_name><pin_name> | disconnect wire <wire_name>");
                        return;
                    }

                    // Check for "disconnect wire <W>"
                    if (parts.length >= 3 && parts[1].equalsIgnoreCase("wire")) {
                        String wName = parts[2];
                        CpsDomain.Wire targetW = findWireByName(wName);
                        if (targetW != null) {
                            wires.remove(targetW);
                            targetW.disconnectFromAll();
                            System.out.printf("[DISCONNECTED] Wire '%s' removed.%n", wName);
                        } else {
                            System.out.println("[ERR] Wire not found: " + wName);
                        }
                        return;
                    }

                    BoardPinPair bp1 = null, bp2 = null;

                    // Case A: Spaced syntax: disconnect B1 P1 from B2 P2 (5 tokens)
                    if (parts.length >= 5 && (parts[3].equalsIgnoreCase("from") || parts[3].equalsIgnoreCase("to"))) {
                        CpsDomain.Board b1 = boards().get(parts[1]);
                        CpsDomain.Board b2 = boards().get(parts[4]);
                        if (b1 != null && b2 != null) {
                            CpsDomain.Pin p1 = b1.getPin(parts[2]);
                            CpsDomain.Pin p2 = b2.getPin(parts[5]);
                            if (p1 != null && p2 != null) {
                                bp1 = new BoardPinPair(b1, p1);
                                bp2 = new BoardPinPair(b2, p2);
                            }
                        }
                    }

                    // Case B: Concatenated or dotted syntax: disconnect <B1><P1> from <B2><P2>
                    if (bp1 == null && parts.length >= 3) {
                        int fromIdx = -1;
                        for (int i = 1; i < parts.length; i++) {
                            if (parts[i].equalsIgnoreCase("from") || parts[i].equalsIgnoreCase("to")) {
                                fromIdx = i;
                                break;
                            }
                        }
                        if (fromIdx > 0 && fromIdx + 1 < parts.length) {
                            String t1 = String.join("", Arrays.copyOfRange(parts, 1, fromIdx));
                            String t2 = String.join("", Arrays.copyOfRange(parts, fromIdx + 1, parts.length));
                            bp1 = resolveBoardPin(t1);
                            bp2 = resolveBoardPin(t2);
                        }
                    }

                    // Case C: Single token is wire name: disconnect <W>
                    if (bp1 == null && bp2 == null && parts.length == 2) {
                        CpsDomain.Wire targetW = findWireByName(parts[1]);
                        if (targetW != null) {
                            wires.remove(targetW);
                            targetW.disconnectFromAll();
                            System.out.printf("[DISCONNECTED] Wire '%s' removed.%n", parts[1]);
                            return;
                        }
                    }

                    if (bp1 == null || bp2 == null) {
                        System.out.println("[ERR] Unable to resolve both terminals. Syntax: disconnect <board_name><pin_name> from <board_name><pin_name>");
                        return;
                    }

                    // Find wire connecting bp1.pin and bp2.pin
                    CpsDomain.Wire found = null;
                    for (CpsDomain.Wire w : wires) {
                        if ((w.pinA == bp1.pin && w.pinB == bp2.pin) || (w.pinA == bp2.pin && w.pinB == bp1.pin)) {
                            found = w;
                            break;
                        }
                    }

                    if (found != null) {
                        wires.remove(found);
                        found.disconnectFromAll();
                        if (bp1.pin.mode == CpsDomain.PinMode.DIGITAL_IN || bp1.pin.mode == CpsDomain.PinMode.ANALOG_IN) {
                            bp1.pin.setVoltage(0.0);
                        }
                        if (bp2.pin.mode == CpsDomain.PinMode.DIGITAL_IN || bp2.pin.mode == CpsDomain.PinMode.ANALOG_IN) {
                            bp2.pin.setVoltage(0.0);
                        }
                        System.out.printf("[DISCONNECTED] %s.%s ←/→ %s.%s  [%s]%n",
                            bp1.board.getId(), bp1.pin.pinId, bp2.board.getId(), bp2.pin.pinId, found.wireName);
                    } else {
                        System.out.printf("[ERR] No direct wire found connecting %s.%s and %s.%s%n",
                            bp1.board.getId(), bp1.pin.pinId, bp2.board.getId(), bp2.pin.pinId);
                    }
                }

                // ── wire connect <W1> to <W2> | wire connect <W1> to component <COMP> ──
                case "wire" -> {
                    if (parts.length < 2) {
                        System.out.println("[SYNTAX] wire connect <W1> to <W2> | wire connect <W1> to component <COMP> | wire list");
                        return;
                    }
                    if (parts[1].equalsIgnoreCase("list")) {
                        System.out.println("  Active Wires & Junction Nets:");
                        for (CpsDomain.Wire w : wires) {
                            String pA = (w.pinA != null) ? w.pinA.pinId : "(tap)";
                            String pB = (w.pinB != null) ? w.pinB.pinId : "(tap)";
                            String junc = w.isJunction() ? " [Junctions: " + w.getConnectedWireNames() + "]" : "";
                            System.out.printf("    %-20s  %s ←→ %s%s%n", w.wireName, pA, pB, junc);
                        }
                        return;
                    }
                    if (parts[1].equalsIgnoreCase("connect") || parts[1].equalsIgnoreCase("tap")) {
                        if (parts.length < 4) {
                            System.out.println("[SYNTAX] wire connect <W1> to <W2> | wire connect <W1> to component <COMP>");
                            return;
                        }
                        String w1Name = parts[2];
                        CpsDomain.Wire w1 = findWireByName(w1Name);
                        if (w1 == null) { System.out.println("[ERR] Wire not found: " + w1Name); return; }

                        int targetIdx = parts[3].equalsIgnoreCase("to") || parts[3].equalsIgnoreCase("into") ? 4 : 3;
                        if (targetIdx >= parts.length) {
                            System.out.println("[SYNTAX] Missing target wire or component.");
                            return;
                        }

                        if (parts[targetIdx].equalsIgnoreCase("component") && targetIdx + 1 < parts.length) {
                            String compName = parts[targetIdx + 1];
                            try {
                                w1.connectToComponent(compName, wires, boards());
                                System.out.printf("[WIRED JUNCTION] Wire '%s' connected to unique wire of component '%s'%n",
                                    w1.wireName, compName);
                            } catch (CpsDomain.AmbiguousWireConnectionException ex) {
                                System.out.println("[ERR] " + ex.getMessage());
                            }
                        } else if (parts[targetIdx].equalsIgnoreCase("wire") && targetIdx + 1 < parts.length) {
                            String w2Name = parts[targetIdx + 1];
                            CpsDomain.Wire w2 = findWireByName(w2Name);
                            if (w2 == null) { System.out.println("[ERR] Wire not found: " + w2Name); return; }
                            w1.connectToWire(w2);
                            System.out.printf("[WIRED JUNCTION] Wire '%s' ←→ Wire '%s'%n", w1.wireName, w2.wireName);
                        } else {
                            CpsDomain.Wire w2 = findWireByName(parts[targetIdx]);
                            if (w2 != null) {
                                w1.connectToWire(w2);
                                System.out.printf("[WIRED JUNCTION] Wire '%s' ←→ Wire '%s'%n", w1.wireName, w2.wireName);
                            } else {
                                String compName = parts[targetIdx];
                                try {
                                    w1.connectToComponent(compName, wires, boards());
                                    System.out.printf("[WIRED JUNCTION] Wire '%s' connected to unique wire of component '%s'%n",
                                        w1.wireName, compName);
                                } catch (CpsDomain.AmbiguousWireConnectionException ex) {
                                    System.out.println("[ERR] " + ex.getMessage());
                                }
                            }
                        }
                    }
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
                    } else if (fwName.toLowerCase().contains("blink")) {
                        int period = (parts.length > 3) ? Integer.parseInt(parts[3]) : 1000;
                        Esp32BlinkFirmware fw = new Esp32BlinkFirmware(period);
                        b.flashFirmware(fw);
                        System.out.printf("[FLASH] Successfully flashed %s on %s (Period = %dms)%n", fw.getName(), parts[1], period);
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

                // ── applet link RoomMasterMCU tcp 9090 | applet blink [port] ──
                case "applet" -> {
                    if (parts.length > 1 && parts[1].equalsIgnoreCase("link")) {
                        CpsDomain.Board b = boards().get(parts[2]);
                        if (b == null) { System.out.println("[ERR] Board not found: " + parts[2]); return; }
                        int port = Integer.parseInt(parts[4]);
                        b.enableSocketBridge(port);
                        System.out.printf("[BRIDGE]    %s → localhost:%d%n", parts[2], port);
                    } else if (parts.length > 1 && (parts[1].equalsIgnoreCase("blink") || parts[1].equalsIgnoreCase("led"))) {
                        int port = (parts.length > 2) ? Integer.parseInt(parts[2]) : 9094;
                        System.out.printf("[APPLET] Launching LED Blink Applet (port %d)...%n", port);
                        SwingUtilities.invokeLater(() -> new LedBlinkApplet("localhost", port));
                    } else if (parts.length > 1 && (parts[1].equalsIgnoreCase("dashboard") || parts[1].equalsIgnoreCase("dash") || parts[1].equalsIgnoreCase("thermal"))) {
                        int port = (parts.length > 2) ? Integer.parseInt(parts[2]) : 9092;
                        System.out.printf("[APPLET] Launching Thermal Bench Telemetry Dashboard (port %d)...%n", port);
                        SwingUtilities.invokeLater(() -> new ThermalBenchDashboardApplet("localhost", port));
                    } else if (parts.length > 1 && (parts[1].equalsIgnoreCase("input") || parts[1].equalsIgnoreCase("rps") || parts[1].equalsIgnoreCase("psu"))) {
                        int port = (parts.length > 2) ? Integer.parseInt(parts[2]) : 9093;
                        System.out.printf("[APPLET] Launching Regulated Power Supply & Motor Input Applet (port %d)...%n", port);
                        SwingUtilities.invokeLater(() -> new ThermalBenchInputApplet("localhost", port));
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

                // ── help / menu / ? ──────────────────────────────────────────
                case "help", "menu", "?" -> {
                    printBanner();
                }

                default -> System.out.println("[ERR] Unknown command. Type 'help' or 'menu' to list commands, or 'exit' to quit.");
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
            if (w.isJunction()) {
                w.syncNet();
            } else if (w.pinA != null && w.pinB != null) {
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

    private static CpsDomain.Wire findWireByName(String name) {
        if (name == null) return null;
        for (CpsDomain.Wire w : wires) {
            if (name.equalsIgnoreCase(w.wireName)) return w;
        }
        return null;
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

    private static class BoardPinPair {
        final CpsDomain.Board board;
        final CpsDomain.Pin pin;
        BoardPinPair(CpsDomain.Board b, CpsDomain.Pin p) { this.board = b; this.pin = p; }
    }

    private static BoardPinPair resolveBoardPin(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        String t = token.trim();
        // 1. Dot notation: "Board.Pin"
        if (t.contains(".")) {
            String[] parts = t.split("\\.", 2);
            CpsDomain.Board b = boards().get(parts[0]);
            if (b != null) {
                CpsDomain.Pin p = b.getPin(parts[1]);
                if (p != null) return new BoardPinPair(b, p);
            }
        }
        // 2. Exact board name match then remainder as pin: <board_name><pin_name>
        for (String bName : boards().keySet()) {
            if (t.toLowerCase().startsWith(bName.toLowerCase())) {
                String pCandidate = t.substring(bName.length());
                if (pCandidate.startsWith(".") || pCandidate.startsWith("_")) {
                    // Try with or without leading dot/underscore
                }
                CpsDomain.Board b = boards().get(bName);
                if (b != null && !pCandidate.isEmpty()) {
                    CpsDomain.Pin p = b.getPin(pCandidate);
                    if (p != null) return new BoardPinPair(b, p);
                    for (Map.Entry<String, CpsDomain.Pin> pe : b.pins.entrySet()) {
                        if (pe.getKey().equalsIgnoreCase(pCandidate)) {
                            return new BoardPinPair(b, pe.getValue());
                        }
                    }
                }
            }
        }
        return null;
    }
}
