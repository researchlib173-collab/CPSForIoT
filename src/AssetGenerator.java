import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Generates clean, crisp, schematic component diagram PNGs in assets/
 * for immediate use with PinFixCalibrator and CircuitAutoRouteGUI.
 */
public class AssetGenerator {

    public static final String FONT_FAMILY = "Space Mono";

    public static void main(String[] args) throws IOException {
        File assetsDir = new File("assets");
        if (!assetsDir.exists()) assetsDir.mkdirs();

        generateESP32(new File(assetsDir, "esp32_wroom.png"));
        generatePIR(new File(assetsDir, "pir_motion_sensor.png"));
        generateDoorSensor(new File(assetsDir, "magnetic_door_sensor.png"));
        generateRadarSensor(new File(assetsDir, "radar_sensor.png"));
        generateRelayModule(new File(assetsDir, "relay_module.png"));
        generateElectronicLoad(new File(assetsDir, "electronic_load.png"));
        generateBenchPowerSource(new File(assetsDir, "bench_power_source.png"));
        generatePowerSupplyUnderTest(new File(assetsDir, "power_supply_under_test.png"));
        generateThermocoupleArray(new File(assetsDir, "thermocouple_array.png"));
        generateThermalChamber(new File(assetsDir, "thermal_chamber.png"));
        generateCoolingFan(new File(assetsDir, "cooling_fan_module.png"));
        generateLedModule(new File(assetsDir, "led_module.png"));

        System.out.println("[ASSETS] Generated 12 component images in assets/");
    }

