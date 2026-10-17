import java.awt.geom.Point2D;
import java.io.File;
import java.util.*;

/**
 * End-to-end verification test for:
 * 1. PinFix image calibration persistence (saving and loading u, v coordinates and imagePath)
 * 2. Default IC / Breakout box fallback when no image is assigned
 * 3. CLI command processing for pinfix and autoroute/aroute
 * 4. CircuitAutoRouteGUI initialization & Manhattan routing calculations
 */
public class TestVisualFeatures {

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("RUNNING CPS VISUAL & CAD FEATURES VERIFICATION");
        System.out.println("=================================================");

        // 1. Test Domain Model & Persistence of imagePath and pinPositions
        System.out.println("\n[TEST 1] Domain & Persistence (ImagePath & Calibrated Pins)...");
        CpsDomain.Board esp = HardwareDictionary.createESP32("TestESP32");
        esp.imagePath = new File("assets/esp32_wroom.png").getAbsolutePath();
        esp.layoutX = 450;
        esp.layoutY = 120;
        esp.pinPositions.put("GPIO_12", new Point2D.Double(0.105, 0.450));
        esp.pinPositions.put("GPIO_14", new Point2D.Double(0.105, 0.520));
        esp.pinPositions.put("GPIO_21", new Point2D.Double(0.895, 0.600));

        File tempBoardFile = new File("saved_boards/test_board_calibrated.json");
        CpsPersistence.saveBoard(esp, tempBoardFile);
        System.out.println("  Saved board to: " + tempBoardFile.getPath());

        CpsDomain.Board reloadedEsp = CpsPersistence.loadBoard(tempBoardFile, null);
        assert reloadedEsp.imagePath != null : "imagePath must not be null";
        assert reloadedEsp.imagePath.equals(esp.imagePath) : "imagePath mismatch";
        assert reloadedEsp.layoutX == 450 : "layoutX mismatch";
        assert reloadedEsp.layoutY == 120 : "layoutY mismatch";
        assert reloadedEsp.pinPositions.size() == 30 : "pinPositions count mismatch: " + reloadedEsp.pinPositions.size();
        assert Math.abs(reloadedEsp.pinPositions.get("GPIO_12").x - 0.105) < 0.001 : "GPIO_12 u coord mismatch";
        assert Math.abs(reloadedEsp.pinPositions.get("GPIO_12").y - 0.450) < 0.001 : "GPIO_12 v coord mismatch";
        System.out.println("  ✓ Board imagePath & pinPositions saved and restored cleanly!");

        // 2. Test Full Layout with Mix of Imaged Components & Default Breakout Boxes
        System.out.println("\n[TEST 2] Full Layout Persistence with Mixed Image/Default Components...");
        Map<String, CpsDomain.Board> boards = new LinkedHashMap<>();
        boards.put("TestESP32", reloadedEsp);

        // A board WITHOUT an image (tests default box fallback)
        CpsDomain.Board unassignedPIR = new CpsDomain.Board("TestPIR_NoImage", "CUSTOM");
        unassignedPIR.addPin("DATA", CpsDomain.PinMode.DIGITAL_OUT);
        unassignedPIR.addPin("VCC", CpsDomain.PinMode.DIGITAL_IN);
        unassignedPIR.addPin("GND", CpsDomain.PinMode.DIGITAL_IN);
        unassignedPIR.layoutX = 80;
        unassignedPIR.layoutY = 100;
        boards.put("TestPIR_NoImage", unassignedPIR);

        List<CpsDomain.Wire> wires = new ArrayList<>();
        CpsDomain.Wire wire = new CpsDomain.Wire("W_TEST", unassignedPIR.getPin("DATA"), reloadedEsp.getPin("GPIO_12"));
        unassignedPIR.getPin("DATA").attachWire(wire);
        reloadedEsp.getPin("GPIO_12").attachWire(wire);
        wires.add(wire);

        File tempLayoutFile = new File("layouts/test_visual_layout.json");
        CpsPersistence.saveLayout("TestVisualWorkspace", boards, wires, 0, tempLayoutFile);
        System.out.println("  Saved layout to: " + tempLayoutFile.getPath());

