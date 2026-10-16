import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

// ============================================================================
// ROOM DASHBOARD APPLET — Smart Room Status & Wireless Telemetry Monitor
// ============================================================================
// Focuses EXCLUSIVELY on the MONITORING / OUTPUT side:
// Connects to Port 1 (9090) - Virtual Wi-Fi / Bluetooth Radio Bridge.
// Receives telemetry emitted by the on-board firmware:
//   - Room State: VACANT, OCCUPIED, HYSTERESIS
//   - Grace Period Hysteresis Countdown
//   - High-Voltage Actuator Relays: Lights & HVAC
//   - Sensor readings as reported by the MCU
// ============================================================================
public class RoomDashboardApplet extends JFrame {

    // ─── Theme Colors ────────────────────────────────────────────────────────
    private static final Color BG_DARK      = new Color(16, 18, 26);
    private static final Color CARD_BG      = new Color(24, 27, 38);
    private static final Color ACCENT_BLUE  = new Color(75, 145, 255);
    private static final Color COLOR_VACANT = new Color(90, 100, 120);
    private static final Color COLOR_OCCUPIED = new Color(45, 220, 115);
    private static final Color COLOR_HYST   = new Color(255, 185, 45);
    private static final Color TEXT_MAIN    = new Color(230, 235, 245);
    private static final Color TEXT_MUTED   = new Color(135, 145, 170);

    // ─── Networking (Port 1: IoT Radio Telemetry) ────────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Telemetry Data Registers ────────────────────────────────────────────
    private volatile String  roomState        = "VACANT";
    private volatile String  deviceName       = "RoomMasterMCU";
    private volatile String  firmwareName     = "SmartRoom_Firmware_v2.0";
    private volatile boolean pirActive        = false;
    private volatile boolean doorOpen         = false;
    private volatile double  radarConf        = 0.0;
    private volatile double  radarVoltage     = 0.0;
    private volatile boolean lightRelayOn     = false;
    private volatile boolean hvacRelayOn      = false;
    private volatile long    hystRemainingSec = 0;

    // ─── Custom UI Panels ────────────────────────────────────────────────────
    private final RoomStateOrbPanel stateOrbPanel = new RoomStateOrbPanel();
    private final HysteresisGaugePanel hystGaugePanel = new HysteresisGaugePanel();
    private final RelaysDisplayPanel relaysPanel = new RelaysDisplayPanel();
    private final SensorTelemetryPanel sensorPanel = new SensorTelemetryPanel();

    private final JLabel headerDeviceLbl = new JLabel("DEVICE: —");
    private final JLabel headerFwLbl     = new JLabel("FIRMWARE: —");
    private final JLabel headerStatusLbl = new JLabel("● DISCONNECTED");

    private final DefaultListModel<String> packetLogModel = new DefaultListModel<>();
    private final JList<String> packetList = new JList<>(packetLogModel);

    public RoomDashboardApplet(String host, int port) {
        super("CPS Smart Room Dashboard — Wireless Telemetry Monitor");
        this.host = host;
        this.port = port;

        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 680);
        setMinimumSize(new Dimension(840, 560));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        // Animation timer for pulsing glows & gauge refresh
        Timer animTimer = new Timer(50, e -> repaint());
        animTimer.start();