    // 1. ESP32-WROOM DevKit (400 x 560 px)
    private static void generateESP32(File file) throws IOException {
        int w = 400, h = 560;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // PCB Board (Dark matte black/blue)
        g2.setColor(new Color(24, 28, 38));
        g2.fillRoundRect(30, 20, 340, 520, 16, 16);
        g2.setColor(new Color(60, 75, 100));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(30, 20, 340, 520, 16, 16);

        // Top PCB Antenna (Serpentine gold trace on dark PCB)
        g2.setColor(new Color(15, 18, 24));
        g2.fillRect(70, 30, 260, 60);
        g2.setColor(new Color(212, 175, 55)); // Gold trace
        g2.setStroke(new BasicStroke(3.0f));
        int ax = 90, ay = 45;
        g2.drawPolyline(new int[]{ax, ax+20, ax+20, ax+40, ax+40, ax+60, ax+60, ax+80, ax+80, ax+100, ax+100, ax+120, ax+120, ax+140, ax+140, ax+160, ax+160, ax+180, ax+180, ax+200, ax+200, ax+220},
                        new int[]{ay+25, ay+25, ay, ay, ay+25, ay+25, ay, ay, ay+25, ay+25, ay, ay, ay+25, ay+25, ay, ay, ay+25, ay+25, ay, ay, ay+25, ay+25}, 22);

        // Metal RF Shield Can
        g2.setColor(new Color(190, 195, 205));
        g2.fillRoundRect(80, 110, 240, 190, 8, 8);
        g2.setColor(new Color(140, 145, 155));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(80, 110, 240, 190, 8, 8);

        // Can Label
        g2.setColor(new Color(40, 45, 55));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 18));
        g2.drawString("ESP32-WROOM-32", 110, 170);
        g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        g2.drawString("Wi-Fi + BT + BLE MCU", 125, 200);
        g2.drawString("FCC ID: 2AC7Z-ESPWROOM32", 105, 230);

        // Micro-USB Port at Bottom
        g2.setColor(new Color(160, 165, 175));
        g2.fillRoundRect(150, 490, 100, 45, 4, 4);
        g2.setColor(new Color(40, 45, 55));
        g2.fillRect(165, 505, 70, 20);

        // Buttons EN & BOOT
        g2.setColor(new Color(220, 220, 225));
        g2.fillRoundRect(60, 480, 40, 30, 4, 4);
        g2.fillRoundRect(300, 480, 40, 30, 4, 4);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        g2.drawString("EN", 72, 470);
        g2.drawString("BOOT", 305, 470);

        // Status LEDs
        g2.setColor(new Color(255, 60, 60)); // Red PWR LED
        g2.fillOval(120, 330, 10, 10);
        g2.setColor(new Color(0, 229, 255)); // Blue GPIO2 LED
        g2.fillOval(145, 330, 10, 10);

        // Left & Right Pin Headers (Dual In-Line 19 pins each)
        drawPinHeaderColumn(g2, 42, 50, 19, 24, true);
        drawPinHeaderColumn(g2, 358, 50, 19, 24, false);

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 2. PIR Motion Sensor (HC-SR501 style, 360 x 300 px)
    private static void generatePIR(File file) throws IOException {
        int w = 360, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // PCB (Blue)
        g2.setColor(new Color(20, 50, 120));
        g2.fillRoundRect(30, 30, 300, 240, 12, 12);
        g2.setColor(new Color(40, 90, 190));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(30, 30, 300, 240, 12, 12);

        // White Fresnel Lens Dome in Center
        g2.setColor(new Color(245, 245, 250));
        g2.fillOval(100, 50, 160, 160);
        g2.setColor(new Color(200, 205, 215));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawOval(100, 50, 160, 160);

        // Lens honeycomb facets
        g2.setColor(new Color(220, 225, 235));
        for (int r = 20; r < 80; r += 20) {
            g2.drawOval(180 - r, 130 - r, r * 2, r * 2);
        }

        // Two orange adjustment pots
        g2.setColor(new Color(230, 120, 20));
        g2.fillRoundRect(50, 70, 35, 45, 4, 4);
        g2.fillRoundRect(50, 150, 35, 45, 4, 4);

        // Bottom 3-Pin Header: VCC, OUT, GND
        int py = 250;
        drawSolderPad(g2, 130, py, "VCC");
        drawSolderPad(g2, 180, py, "DATA");
        drawSolderPad(g2, 230, py, "GND");

        // Module text
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("PIR MOTION SENSOR", 115, 230);

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 3. Magnetic Door Reed Sensor (360 x 220 px)
    private static void generateDoorSensor(File file) throws IOException {
        int w = 360, h = 220;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // Plastic Enclosure (Off-white / grey)
        g2.setColor(new Color(235, 238, 242));
        g2.fillRoundRect(40, 40, 280, 140, 14, 14);
        g2.setColor(new Color(180, 190, 200));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(40, 40, 280, 140, 14, 14);

        // Reed switch internal glass tube graphic
        g2.setColor(new Color(200, 225, 240, 180));
        g2.fillRoundRect(90, 85, 180, 35, 16, 16);
        g2.setColor(new Color(120, 150, 180));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(90, 85, 180, 35, 16, 16);

        // Reed contacts
        g2.setColor(new Color(212, 175, 55)); // Gold contacts
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawLine(105, 102, 175, 102);
        g2.drawLine(173, 98, 255, 98);

        // 2 Contact Pins at Left
        drawSolderPad(g2, 55, 80, "PIN_A");
        drawSolderPad(g2, 55, 130, "PIN_B");

        // Title
        g2.setColor(new Color(50, 60, 75));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        g2.drawString("MAGNETIC REED DOOR SWITCH", 85, 68);

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 4. 24GHz mmWave Radar Sensor (360 x 300 px)
    private static void generateRadarSensor(File file) throws IOException {
        int w = 360, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // Green RF PCB
        g2.setColor(new Color(18, 75, 42));
        g2.fillRoundRect(40, 30, 280, 240, 10, 10);
        g2.setColor(new Color(35, 125, 75));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(40, 30, 280, 240, 10, 10);

        // Gold Patch Antenna Array (Top half)
        g2.setColor(new Color(225, 185, 60));
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                int px = 85 + col * 50;
                int py = 55 + row * 40;
                g2.fillRect(px, py, 35, 25);
            }
        }

        // Radar Transceiver IC
        g2.setColor(new Color(25, 30, 35));
        g2.fillRoundRect(135, 150, 90, 60, 6, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        g2.drawString("24GHz FMCW", 145, 185);

        // 4 Pin Header at Bottom
        int py = 250;
        drawSolderPad(g2, 105, py, "VCC");
        drawSolderPad(g2, 155, py, "GND");
        drawSolderPad(g2, 205, py, "RADAR_OUT");
        drawSolderPad(g2, 255, py, "RX");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 5. Dual Channel Relay Module (420 x 340 px)
    private static void generateRelayModule(File file) throws IOException {
        int w = 420, h = 340;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // PCB (Classic Royal Blue)
        g2.setColor(new Color(20, 60, 140));
        g2.fillRoundRect(30, 30, 360, 280, 12, 12);
        g2.setColor(new Color(50, 100, 210));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(30, 30, 360, 280, 12, 12);

        // Two Blue Songle Relay Cubes
        g2.setColor(new Color(0, 102, 204));
        g2.fillRoundRect(160, 50, 130, 100, 6, 6);
        g2.fillRoundRect(160, 170, 130, 100, 6, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("SONGLE RELAY 1", 175, 100);
        g2.drawString("SONGLE RELAY 2", 175, 220);
        g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
        g2.drawString("10A 250VAC", 195, 120);
        g2.drawString("10A 250VAC", 195, 240);

        // Screw Terminal Blocks on Right (High Voltage AC side)
        g2.setColor(new Color(40, 140, 80)); // Green screw terminals
        g2.fillRoundRect(310, 55, 65, 90, 6, 6);
        g2.fillRoundRect(310, 175, 65, 90, 6, 6);

        // Input Control Pins on Left: VCC, GND, IN1, IN2
        drawSolderPad(g2, 50, 80, "VCC");
        drawSolderPad(g2, 50, 130, "GND");
        drawSolderPad(g2, 50, 180, "IN1");
        drawSolderPad(g2, 50, 230, "IN2");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 6. Programmable DC Electronic Load Module (420 x 340 px)
    private static void generateElectronicLoad(File file) throws IOException {
        int w = 420, h = 340;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // Dark Industrial Chassis
        g2.setColor(new Color(32, 38, 48));
        g2.fillRoundRect(30, 30, 360, 280, 12, 12);
        g2.setColor(new Color(70, 85, 110));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(30, 30, 360, 280, 12, 12);

        // Digital LED Display Screen
        g2.setColor(new Color(15, 20, 25));
        g2.fillRoundRect(50, 50, 180, 90, 6, 6);
        g2.setColor(new Color(0, 230, 118)); // Green digital readout
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 22));
        g2.drawString("12.00 V", 65, 85);
        g2.drawString(" 8.50 A", 65, 120);

        // Big Aluminium Heatsink & Cooling Fan
        g2.setColor(new Color(120, 130, 145));
        g2.fillRoundRect(250, 50, 120, 140, 8, 8);
        g2.setColor(new Color(30, 35, 45));
        g2.fillOval(265, 75, 90, 90);
        g2.setColor(new Color(70, 80, 95));
        g2.fillOval(295, 105, 30, 30);

        // High Current Heavy Binding Posts at Bottom: LOAD+, LOAD-, SENSE+, SENSE-
        drawSolderPad(g2, 80, 270, "LOAD+");
        drawSolderPad(g2, 160, 270, "LOAD-");
        drawSolderPad(g2, 240, 270, "SENSE+");
        drawSolderPad(g2, 320, 270, "SENSE-");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 7. Adjustable Benchtop DC Power Supply (400 x 300 px)
    private static void generateBenchPowerSource(File file) throws IOException {
        int w = 400, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // Metallic Slate Chassis
        g2.setColor(new Color(26, 32, 44));
        g2.fillRoundRect(20, 20, 360, 260, 14, 14);
        g2.setColor(new Color(66, 80, 102));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(20, 20, 360, 260, 14, 14);

        // Top Title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("BENCH DC POWER SUPPLY", 100, 46);

        // Dual LED Digital Meters (Voltage & Current)
        g2.setColor(new Color(12, 16, 22));
        g2.fillRoundRect(40, 60, 150, 75, 6, 6);
        g2.fillRoundRect(210, 60, 150, 75, 6, 6);

        g2.setColor(new Color(255, 61, 0)); // Red Voltmeter
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 20));
        g2.drawString("24.00 V", 65, 105);

        g2.setColor(new Color(0, 230, 118)); // Green Ammeter
        g2.drawString(" 8.50 A", 235, 105);

        // Rotary Control Knobs
        g2.setColor(new Color(75, 85, 105));
        g2.fillOval(75, 155, 45, 45);
        g2.fillOval(175, 155, 45, 45);
        g2.fillOval(275, 155, 45, 45);

        g2.setColor(Color.LIGHT_GRAY);
        g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 9));
        g2.drawString("V-COARSE", 70, 215);
        g2.drawString("V-FINE", 178, 215);
        g2.drawString("I-LIMIT", 276, 215);

        // Bottom Heavy Binding Posts: VIN_OUT, IIN_SHUNT, GND
        drawSolderPad(g2, 120, 250, "VIN_OUT");
        drawSolderPad(g2, 200, 250, "IIN_SHUNT");
        drawSolderPad(g2, 280, 250, "GND");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 8. Power Supply Under Test (PSU) Converter Board (420 x 320 px)
    private static void generatePowerSupplyUnderTest(File file) throws IOException {
        int w = 420, h = 320;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // FR4 PCB (Dark Industrial Green)
        g2.setColor(new Color(20, 48, 32));
        g2.fillRoundRect(20, 20, 380, 280, 12, 12);
        g2.setColor(new Color(40, 100, 68));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(20, 20, 380, 280, 12, 12);

        // Title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("POWER SUPPLY UNDER TEST (PSU)", 85, 45);

        // Extruded Aluminium Heatsink for Switching MOSFETs
        g2.setColor(new Color(110, 120, 135));
        g2.fillRoundRect(50, 65, 80, 120, 6, 6);
        g2.setColor(new Color(60, 70, 85));
        for (int fx = 60; fx < 125; fx += 15) {
            g2.fillRect(fx, 65, 6, 120);
        }
        g2.setColor(new Color(255, 204, 0));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        g2.drawString("MOSFET", 64, 200);

        // Heavy Ferrite Core Transformer in Center
        g2.setColor(new Color(30, 32, 40));
        g2.fillRoundRect(160, 75, 100, 95, 8, 8);
        g2.setColor(new Color(184, 115, 51)); // Copper windings
        g2.fillRect(175, 85, 70, 75);
        g2.setColor(new Color(212, 140, 60));
        for (int wy = 90; wy < 155; wy += 8) {
            g2.drawLine(175, wy, 245, wy);
        }
        g2.setColor(Color.WHITE);
        g2.drawString("TRANSFORMER", 168, 190);

        // Filter Capacitors on Right
        g2.setColor(new Color(40, 50, 70));
        g2.fillOval(290, 70, 40, 40);
        g2.fillOval(290, 125, 40, 40);
        g2.setColor(new Color(200, 205, 215));
        g2.fillOval(305, 85, 10, 10);
        g2.fillOval(305, 140, 10, 10);

        // Terminals: VIN_IN on left, VOUT & GND on right
        drawSolderPad(g2, 60, 240, "VIN_IN");
        drawSolderPad(g2, 360, 240, "VOUT");
        drawSolderPad(g2, 360, 140, "GND");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 9. 4-Channel Type-K Thermocouple Array & Cold-Junction DAQ (380 x 280 px)
    private static void generateThermocoupleArray(File file) throws IOException {
        int w = 380, h = 280;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // High precision DAQ PCB (Deep Blue-Violet)
        g2.setColor(new Color(36, 28, 62));
        g2.fillRoundRect(20, 20, 340, 240, 12, 12);
        g2.setColor(new Color(78, 62, 128));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(20, 20, 340, 240, 12, 12);

        // Title
        g2.setColor(new Color(200, 190, 255));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("4-CH THERMOCOUPLE ARRAY", 75, 44);

        // 4 Yellow Mini Type-K Female Connector Blocks on Left
        String[] chLabels = {"CH1: MOSFET", "CH2: CORE", "CH3: DIODE", "CH4: AMBIENT"};
        int[] chY = {60, 110, 160, 210};
        for (int i = 0; i < 4; i++) {
            // Yellow socket
            g2.setColor(new Color(245, 200, 35));
            g2.fillRoundRect(45, chY[i] - 12, 50, 24, 4, 4);
            g2.setColor(new Color(180, 140, 15));
            g2.drawRoundRect(45, chY[i] - 12, 50, 24, 4, 4);
            // Plug contacts
            g2.setColor(new Color(50, 50, 50));
            g2.fillRect(52, chY[i] - 4, 10, 8);
            g2.fillRect(72, chY[i] - 3, 14, 6);

            // Channel text
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
            g2.drawString(chLabels[i], 105, chY[i] + 4);
        }

        // Amplifier IC in Center
        g2.setColor(new Color(20, 22, 28));
        g2.fillRoundRect(200, 110, 50, 60, 4, 4);
        g2.setColor(new Color(0, 229, 255));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        g2.drawString("MAX", 212, 135);
        g2.drawString("31855", 206, 150);

        // Output Solder Pads on Right Edge: T_MOSFET, T_CORE, T_DIODE, T_AMBIENT
        drawSolderPad(g2, 320, 60, "T_MOSFET");
        drawSolderPad(g2, 320, 110, "T_CORE");
        drawSolderPad(g2, 320, 160, "T_DIODE");
        drawSolderPad(g2, 320, 210, "T_AMBIENT");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 10. Environmental Thermal Chamber / Oven (400 x 320 px)
    private static void generateThermalChamber(File file) throws IOException {
        int w = 400, h = 320;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // Insulated Double-Wall Chamber (Stainless Steel / Thermal Silver)
        g2.setColor(new Color(42, 48, 60));
        g2.fillRoundRect(25, 20, 350, 280, 14, 14);
        g2.setColor(new Color(85, 95, 115));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(25, 20, 350, 280, 14, 14);

        // Header Title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.drawString("THERMAL CHAMBER (25°C - 85°C)", 75, 45);

        // Chamber Inspection Window / Cavity
        g2.setColor(new Color(20, 24, 30));
        g2.fillRoundRect(45, 65, 180, 160, 8, 8);
        g2.setColor(new Color(255, 110, 64, 80)); // Orange thermal heating glow
        g2.fillRoundRect(50, 70, 170, 150, 6, 6);

        // Heating element coils inside window
        g2.setColor(new Color(255, 87, 34));
        g2.setStroke(new BasicStroke(2.0f));
        for (int hy = 90; hy <= 190; hy += 25) {
            g2.drawArc(65, hy, 40, 20, 0, 180);
            g2.drawArc(105, hy, 40, 20, 180, 180);
            g2.drawArc(145, hy, 40, 20, 0, 180);
        }

        // Digital Temperature Controller Display on Right
        g2.setColor(new Color(15, 18, 25));
        g2.fillRoundRect(245, 65, 110, 60, 6, 6);
        g2.setColor(new Color(255, 61, 0)); // Bright thermal red
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 16));
        g2.drawString("65.0°C", 260, 102);

        // Status Indicators
        g2.setColor(new Color(0, 230, 118)); // Green HEATING status
        g2.fillOval(255, 145, 10, 10);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
        g2.drawString("HEATER ACTIVE", 272, 154);

        // Output Terminals: T_AMBIENT and HEATER_CTRL
        drawSolderPad(g2, 230, 260, "HEATER_CTRL");
        drawSolderPad(g2, 330, 260, "T_AMBIENT");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 11. Variable-Speed High-RPM 4-Wire PWM Cooling Fan (360 x 340 px)
    private static void generateCoolingFan(File file) throws IOException {
        int w = 360, h = 340;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // 120mm Industrial Fan Outer Frame (Dark Polycarbonate)
        g2.setColor(new Color(25, 28, 35));
        g2.fillRoundRect(20, 20, 320, 260, 16, 16);
        g2.setColor(new Color(60, 70, 85));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(20, 20, 320, 260, 16, 16);

        // 4 Corner Screw Mounting Holes
        g2.setColor(new Color(15, 18, 22));
        g2.fillOval(35, 35, 16, 16);
        g2.fillOval(305, 35, 16, 16);
        g2.fillOval(35, 245, 16, 16);
        g2.fillOval(305, 245, 16, 16);

        // Circular Rotor Housing / Venturi
        int cx = 180, cy = 150;
        g2.setColor(new Color(15, 18, 22));
        g2.fillOval(cx - 100, cy - 100, 200, 200);
        g2.setColor(new Color(50, 60, 75));
        g2.drawOval(cx - 100, cy - 100, 200, 200);

        // 7 Curved Aerodynamic Fan Blades
        g2.setColor(new Color(40, 48, 62));
        for (int i = 0; i < 7; i++) {
            double angle = i * (2 * Math.PI / 7);
            int x1 = cx + (int) (25 * Math.cos(angle));
            int y1 = cy + (int) (25 * Math.sin(angle));
            int x2 = cx + (int) (90 * Math.cos(angle + 0.5));
            int y2 = cy + (int) (90 * Math.sin(angle + 0.5));
            g2.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x1, y1, x2, y2);
        }

        // Central Motor Hub with Label
        g2.setColor(new Color(20, 25, 34));
        g2.fillOval(cx - 30, cy - 30, 60, 60);
        g2.setColor(new Color(0, 229, 255));
        g2.drawOval(cx - 30, cy - 30, 60, 60);
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        g2.drawString("12V PWM", cx - 22, cy + 4);

        // 4-Pin Wire Harness at Bottom: PWM_IN, TACH, VCC, GND
        drawSolderPad(g2, 90, 290, "PWM_IN");
        drawSolderPad(g2, 150, 290, "TACH");
        drawSolderPad(g2, 210, 290, "VCC");
        drawSolderPad(g2, 270, 290, "GND");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    // 12. 5mm Status LED Breakout Module (360 x 300 px)
    private static void generateLedModule(File file) throws IOException {
        int w = 360, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = setupGraphics(img);

        // PCB Board (Dark sleek matte PCB)
        g2.setColor(new Color(22, 26, 36));
        g2.fillRoundRect(20, 20, 320, 260, 16, 16);
        g2.setColor(new Color(55, 68, 92));
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(20, 20, 320, 260, 16, 16);

        // Corner Mounting Holes
        g2.setColor(new Color(14, 17, 24));
        g2.fillOval(35, 35, 14, 14);
        g2.fillOval(311, 35, 14, 14);
        g2.fillOval(35, 251, 14, 14);
        g2.fillOval(311, 251, 14, 14);

        // Silkscreen Titles
        g2.setColor(new Color(230, 235, 245));
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        g2.drawString("STATUS LED MODULE", 95, 52);
        g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
        g2.setColor(new Color(130, 142, 168));
        g2.drawString("5MM COMPONENT BREAKOUT (3.3V - 5V)", 65, 70);

        // Subtle PCB Copper Traces
        g2.setColor(new Color(36, 45, 62));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawLine(130, 240, 130, 195);
        g2.drawLine(130, 195, 155, 195); // To resistor
        g2.drawLine(155, 195, 155, 160);
        g2.drawLine(155, 160, 170, 145);
        g2.drawLine(230, 240, 230, 160); // Cathode trace direct to LED
        g2.drawLine(230, 160, 190, 145);

        // Current Limiting Resistor (220 Ohm SMD / Through-hole Package)
        int rx = 105, ry = 145;
        g2.setColor(new Color(205, 175, 125)); // Resistor body tan/ceramic
        g2.fillRoundRect(rx, ry, 45, 18, 4, 4);
        g2.setColor(new Color(150, 120, 80));
        g2.drawRoundRect(rx, ry, 45, 18, 4, 4);
        // Resistor color bands (Red, Red, Brown, Gold = 220 Ohm 5%)
        g2.setColor(new Color(220, 40, 40)); // Red
        g2.fillRect(rx + 8, ry, 4, 18);
        g2.fillRect(rx + 16, ry, 4, 18);
        g2.setColor(new Color(110, 60, 30)); // Brown
        g2.fillRect(rx + 24, ry, 4, 18);
        g2.setColor(new Color(212, 175, 55)); // Gold
        g2.fillRect(rx + 33, ry, 3, 18);

        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        g2.setColor(new Color(180, 190, 210));
        g2.drawString("R1: 220Ω", rx + 2, ry - 6);

        // LED Physical Graphic in Center (cx = 180, cy = 115)
        int cx = 180, cy = 112;

        // Radial ambient glow behind LED
        RadialGradientPaint aura = new RadialGradientPaint(
            new Point2D.Float(cx, cy), 50.0f,
            new float[]{0.0f, 0.4f, 1.0f},
            new Color[]{new Color(0, 230, 118, 90), new Color(0, 230, 118, 30), new Color(0, 230, 118, 0)}
        );
        g2.setPaint(aura);
        g2.fillOval(cx - 50, cy - 50, 100, 100);

        // LED Base Flange / Rim (with flat cathode notch on right)
        g2.setColor(new Color(0, 160, 80));
        g2.fillOval(cx - 24, cy + 8, 48, 16);
        g2.setColor(new Color(0, 100, 50));
        g2.drawOval(cx - 24, cy + 8, 48, 16);

        // LED Cylindrical Body & Dome
        g2.setColor(new Color(0, 200, 100, 220));
        g2.fillRoundRect(cx - 20, cy - 18, 40, 32, 10, 10);
        g2.fillArc(cx - 20, cy - 32, 40, 32, 0, 180);

        // Internal Leadframe (Cathode Anvil & Anode Post with Die)
        g2.setColor(new Color(230, 235, 245, 200));
        g2.setStroke(new BasicStroke(1.5f));
        // Anode post (left)
        g2.drawLine(cx - 8, cy + 14, cx - 8, cy - 4);
        // Cathode anvil (right, wider flat triangular flag)
        int[] anvilX = {cx + 8, cx + 8, cx + 1, cx - 4};
        int[] anvilY = {cy + 14, cy - 2, cy - 8, cy - 4};
        g2.drawPolyline(anvilX, anvilY, 4);
        // Semiconductor Die Hotspot
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fillOval(cx - 2, cy - 6, 4, 4);

        // Realistic Glass Specular Highlight Arc
        g2.setColor(new Color(255, 255, 255, 160));
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawArc(cx - 15, cy - 26, 16, 22, 90, 80);

        // Polarity Indicators Silkscreen
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        g2.setColor(new Color(0, 230, 118));
        g2.drawString("+ [ANODE]", 85, 230);
        g2.setColor(new Color(255, 82, 82));
        g2.drawString("- [CATHODE]", 200, 230);

        // Terminals / Solder Pads at Bottom:
        // ANODE: x = 130, y = 250 (u = 0.361, v = 0.833)
        // CATHODE: x = 230, y = 250 (u = 0.639, v = 0.833)
        drawSolderPad(g2, 130, 250, "ANODE");
        drawSolderPad(g2, 230, 250, "CATHODE");

        g2.dispose();
        ImageIO.write(img, "PNG", file);
    }

    private static Graphics2D setupGraphics(BufferedImage img) {
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    private static void drawPinHeaderColumn(Graphics2D g2, int x, int startY, int count, int pitch, boolean isLeft) {
        for (int i = 0; i < count; i++) {
            int py = startY + i * pitch;
            // Metallic pin contact pad
            g2.setColor(new Color(212, 175, 55)); // Gold
            g2.fillOval(x - 5, py - 5, 10, 10);
            g2.setColor(new Color(30, 35, 45));
            g2.fillOval(x - 2, py - 2, 4, 4); // Hole
        }
    }

    private static void drawSolderPad(Graphics2D g2, int x, int py, String label) {
        g2.setColor(new Color(212, 175, 55));
        g2.fillOval(x - 6, py - 6, 12, 12);
        g2.setColor(new Color(25, 30, 40));
        g2.fillOval(x - 3, py - 3, 6, 6);

        // Small label text
        g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        int tw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, x - tw / 2, py - 10);
    }
}
