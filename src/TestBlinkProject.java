import java.io.File;
import java.util.*;

public class TestBlinkProject {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  RUNNING ESP32 BLINK PROJECT VERIFICATION SUITE  ");
        System.out.println("==================================================");

        testAssetGeneration();
        testLayoutLoading();
        testFirmwareAndWirePropagation();
        testTelemetryJsonEmission();

        System.out.println("\n[SUCCESS] ALL 4 VERIFICATION TESTS PASSED (100% OK)!");
        System.out.println("==================================================");
        System.exit(0);
    }

    private static void testAssetGeneration() {
        System.out.print("[TEST 1/4] Verifying assets/led_module.png exists and is valid... ");
        File f = new File("assets/led_module.png");
        if (!f.exists() || f.length() == 0) {
            throw new AssertionError("assets/led_module.png not found or empty!");
        }
        System.out.printf("OK (%d KB)%n", f.length() / 1024);
    }

    private static void testLayoutLoading() throws Exception {
        System.out.print("[TEST 2/4] Verifying layouts/esp32_blink.json schema & deserialization... ");
        File f = new File("layouts/esp32_blink.json");
        if (!f.exists()) throw new AssertionError("layouts/esp32_blink.json missing!");

        CpsPersistence.LayoutData data = CpsPersistence.loadLayout(f);
        if (!"ESP32_Blink".equals(data.workspaceName)) {
            throw new AssertionError("Expected workspace name ESP32_Blink, got: " + data.workspaceName);
        }
        if (!data.boards.containsKey("ESP32_BlinkNode")) {
            throw new AssertionError("ESP32_BlinkNode board missing from layout!");
        }
        if (!data.boards.containsKey("StatusLEDModule")) {
            throw new AssertionError("StatusLEDModule board missing from layout!");
        }
        if (data.wires.size() < 2) {
            throw new AssertionError("Expected at least 2 wires, found: " + data.wires.size());
        }

        CpsDomain.Board mcu = data.boards.get("ESP32_BlinkNode");
        if (!(mcu.getFlashedFirmware() instanceof Esp32BlinkFirmware)) {
            throw new AssertionError("Expected Esp32BlinkFirmware flashed on ESP32_BlinkNode!");
        }

        CpsDomain.Board ledMod = data.boards.get("StatusLEDModule");
        if (!"assets/led_module.png".equals(ledMod.imagePath)) {
            throw new AssertionError("Expected imagePath assets/led_module.png on StatusLEDModule!");
        }
        if (ledMod.pinPositions.get("ANODE") == null || ledMod.pinPositions.get("CATHODE") == null) {
            throw new AssertionError("Expected calibrated pin positions for ANODE and CATHODE!");
        }

        System.out.println("OK (Boards: 2, Wires: 2, Calibrated: YES)");
    }

    private static void testFirmwareAndWirePropagation() throws Exception {
        System.out.print("[TEST 3/4] Verifying MCU firmware toggle & wire voltage propagation... ");
        File f = new File("layouts/esp32_blink.json");
        CpsPersistence.LayoutData data = CpsPersistence.loadLayout(f);

        CpsDomain.Board mcu = data.boards.get("ESP32_BlinkNode");
        CpsDomain.Board led = data.boards.get("StatusLEDModule");
        List<CpsDomain.Wire> wires = data.wires;

        CpsDomain.Pin gpio2 = mcu.getPin("GPIO_02");
        CpsDomain.Pin anode = led.getPin("ANODE");
        CpsDomain.Pin gnd = mcu.getPin("GND");
        CpsDomain.Pin cathode = led.getPin("CATHODE");

        if (gpio2 == null || anode == null || gnd == null || cathode == null) {
            throw new AssertionError("Required pins not found!");
        }

        // Initially setup was called, pin is LOW (0.0V)
        propagateWires(wires);
        if (Math.abs(anode.voltage - 0.0) > 0.01) {
            throw new AssertionError("Initial LED Anode voltage should be 0.0V, got: " + anode.voltage);
        }

        // Tick 1 -> Firmware loop toggles pin to HIGH (3.3V)
        mcu.tickLogic();
        propagateWires(wires);

        if (Math.abs(gpio2.voltage - 3.3) > 0.01) {
            throw new AssertionError("After tick 1, GPIO_02 should be 3.3V, got: " + gpio2.voltage);
        }
        if (Math.abs(anode.voltage - 3.3) > 0.01) {
            throw new AssertionError("Wire propagation failed: LED Anode should be 3.3V, got: " + anode.voltage);
        }

        // Tick 2 -> Firmware loop toggles pin to LOW (0.0V)
        mcu.tickLogic();
        propagateWires(wires);

        if (Math.abs(gpio2.voltage - 0.0) > 0.01) {
            throw new AssertionError("After tick 2, GPIO_02 should be 0.0V, got: " + gpio2.voltage);
        }
        if (Math.abs(anode.voltage - 0.0) > 0.01) {
            throw new AssertionError("Wire propagation failed: LED Anode should be 0.0V, got: " + anode.voltage);
        }

        System.out.println("OK (0.0V -> 3.3V -> 0.0V verified across wire)");
    }

    private static void testTelemetryJsonEmission() throws Exception {
        System.out.print("[TEST 4/4] Verifying JSON telemetry radio emission format... ");
        File f = new File("layouts/esp32_blink.json");
        CpsPersistence.LayoutData data = CpsPersistence.loadLayout(f);
        CpsDomain.Board mcu = data.boards.get("ESP32_BlinkNode");

        int testPort = (mcu.radioBridge != null) ? mcu.radioBridge.getPort() : 9096;
        if (mcu.radioBridge == null) mcu.enableRadioBridge(testPort);

        // Connect a test client socket to receive telemetry
        java.net.Socket client = new java.net.Socket("localhost", testPort);
        client.setSoTimeout(3000);
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));

        // Wait up to 1 second for active client registration
        long deadline = System.currentTimeMillis() + 1000;
        while (!mcu.radioBridge.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Tick to trigger telemetry
        mcu.tickLogic();

        String packet = in.readLine();
        client.close();

        if (packet == null || packet.isEmpty()) {
            throw new AssertionError("No radio packet received over port " + testPort);
        }

        if (!packet.contains("\"device\":\"ESP32_BlinkNode\"")) {
            throw new AssertionError("Packet missing device name: " + packet);
        }
        if (!packet.contains("\"ledPin\":\"GPIO_02\"")) {
            throw new AssertionError("Packet missing ledPin: " + packet);
        }
        if (!packet.contains("\"voltage\":3.30") && !packet.contains("\"voltage\":0.00")) {
            throw new AssertionError("Packet missing valid voltage: " + packet);
        }
        if (!packet.contains("\"freqHz\":")) {
            throw new AssertionError("Packet missing freqHz: " + packet);
        }

        // Verify MCU internal properties
        if (!"HIGH".equals(mcu.getProperty("ledState", "")) && !"LOW".equals(mcu.getProperty("ledState", ""))) {
            throw new AssertionError("MCU property ledState not updated!");
        }

        System.out.println("OK (Packet: " + packet + ")");
    }

    private static void propagateWires(List<CpsDomain.Wire> wires) {
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
    }
}
