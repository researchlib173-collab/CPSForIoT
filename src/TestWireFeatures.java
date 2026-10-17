import java.io.File;
import java.util.*;

public class TestWireFeatures {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================================");
        System.out.println("  RUNNING WIRE-TO-WIRE & COMPONENT CONNECTION TEST SUITE ");
        System.out.println("========================================================");

        testDirectWireToWireSyntax();
        testEquipotentialSignalPropagation();
        testWireToComponentUniqueOneToOne();
        testWireToComponentUniqueOneToMany();
        testWireToComponentZeroWiresFails();
        testWireToComponentAmbiguousMultipleWiresFails();
        testPersistenceOfWireJunctions();
        testCliWireCommands();

        System.out.println("\n[SUCCESS] ALL 8 WIRE SPECIFICATION TESTS PASSED (100% OK)!");
        System.out.println("========================================================");
        System.exit(0);
    }

    // ─── TEST 1: Direct Wire-to-Wire Syntax ──────────────────────────────────
    private static void testDirectWireToWireSyntax() {
        System.out.print("[TEST 1/8] Verifying Wire-to-Wire direct connection & fluent DSL syntax... ");

        CpsDomain.Pin p1 = new CpsDomain.Pin("P1", CpsDomain.PinMode.DIGITAL_OUT);
        CpsDomain.Pin p2 = new CpsDomain.Pin("P2", CpsDomain.PinMode.DIGITAL_IN);
        CpsDomain.Pin p3 = new CpsDomain.Pin("P3", CpsDomain.PinMode.DIGITAL_IN);

        // Standard connection
        HardwareDictionary.Wire w1 = new HardwareDictionary.Wire("W_TRUNK", p1, p2);
        HardwareDictionary.Wire w2 = new HardwareDictionary.Wire("W_BRANCH", p3, null);

        // Test wire.connectToWire
        w2.connectToWire(w1);

        if (!w1.isJunction() || !w2.isJunction()) {
            throw new AssertionError("Both wires should be junctions!");
        }
        if (!w1.connectedWires.contains(w2) || !w2.connectedWires.contains(w1)) {
            throw new AssertionError("Connection must be bidirectional!");
        }

        // Test fluent builder syntax in HardwareDictionary
        CpsDomain.Pin p4 = new CpsDomain.Pin("P4", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire w3 = HardwareDictionary.Wire.named("W_FLUENT")
            .from(p4)
            .toWire(w1);

        if (!w1.connectedWires.contains(w3)) {
            throw new AssertionError("Fluent toWire() failed to register in trunk wire!");
        }

        // Test HardwareDictionary.connect top-level syntax
        CpsDomain.Pin p5 = new CpsDomain.Pin("P5", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire w4 = new HardwareDictionary.Wire("W_TOP", p5, null);
        HardwareDictionary.connect(w4, w1);

        if (!w4.isConnectedTo(w1)) {
            throw new AssertionError("HardwareDictionary.connect(w4, w1) failed!");
        }

        System.out.println("OK (Fluent DSL, tap, connect, and builder verified)");
    }

    // ─── TEST 2: Equipotential Signal Propagation ───────────────────────────
    private static void testEquipotentialSignalPropagation() {
        System.out.print("[TEST 2/8] Verifying electrical potential propagation across wire junctions... ");

        CpsDomain.Pin driverPin = new CpsDomain.Pin("OUT_PIN", CpsDomain.PinMode.DIGITAL_OUT);
        CpsDomain.Pin sinkPin1  = new CpsDomain.Pin("SINK_1",  CpsDomain.PinMode.DIGITAL_IN);
        CpsDomain.Pin sinkPin2  = new CpsDomain.Pin("SINK_2",  CpsDomain.PinMode.DIGITAL_IN);
        CpsDomain.Pin sinkPin3  = new CpsDomain.Pin("SINK_3",  CpsDomain.PinMode.DIGITAL_IN);

        HardwareDictionary.Wire trunk = new HardwareDictionary.Wire("W_TRUNK", driverPin, sinkPin1);
        HardwareDictionary.Wire branch1 = new HardwareDictionary.Wire("W_BRANCH1", sinkPin2, null);
        HardwareDictionary.Wire branch2 = new HardwareDictionary.Wire("W_BRANCH2", sinkPin3, null);

        // Form a multi-drop bus / tree
        branch1.connect(trunk);
        branch2.connect(branch1); // Branch connected to branch!

        // Drive voltage to 3.3V
        driverPin.setVoltage(3.3);

        if (Math.abs(sinkPin1.voltage - 3.3) > 0.01) {
            throw new AssertionError("Trunk sinkPin1 failed to receive 3.3V!");
        }
        if (Math.abs(sinkPin2.voltage - 3.3) > 0.01) {
            throw new AssertionError("Branch1 sinkPin2 failed to receive 3.3V!");
        }
        if (Math.abs(sinkPin3.voltage - 3.3) > 0.01) {
            throw new AssertionError("Branch2 sinkPin3 failed to receive 3.3V across 2 hops!");
        }

        // Drive voltage to 0.0V (LOW)
        driverPin.setVoltage(0.0);

        if (Math.abs(sinkPin1.voltage - 0.0) > 0.01 ||
            Math.abs(sinkPin2.voltage - 0.0) > 0.01 ||
            Math.abs(sinkPin3.voltage - 0.0) > 0.01) {
            throw new AssertionError("Falling edge to 0.0V failed to propagate across junctions!");
        }

        System.out.println("OK (3.3V -> 0.0V propagated cleanly across 2 wire hops)");
    }

    // ─── TEST 3: Wire-to-Component Unique [One-to-One] ───────────────────────
    private static void testWireToComponentUniqueOneToOne() {
        System.out.print("[TEST 3/8] Verifying Wire-to-Component with unique [one to one] connection... ");

        CpsDomain.Board pir = new CpsDomain.Board("PIR_Sensor", "CUSTOM");
        pir.addPin("DATA", CpsDomain.PinMode.DIGITAL_OUT);

        CpsDomain.Board mcu = new CpsDomain.Board("MCU_Node", "ESP32");
        mcu.addPin("GPIO_12", CpsDomain.PinMode.DIGITAL_IN);

        List<CpsDomain.Wire> workspaceWires = new ArrayList<>();

        // Initially 1 wire connected to PIR
        CpsDomain.Wire pirWire = new CpsDomain.Wire("W_PIR_MAIN", pir.getPin("DATA"), mcu.getPin("GPIO_12"));
        workspaceWires.add(pirWire);

        // Now create a monitor/logger wire and connect it simply by specifying the component!
        CpsDomain.Pin scopePin = new CpsDomain.Pin("SCOPE_IN", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire scopeWire = new HardwareDictionary.Wire("W_SCOPE_TAP", scopePin, null);

        // Connect by component reference!
        scopeWire.connectToComponent(pir, workspaceWires);

        if (!scopeWire.connectedWires.contains(pirWire)) {
            throw new AssertionError("scopeWire should be connected to pirWire!");
        }
        if (!pirWire.connectedWires.contains(scopeWire)) {
            throw new AssertionError("pirWire should have scopeWire registered!");
        }

        // Verify voltage propagation
        pir.getPin("DATA").setVoltage(3.3);
        if (Math.abs(scopePin.voltage - 3.3) > 0.01) {
            throw new AssertionError("Scope pin did not receive voltage from PIR through component connection!");
        }

        System.out.println("OK (Unique 1-to-1 connection resolved and verified)");
    }

    // ─── TEST 4: Wire-to-Component Unique [One-to-Many] ──────────────────────
    private static void testWireToComponentUniqueOneToMany() {
        System.out.print("[TEST 4/8] Verifying Wire-to-Component with unique [one to many] connection... ");

        CpsDomain.Board doorSensor = new CpsDomain.Board("DoorSensor", "CUSTOM");
        doorSensor.addPin("STATE", CpsDomain.PinMode.DIGITAL_OUT);

        CpsDomain.Board mcu = new CpsDomain.Board("MainMCU", "ESP32");
        mcu.addPin("GPIO_14", CpsDomain.PinMode.DIGITAL_IN);

        List<CpsDomain.Wire> workspaceWires = new ArrayList<>();

        // Wire 1 from door sensor to MCU
        CpsDomain.Wire doorWire = new CpsDomain.Wire("W_DOOR_BUS", doorSensor.getPin("STATE"), mcu.getPin("GPIO_14"));
        workspaceWires.add(doorWire);

        // Another board tapped into doorWire
        CpsDomain.Pin tap1 = new CpsDomain.Pin("ALARM_TAP", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire alarmWire = new HardwareDictionary.Wire("W_ALARM", tap1, null);
        alarmWire.connectToWire(doorWire);
        workspaceWires.add(alarmWire);

        // Now connect a 3rd wire by simply specifying component "DoorSensor".
        // DoorSensor has exactly 1 wire attached to its pin (W_DOOR_BUS), which forms a 1-to-many junction!
        CpsDomain.Pin tap2 = new CpsDomain.Pin("LOGGER_TAP", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire loggerWire = new HardwareDictionary.Wire("W_LOGGER", tap2, null);

        loggerWire.connectToComponent(doorSensor, workspaceWires);

        if (!loggerWire.connectedWires.contains(doorWire)) {
            throw new AssertionError("loggerWire must connect to W_DOOR_BUS!");
        }

        System.out.println("OK (Unique 1-to-many bus tap resolved and verified)");
    }

    // ─── TEST 5: Wire-to-Component Fails when 0 wires exist ─────────────────
    private static void testWireToComponentZeroWiresFails() {
        System.out.print("[TEST 5/8] Verifying Wire-to-Component failure when component has 0 wires... ");

        CpsDomain.Board disconnectedBoard = new CpsDomain.Board("FloatingModule", "CUSTOM");
        disconnectedBoard.addPin("SIG", CpsDomain.PinMode.DIGITAL_IN);

        List<CpsDomain.Wire> workspaceWires = new ArrayList<>();

        CpsDomain.Pin probe = new CpsDomain.Pin("PROBE", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire probeWire = new HardwareDictionary.Wire("W_PROBE", probe, null);

        boolean exceptionThrown = false;
        try {
            probeWire.connectToComponent(disconnectedBoard, workspaceWires);
        } catch (CpsDomain.AmbiguousWireConnectionException ex) {
            exceptionThrown = true;
            if (!ex.getMessage().contains("No wires are currently connected")) {
                throw new AssertionError("Unexpected error message: " + ex.getMessage());
            }
        }

        if (!exceptionThrown) {
            throw new AssertionError("Should have thrown AmbiguousWireConnectionException for 0 wires!");
        }

        System.out.println("OK (Rejected with clear descriptive error message)");
    }

    // ─── TEST 6: Wire-to-Component Fails when Ambiguous (>1 wires) ──────────
    private static void testWireToComponentAmbiguousMultipleWiresFails() {
        System.out.print("[TEST 6/8] Verifying Wire-to-Component rejection when ambiguous (>1 wires)... ");

        CpsDomain.Board esp32 = new CpsDomain.Board("ESP32_Complex", "ESP32_WROOM");
        esp32.addPin("GPIO_12", CpsDomain.PinMode.DIGITAL_IN);
        esp32.addPin("GPIO_14", CpsDomain.PinMode.DIGITAL_IN);
        esp32.addPin("GPIO_27", CpsDomain.PinMode.ANALOG_IN);

        CpsDomain.Board sensorA = new CpsDomain.Board("SensorA", "CUSTOM");
        sensorA.addPin("OUT", CpsDomain.PinMode.DIGITAL_OUT);

        CpsDomain.Board sensorB = new CpsDomain.Board("SensorB", "CUSTOM");
        sensorB.addPin("OUT", CpsDomain.PinMode.DIGITAL_OUT);

        List<CpsDomain.Wire> workspaceWires = new ArrayList<>();
        CpsDomain.Wire wireA = new CpsDomain.Wire("W_A", sensorA.getPin("OUT"), esp32.getPin("GPIO_12"));
        CpsDomain.Wire wireB = new CpsDomain.Wire("W_B", sensorB.getPin("OUT"), esp32.getPin("GPIO_14"));
        workspaceWires.add(wireA);
        workspaceWires.add(wireB);

        // Now an external wire attempts to connect by simply saying: connectToComponent(esp32)!
        // Since esp32 has 2 distinct wires (W_A and W_B), the connection is AMBIGUOUS!
        CpsDomain.Pin probe = new CpsDomain.Pin("PROBE", CpsDomain.PinMode.DIGITAL_IN);
        HardwareDictionary.Wire probeWire = new HardwareDictionary.Wire("W_AMBIGUOUS_TAP", probe, null);

        boolean caughtAmbiguity = false;
        try {
            probeWire.connectToComponent(esp32, workspaceWires);
        } catch (CpsDomain.AmbiguousWireConnectionException ex) {
            caughtAmbiguity = true;
            if (!ex.getMessage().contains("AMBIGUOUS") || !ex.getMessage().contains("2 connected wires")) {
                throw new AssertionError("Expected AMBIGUOUS error message, got: " + ex.getMessage());
            }
        }

        if (!caughtAmbiguity) {
            throw new AssertionError("Should have caught ambiguity for component with multiple wires!");
        }

        System.out.println("OK (Properly rejected ambiguity with required rule statement)");
    }

    // ─── TEST 7: Persistence of Wire-to-Wire Junctions ──────────────────────
    private static void testPersistenceOfWireJunctions() throws Exception {
        System.out.print("[TEST 7/8] Verifying persistence (save/load) of wire-to-wire junctions... ");

        Map<String, CpsDomain.Board> boards = new LinkedHashMap<>();
        CpsDomain.Board b1 = new CpsDomain.Board("B1", "CUSTOM");
        b1.addPin("OUT", CpsDomain.PinMode.DIGITAL_OUT);
        CpsDomain.Board b2 = new CpsDomain.Board("B2", "CUSTOM");
        b2.addPin("IN1", CpsDomain.PinMode.DIGITAL_IN);
        CpsDomain.Board b3 = new CpsDomain.Board("B3", "CUSTOM");
        b3.addPin("IN2", CpsDomain.PinMode.DIGITAL_IN);

        boards.put("B1", b1);
        boards.put("B2", b2);
        boards.put("B3", b3);

        List<CpsDomain.Wire> wires = new ArrayList<>();
        CpsDomain.Wire mainWire = new CpsDomain.Wire("W_MAIN", b1.getPin("OUT"), b2.getPin("IN1"));
        CpsDomain.Wire tapWire  = new CpsDomain.Wire("W_TAP", b3.getPin("IN2"), null);
        tapWire.connectToWire(mainWire);

        wires.add(mainWire);
        wires.add(tapWire);

        File tempFile = new File("layouts/test_wire_junction_layout.json");
        CpsPersistence.saveLayout("TestJunctionWorkspace", boards, wires, 0, tempFile);

        CpsPersistence.LayoutData loaded = CpsPersistence.loadLayout(tempFile);
        tempFile.delete();

        if (loaded.wires.size() != 2) {
            throw new AssertionError("Expected 2 wires, got: " + loaded.wires.size());
        }

        CpsDomain.Wire loadedMain = null, loadedTap = null;
        for (CpsDomain.Wire w : loaded.wires) {
            if ("W_MAIN".equals(w.wireName)) loadedMain = w;
            if ("W_TAP".equals(w.wireName))  loadedTap = w;
        }

        if (loadedMain == null || loadedTap == null) {
            throw new AssertionError("Failed to load W_MAIN or W_TAP!");
        }

        if (!loadedMain.connectedWires.contains(loadedTap) || !loadedTap.connectedWires.contains(loadedMain)) {
            throw new AssertionError("Reconstituted wires must retain junction relationship!");
        }

        System.out.println("OK (Junction graph preserved across JSON serialization)");
    }

    // ─── TEST 8: CLI Commands for Wire Connections ──────────────────────────
    private static void testCliWireCommands() {
        System.out.print("[TEST 8/8] Verifying CLI wire-to-wire and wire-to-component commands... ");

        SmartRoomCLI.process("workspace create CliWireTest");
        SmartRoomCLI.process("create board SensorNode");
        SmartRoomCLI.process("add pin SensorNode OUT DIGITAL_OUT");
        SmartRoomCLI.process("create board McuNode");
        SmartRoomCLI.process("add pin McuNode IN DIGITAL_IN");
        SmartRoomCLI.process("create board AuxLed");
        SmartRoomCLI.process("add pin AuxLed ANODE DIGITAL_IN");

        // Connect standard wire
        SmartRoomCLI.process("connect SensorNode OUT to McuNode IN W_MAIN_SENSOR");

        // Connect wire to wire via CLI: connect wire W_LED_TAP to wire W_MAIN_SENSOR
        SmartRoomCLI.process("connect AuxLed ANODE to SensorNode OUT W_LED_TAP");
        SmartRoomCLI.process("connect wire W_LED_TAP to wire W_MAIN_SENSOR");

        CpsDomain.Wire wMain = findCliWire("W_MAIN_SENSOR");
        CpsDomain.Wire wTap  = findCliWire("W_LED_TAP");

        if (wMain == null || wTap == null) {
            throw new AssertionError("Wires not found in CLI context!");
        }
        if (!wMain.connectedWires.contains(wTap)) {
            throw new AssertionError("CLI connect wire W_LED_TAP to wire W_MAIN_SENSOR failed!");
        }

        // Test wire list command
        SmartRoomCLI.process("wire list");

        // Test disconnect <board_name><pin_name> from <board_name><pin_name> with and without dots
        SmartRoomCLI.process("disconnect AuxLed.ANODE from SensorNode.OUT");
        if (findCliWire("W_LED_TAP") != null) {
            throw new AssertionError("disconnect AuxLed.ANODE from SensorNode.OUT failed to remove wire!");
        }

        SmartRoomCLI.process("connect AuxLed ANODE to SensorNode OUT W_LED_TAP2");
        if (findCliWire("W_LED_TAP2") == null) {
            throw new AssertionError("W_LED_TAP2 not created");
        }
        SmartRoomCLI.process("disconnect AuxLedANODE from SensorNodeOUT");
        if (findCliWire("W_LED_TAP2") != null) {
            throw new AssertionError("disconnect AuxLedANODE from SensorNodeOUT failed to remove wire!");
        }

        System.out.println("OK (CLI connect & disconnect commands verified)");
    }

    private static CpsDomain.Wire findCliWire(String name) {
        for (CpsDomain.Wire w : SmartRoomCLI.wires) {
            if (name.equalsIgnoreCase(w.wireName)) return w;
        }
        return null;
    }
}