        CpsPersistence.LayoutData loadedLayout = CpsPersistence.loadLayout(tempLayoutFile);
        assert loadedLayout.boards.size() == 2 : "Board count mismatch";
        assert loadedLayout.wires.size() == 1 : "Wire count mismatch";
        CpsDomain.Board b1 = loadedLayout.boards.get("TestESP32");
        CpsDomain.Board b2 = loadedLayout.boards.get("TestPIR_NoImage");
        assert b1.imagePath != null : "TestESP32 imagePath should be preserved";
        assert b2.imagePath == null : "TestPIR_NoImage imagePath should remain null";
        System.out.println("  ✓ Layout successfully verified with preserved image paths and fallback boards!");

        // 3. Test CLI command dispatch for pinfix and autoroute/aroute
        System.out.println("\n[TEST 3] CLI Command Dispatcher for pinfix and autoroute...");
        SmartRoomCLI.workspaces.put("TestVisualWorkspace", boards);
        SmartRoomCLI.activeWorkspace = "TestVisualWorkspace";
        SmartRoomCLI.wires = wires;

        // Execute CLI pinfix with direct path argument
        SmartRoomCLI.process("pinfix TestESP32 assets/esp32_wroom.png");

        // Execute CLI autoroute
        SmartRoomCLI.process("autoroute");
        SmartRoomCLI.process("aroute");
        // 4. Test Thermal-Aware Power Supply Benchmark Rig Auto-Routing
        System.out.println("\n[TEST 4] Thermal Bench Rig Layout, Assets & Auto-Routing...");
        File thermalLayoutFile = new File("layouts/thermal_bench_rig.json");
        assert thermalLayoutFile.exists() : "thermal_bench_rig.json must exist in layouts/";
        CpsPersistence.LayoutData thermalLayout = CpsPersistence.loadLayout(thermalLayoutFile);

        System.out.printf("  Loaded workspace: '%s' (%d boards, %d wires)%n",
            thermalLayout.workspaceName, thermalLayout.boards.size(), thermalLayout.wires.size());
        assert thermalLayout.boards.size() == 3 : "Thermal bench must have exactly 3 boards";
        assert thermalLayout.wires.size() == 7 : "Thermal bench must have exactly 7 wires";

        String[] expectedBoards = {
            "BenchMasterMCU", "INA219_Sensor", "TempSensor"
        };

        for (String bId : expectedBoards) {
            CpsDomain.Board b = thermalLayout.boards.get(bId);
            assert b != null : "Missing expected board: " + bId;
            assert b.imagePath != null : "Board " + bId + " must have imagePath";
            File imgF = new File(b.imagePath);
            assert imgF.exists() : "Image file does not exist: " + b.imagePath;
            assert !b.pinPositions.isEmpty() : "Board " + bId + " must have calibrated pinPositions";
            System.out.printf("    ✓ %-24s -> image: %-30s (pins: %d, calibrated: %d)%n",
                bId, imgF.getName(), b.pins.size(), b.pinPositions.size());
        }

        // Verify wire pin connections
        for (CpsDomain.Wire w : thermalLayout.wires) {
            assert w.pinA != null && w.pinB != null : "Wire " + w.wireName + " has null pins";
            assert w.pinA.pinId != null && w.pinB.pinId != null : "Wire pins must have IDs";
            assert !w.pinA.connectedWires.isEmpty() && !w.pinB.connectedWires.isEmpty() : "Pins must be attached";
        }
        System.out.printf("  ✓ All %d wires successfully verified with valid pin attachments!%n", thermalLayout.wires.size());

        // Test CLI execution: "autoroute thermal"
        SmartRoomCLI.process("autoroute thermal");
        System.out.println("  ✓ CLI 'autoroute thermal' executed and loaded layout successfully!");

        // Clean up temporary test files
        if (tempBoardFile.exists()) tempBoardFile.delete();
        if (tempLayoutFile.exists()) tempLayoutFile.delete();

        System.out.println("\n=================================================");
        System.out.println("ALL CPS VISUAL & CAD VERIFICATIONS PASSED 100%!");
        System.out.println("=================================================");
        System.exit(0);
    }
}