        // Connect automatically
        SwingUtilities.invokeLater(this::connect);
    }

    public RoomDashboardApplet() {
        this("localhost", 9090);
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(12, 14, 12, 14));

        // ── TOP: Header Bar ──────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setBackground(CARD_BG);
        topBar.setBorder(new CompoundBorder(
            new LineBorder(new Color(38, 44, 62), 1, true),
            new EmptyBorder(10, 16, 10, 16)
        ));

        JLabel titleLbl = new JLabel("🏠 SMART ROOM TELEMETRY DASHBOARD");
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_BLUE);

        headerDeviceLbl.setFont(new Font("Monospaced", Font.BOLD, 12));
        headerDeviceLbl.setForeground(TEXT_MAIN);

        headerFwLbl.setFont(new Font("Monospaced", Font.PLAIN, 12));
        headerFwLbl.setForeground(TEXT_MUTED);

        JPanel infoBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        infoBox.setOpaque(false);
        infoBox.add(titleLbl);
        infoBox.add(headerDeviceLbl);
        infoBox.add(headerFwLbl);

        headerStatusLbl.setFont(new Font("Monospaced", Font.BOLD, 12));
        headerStatusLbl.setForeground(new Color(240, 75, 75));

        JButton connectBtn = new JButton("Reconnect");
        connectBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        connectBtn.setBackground(new Color(45, 52, 72));
        connectBtn.setForeground(TEXT_MAIN);
        connectBtn.setFocusPainted(false);
        connectBtn.addActionListener(e -> {
            if (!connected) connect();
        });

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightBox.setOpaque(false);
        rightBox.add(headerStatusLbl);
        rightBox.add(connectBtn);

        topBar.add(infoBox, BorderLayout.WEST);
        topBar.add(rightBox, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ── CENTER: Main Visual Displays ─────────────────────────────────────
        JPanel centerGrid = new JPanel(new GridLayout(2, 2, 10, 10));
        centerGrid.setOpaque(false);

        centerGrid.add(createCard("ROOM OCCUPANCY STATE (FUSED)", stateOrbPanel));
        centerGrid.add(createCard("STATEFUL HYSTERESIS GRACE COUNTDOWN", hystGaugePanel));
        centerGrid.add(createCard("HIGH-VOLTAGE RELAY ACTUATORS", relaysPanel));
        centerGrid.add(createCard("ON-BOARD SENSOR TELEMETRY", sensorPanel));

        add(centerGrid, BorderLayout.CENTER);

        // ── BOTTOM: Wi-Fi Telemetry Stream Log ───────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout(4, 4));
        bottomBar.setBackground(CARD_BG);
        bottomBar.setBorder(new CompoundBorder(
            new LineBorder(new Color(38, 44, 62), 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));
        bottomBar.setPreferredSize(new Dimension(100, 130));

        JLabel streamHeader = new JLabel("Virtual Wi-Fi Radio Telemetry Stream (Port " + port + "):");
        streamHeader.setFont(new Font("Segoe UI", Font.BOLD, 11));
        streamHeader.setForeground(TEXT_MUTED);

        packetList.setBackground(new Color(12, 14, 20));
        packetList.setForeground(new Color(120, 215, 255));
        packetList.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(packetList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        bottomBar.add(streamHeader, BorderLayout.NORTH);
        bottomBar.add(scroll, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private JPanel createCard(String title, JComponent inner) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(new Color(36, 42, 60), 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));

        JLabel lbl = new JLabel(title);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lbl.setForeground(new Color(175, 185, 210));

        card.add(lbl, BorderLayout.NORTH);
        card.add(inner, BorderLayout.CENTER);
        return card;
    }

    // =========================================================================
    // CUSTOM VISUAL COMPONENTS
    // =========================================================================

    // 1. Room State Glowing Orb
    private class RoomStateOrbPanel extends JPanel {
        RoomStateOrbPanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 10;
            int radius = Math.min(w, h) / 3;

            Color stateColor = switch (roomState) {
                case "OCCUPIED"   -> COLOR_OCCUPIED;
                case "HYSTERESIS" -> COLOR_HYST;
                default           -> COLOR_VACANT;
            };

            // Outer glow
            float pulse = (float) (0.8 + 0.2 * Math.sin(System.currentTimeMillis() / 250.0));
            Color glowColor = new Color(stateColor.getRed(), stateColor.getGreen(), stateColor.getBlue(), (int) (60 * pulse));
            g2.setColor(glowColor);
            g2.fillOval(cx - radius - 14, cy - radius - 14, (radius + 14) * 2, (radius + 14) * 2);

            // Main circle
            g2.setColor(stateColor);
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // Inner gloss
            g2.setColor(new Color(255, 255, 255, 50));
            g2.fillOval(cx - radius + 8, cy - radius + 6, (radius - 8) * 2, radius);

            // State Text
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
            FontMetrics fm = g2.getFontMetrics();
            int tx = cx - fm.stringWidth(roomState) / 2;
            int ty = cy + fm.getAscent() / 2 - 2;
            g2.drawString(roomState, tx, ty);

            // Subtitle Description
            String desc = switch (roomState) {
                case "OCCUPIED"   -> "Occupancy Active — Systems Running";
                case "HYSTERESIS" -> "Grace Window — Awaiting Micro-Presence";
                default           -> "Room Empty — Energy Saving Standby";
            };
            g2.setColor(TEXT_MUTED);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(desc, cx - fm2.stringWidth(desc) / 2, cy + radius + 22);

            g2.dispose();
        }
    }

    // 2. Hysteresis Countdown Gauge
    private class HysteresisGaugePanel extends JPanel {
        HysteresisGaugePanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 10;
            int radius = Math.min(w, h) / 3;

            // Background ring
            g2.setColor(new Color(40, 46, 64));
            g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // Active Countdown Arc
            if (roomState.equals("HYSTERESIS")) {
                int totalSec = 30; // nominal grace window
                double progress = Math.min(1.0, Math.max(0.0, hystRemainingSec / (double) totalSec));
                int sweepAngle = (int) (progress * 360.0);

                g2.setColor(COLOR_HYST);
                g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawArc(cx - radius, cy - radius, radius * 2, radius * 2, 90, -sweepAngle);
            } else if (roomState.equals("OCCUPIED")) {
                // Full green ring
                g2.setColor(COLOR_OCCUPIED);
                g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            }

            // Center Label
            g2.setFont(new Font("Segoe UI", Font.BOLD, 22));
            g2.setColor(TEXT_MAIN);
            String centerStr = roomState.equals("HYSTERESIS")
                ? (hystRemainingSec + "s")
                : (roomState.equals("OCCUPIED") ? "SAFE" : "IDLE");
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(centerStr, cx - fm.stringWidth(centerStr) / 2, cy + fm.getAscent() / 2 - 2);

            // Subtitle
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g2.setColor(TEXT_MUTED);
            String subStr = roomState.equals("HYSTERESIS") ? "Remaining Grace Time" : "No Grace Active";
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(subStr, cx - fm2.stringWidth(subStr) / 2, cy + radius + 22);

            g2.dispose();
        }
    }

    // 3. Relays Display Panel
    private class RelaysDisplayPanel extends JPanel {
        RelaysDisplayPanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int col1 = w / 4, col2 = (w * 3) / 4;
            int cy = h / 2 - 10;
            int r = 32;

            // ── LIGHT RELAY ──
            drawRelayCircle(g2, col1, cy, r, lightRelayOn, new Color(255, 215, 60), "💡 LIGHTS (GPIO 21)", lightRelayOn ? "ENERGIZED (ON)" : "OFF");

            // ── HVAC RELAY ──
            drawRelayCircle(g2, col2, cy, r, hvacRelayOn, new Color(60, 185, 255), "❄ HVAC / AC (GPIO 22)", hvacRelayOn ? "RUNNING (ON)" : "STANDBY (OFF)");

            g2.dispose();
        }

        private void drawRelayCircle(Graphics2D g2, int cx, int cy, int r, boolean active, Color activeColor, String label, String status) {
            // Glow if active
            if (active) {
                g2.setColor(new Color(activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue(), 60));
                g2.fillOval(cx - r - 8, cy - r - 8, (r + 8) * 2, (r + 8) * 2);
            }

            g2.setColor(active ? activeColor : new Color(42, 48, 66));
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);

            g2.setColor(active ? new Color(30, 30, 30) : new Color(110, 120, 145));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
            String iconText = active ? "ON" : "OFF";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(iconText, cx - fm.stringWidth(iconText) / 2, cy + fm.getAscent() / 2 - 2);

            // Labels below
            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            FontMetrics fmLabel = g2.getFontMetrics();
            g2.drawString(label, cx - fmLabel.stringWidth(label) / 2, cy + r + 20);

            g2.setColor(active ? activeColor : TEXT_MUTED);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            FontMetrics fmStatus = g2.getFontMetrics();
            g2.drawString(status, cx - fmStatus.stringWidth(status) / 2, cy + r + 36);
        }
    }

    // 4. Sensor Telemetry Readout Panel
    private class SensorTelemetryPanel extends JPanel {
        SensorTelemetryPanel() {
            setOpaque(false);
            setLayout(new GridLayout(3, 1, 0, 8));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int rowH = h / 3;

            // Row 1: PIR
            drawSensorRow(g2, 10, 8, w - 20, rowH - 12, "PIR Motion Sensor (GPIO 12)",
                pirActive ? "ACTIVE (Motion Detected)" : "IDLE (No Motion)",
                pirActive ? COLOR_OCCUPIED : TEXT_MUTED);

            // Row 2: Door
            drawSensorRow(g2, 10, rowH + 8, w - 20, rowH - 12, "Door Reed Switch (GPIO 14)",
                doorOpen ? "OPEN (Contact Broken)" : "CLOSED (Contact Latched)",
                doorOpen ? COLOR_HYST : TEXT_MUTED);

            // Row 3: Radar
            drawSensorRow(g2, 10, (rowH * 2) + 8, w - 20, rowH - 12, "60GHz mmWave Radar (GPIO 27)",
                String.format("%.1f%% Confidence  [%.2f V]", radarConf, radarVoltage),
                radarConf > 45 ? ACCENT_BLUE : TEXT_MUTED);

            g2.dispose();
        }

        private void drawSensorRow(Graphics2D g2, int x, int y, int w, int h, String name, String val, Color statusColor) {
            g2.setColor(new Color(32, 36, 50));
            g2.fillRoundRect(x, y, w, h, 8, 8);

            // Indicator dot
            g2.setColor(statusColor);
            g2.fillOval(x + 10, y + (h / 2) - 5, 10, 10);

            // Name
            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2.drawString(name, x + 28, y + (h / 2) + 4);

            // Value
            g2.setColor(statusColor);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(val, x + w - fm.stringWidth(val) - 12, y + (h / 2) + 4);
        }
    }

    // =========================================================================
    // NETWORKING (Port 1 Radio Telemetry Reader)
    // =========================================================================

    private void connect() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to " + host + ":" + port + "...", COLOR_HYST);
                socket = new Socket(host, port);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                updateStatus("● CONNECTED (Port " + port + ")", COLOR_OCCUPIED);
                logPacket("[CONNECTED] Listening to virtual Wi-Fi telemetry at " + host + ":" + port);

                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String packet = line;
                    SwingUtilities.invokeLater(() -> handleTelemetry(packet));
                }
            } catch (IOException e) {
                updateStatus("● DISCONNECTED", new Color(240, 75, 75));
                logPacket("[DISCONNECTED] " + e.getMessage());
                connected = false;
            }
        }, "DashboardTelemetryReader").start();
    }

    private void handleTelemetry(String json) {
        logPacket("← " + json);

        String state = extractJsonVal(json, "state");
        if (!state.isEmpty()) roomState = state.toUpperCase();

        String dev = extractJsonVal(json, "device");
        if (!dev.isEmpty()) {
            deviceName = dev;
            headerDeviceLbl.setText("DEVICE: " + dev);
        }

        String fw = extractJsonVal(json, "firmware");
        if (!fw.isEmpty()) {
            firmwareName = fw;
            headerFwLbl.setText("FIRMWARE: " + fw);
        }

        pirActive        = extractJsonBool(json, "pir", pirActive);
        doorOpen         = extractJsonBool(json, "door", doorOpen);
        radarConf        = extractJsonDouble(json, "radar", radarConf);
        radarVoltage     = extractJsonDouble(json, "radar_v", (radarConf / 100.0) * 3.3);
        lightRelayOn     = extractJsonBool(json, "light_relay", lightRelayOn);
        hvacRelayOn      = extractJsonBool(json, "hvac_relay", hvacRelayOn);
        hystRemainingSec = (long) extractJsonDouble(json, "hysteresis_remaining_s", hystRemainingSec);
    }

    private void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            headerStatusLbl.setText(text);
            headerStatusLbl.setForeground(color);
        });
    }

    private void logPacket(String msg) {
        SwingUtilities.invokeLater(() -> {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            packetLogModel.add(0, "[" + time + "] " + msg);
            if (packetLogModel.size() > 60) packetLogModel.removeElementAt(packetLogModel.size() - 1);
        });
    }

    // JSON Helper Extraction
    private String extractJsonVal(String j, String k) {
        String s = "\"" + k + "\":\""; int i = j.indexOf(s);
        if (i < 0) return ""; i += s.length(); int e = j.indexOf('"', i);
        return e < 0 ? "" : j.substring(i, e);
    }

    private boolean extractJsonBool(String j, String k, boolean def) {
        String s = "\"" + k + "\":"; int i = j.indexOf(s);
        if (i < 0) return def; i += s.length();
        if (j.startsWith("true", i)) return true;
        if (j.startsWith("false", i)) return false;
        return def;
    }

    private double extractJsonDouble(String j, String k, double def) {
        String s = "\"" + k + "\":"; int i = j.indexOf(s);
        if (i < 0) return def; i += s.length(); int e = i;
        while (e < j.length() && (Character.isDigit(j.charAt(e)) || j.charAt(e) == '.' || j.charAt(e) == '-')) e++;
        try { return Double.parseDouble(j.substring(i, e)); } catch (Exception x) { return def; }
    }

    // ─── Entry Point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        SwingUtilities.invokeLater(() -> new RoomDashboardApplet(host, port));
    }
}
