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

    public static void main(String[] args) throws IOException {
        File assetsDir = new File("assets");
        if (!assetsDir.exists()) assetsDir.mkdirs();

        generateESP32(new File(assetsDir, "esp32_wroom.png"));
        generatePIR(new File(assetsDir, "pir_motion_sensor.png"));
        generateDoorSensor(new File(assetsDir, "magnetic_door_sensor.png"));
        generateRadarSensor(new File(assetsDir, "radar_sensor.png"));
        generateRelayModule(new File(assetsDir, "relay_module.png"));
        generateElectronicLoad(new File(assetsDir, "electronic_load.png"));

        System.out.println("[ASSETS] Generated 6 default component images in assets/");
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
        g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
        g2.drawString("ESP32-WROOM-32", 110, 170);
        g2.setFont(new Font("Consolas", Font.PLAIN, 12));
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
        g2.setFont(new Font("Consolas", Font.BOLD, 10));
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
        g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
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
        g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
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
        g2.setFont(new Font("Consolas", Font.BOLD, 10));
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
        g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
        g2.drawString("SONGLE RELAY 1", 175, 100);
        g2.drawString("SONGLE RELAY 2", 175, 220);
        g2.setFont(new Font("Consolas", Font.PLAIN, 10));
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
        g2.setFont(new Font("Consolas", Font.BOLD, 22));
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
        g2.setFont(new Font("Consolas", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        int tw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, x - tw / 2, py - 10);
    }
}
