import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.geom.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

// ============================================================================
// ROOM DASHBOARD APPLET — Smart Room Status & Wireless Telemetry Monitor
// ============================================================================
// Focuses EXCLUSIVELY on the MONITORING / OUTPUT side:
// Connects to Port 1 (9090) - Virtual Wi-Fi / Bluetooth Radio Bridge.
// Receives telemetry emitted by the on-board firmware:
//   - Room State: VACANT, OCCUPIED, HYSTERESIS, SLEEP (Glossy 3D Glass Bubble)
//   - Grace Period Hysteresis Countdown Gauge
//   - High-Voltage Actuator Relays: Lights & HVAC
//   - Sensor readings as reported by the MCU
// Features an interactive scenario drop down selector with emojis!
// All other components have emojis removed and high-contrast styling verified.
// ============================================================================
public class RoomDashboardApplet extends JFrame {

    // ─── Theme Font & High-Contrast Colors ───────────────────────────────────
    public static final String FONT_FAMILY  = "Segoe UI Emoji";
    private static final Color BG_DARK      = new Color(10, 13, 20);      // Deep pitch slate (#0A0D14)
    private static final Color CARD_BG      = new Color(18, 23, 34);      // Dark card slate (#121722)
    private static final Color ACCENT_BLUE  = new Color(56, 189, 248);    // Vivid sky cyan (#38BDF8)
    private static final Color COLOR_VACANT = new Color(71, 85, 105);     // Deep slate (#475569)
    private static final Color COLOR_OCCUPIED = new Color(34, 197, 94);   // Vivid emerald (#22C55E)
    private static final Color COLOR_HYST   = new Color(251, 191, 36);    // Vivid amber (#FBBF24)
    private static final Color COLOR_SLEEP  = new Color(192, 132, 252);   // Vivid purple (#C084FC)
    private static final Color TEXT_MAIN    = new Color(248, 250, 252);   // Pure crisp white (#F8FAFC)
    private static final Color TEXT_MUTED   = new Color(160, 174, 192);   // Clear light slate (#A0AEC0)

    // ─── Networking (Port 1: IoT Radio Telemetry) ────────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Live State from Telemetry Stream ────────────────────────────────────
    private volatile String roomState = "VACANT"; // OCCUPIED, HYSTERESIS, SLEEP, VACANT
    private volatile long hystRemainingSec = 0;
    private volatile boolean lightRelayOn = false;
    private volatile boolean hvacRelayOn = false;
    private volatile boolean pirActive = false;
    private volatile boolean doorOpen = false;
    private volatile double radarConf = 0.0;
    private volatile double radarVoltage = 0.0;
    private volatile String deviceName = "ESP32-DEV-ROOM1";
    private volatile String firmwareName = "PresenceFusion-v2.1";

    // ─── Visual Components ───────────────────────────────────────────────────
    private final RoomStateOrbPanel stateOrbPanel = new RoomStateOrbPanel();
    private final HysteresisGaugePanel hystGaugePanel = new HysteresisGaugePanel();
    private final RelaysDisplayPanel relaysPanel = new RelaysDisplayPanel();
    private final SensorTelemetryPanel sensorPanel = new SensorTelemetryPanel();

    private final JLabel headerDeviceLbl = new JLabel("DEVICE: —");
    private final JLabel headerFwLbl = new JLabel("FW: —");
    private final JLabel headerStatusLbl = new JLabel("[DISCONNECTED]");
    private final DefaultListModel<String> packetLogModel = new DefaultListModel<>();
    private final JList<String> packetList = new JList<>(packetLogModel);

    // Interactive Drop Down Box (Emojis are preserved inside dropdown items!)
    private JComboBox<String> scenarioCombo;

    public RoomDashboardApplet(String host, int port) {
        super("Smart Room Telemetry Dashboard — CPS Presence Fusion");
        this.host = host;
        this.port = port;

        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1040, 740);
        setMinimumSize(new Dimension(880, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        // Animation timer for pulsing glows & gauge refresh
        Timer animTimer = new Timer(40, e -> repaint());
        animTimer.start();

        // Connect automatically to network stream
        connect();
    }

    public RoomDashboardApplet() {
        this("localhost", 9090);
    }

    private void buildUI() {
        setLayout(new BorderLayout(12, 12));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(14, 16, 14, 16));

        // ── TOP: Header Bar ──────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(14, 0));
        topBar.setBackground(CARD_BG);
        topBar.setBorder(new CompoundBorder(
            new LineBorder(new Color(51, 65, 85), 1, true),
            new EmptyBorder(12, 18, 12, 18)
        ));

        // Title and Info
        JLabel titleLbl = new JLabel("SMART ROOM TELEMETRY DASHBOARD");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_BLUE);

        headerDeviceLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        headerDeviceLbl.setForeground(TEXT_MAIN);

        headerFwLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        headerFwLbl.setForeground(TEXT_MUTED);

        JPanel infoBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        infoBox.setOpaque(false);
        infoBox.add(titleLbl);
        infoBox.add(headerDeviceLbl);
        infoBox.add(headerFwLbl);

        // Right side: Scenario Drop Down Box (Emojis allowed) + Reconnect + Status
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightBox.setOpaque(false);

        JLabel dropLabel = new JLabel("PRESET / MODE:");
        dropLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        dropLabel.setForeground(TEXT_MUTED);

        // ── DROP DOWN BOX: Emojis preserved here per instructions ──
        scenarioCombo = new JComboBox<>(new String[] {
            "📡 Live Radio Telemetry (Port " + port + ")",
            "🚶 1. Person Enters Room (Occupied & Lights ON)",
            "💻 2. Sedentary Desk Study (Micro-Presence)",
            "🛏️ 3. Person Resting in Bed (Sleep Mode)",
            "🚪 4. Person Leaves Room (Grace Hysteresis)",
            "⚪ 5. Vacant Standby (All Systems Idle)"
        });
        scenarioCombo.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scenarioCombo.setBackground(new Color(24, 32, 47));
        scenarioCombo.setForeground(TEXT_MAIN);
        scenarioCombo.setFocusable(false);
        scenarioCombo.setBorder(new LineBorder(new Color(51, 65, 85), 1, true));
        scenarioCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
                lbl.setBorder(new EmptyBorder(4, 8, 4, 8));
                if (isSelected) {
                    lbl.setBackground(new Color(37, 99, 235)); // High-contrast Royal Blue
                    lbl.setForeground(Color.WHITE);
                } else {
                    lbl.setBackground(new Color(24, 32, 47));  // Slate card
                    lbl.setForeground(TEXT_MAIN);              // Crisp white
                }
                return lbl;
            }
        });

        scenarioCombo.addActionListener(e -> {
            int idx = scenarioCombo.getSelectedIndex();
            applyScenarioPreset(idx);
        });

        headerStatusLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        headerStatusLbl.setForeground(new Color(244, 63, 94));

        JButton connectBtn = new JButton("Reconnect");
        connectBtn.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        connectBtn.setBackground(new Color(30, 41, 59));
        connectBtn.setForeground(TEXT_MAIN);
        connectBtn.setFocusPainted(false);
        connectBtn.setBorder(new CompoundBorder(new LineBorder(new Color(71, 85, 105), 1, true), new EmptyBorder(6, 14, 6, 14)));
        connectBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        connectBtn.addActionListener(e -> {
            if (!connected) connect();
        });

        rightBox.add(dropLabel);
        rightBox.add(scenarioCombo);
        rightBox.add(headerStatusLbl);
        rightBox.add(connectBtn);

        topBar.add(infoBox, BorderLayout.WEST);
        topBar.add(rightBox, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ── CENTER: Main Visual Displays ─────────────────────────────────────
        JPanel centerGrid = new JPanel(new GridLayout(2, 2, 12, 12));
        centerGrid.setOpaque(false);

        centerGrid.add(createCard("ROOM OCCUPANCY STATE (FUSED)", stateOrbPanel));
        centerGrid.add(createCard("STATEFUL HYSTERESIS GRACE COUNTDOWN", hystGaugePanel));
        centerGrid.add(createCard("HIGH-VOLTAGE RELAY ACTUATORS", relaysPanel));
        centerGrid.add(createCard("ON-BOARD SENSOR TELEMETRY", sensorPanel));

        add(centerGrid, BorderLayout.CENTER);

        // ── BOTTOM: Wi-Fi Telemetry Stream Log ───────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout(6, 6));
        bottomBar.setBackground(CARD_BG);
        bottomBar.setBorder(new CompoundBorder(
            new LineBorder(new Color(51, 65, 85), 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));
        bottomBar.setPreferredSize(new Dimension(100, 135));

        JLabel streamHeader = new JLabel("Virtual Wi-Fi Radio Telemetry Stream (Port " + port + "):");
        streamHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        streamHeader.setForeground(TEXT_MUTED);

        packetList.setBackground(new Color(8, 12, 20));
        packetList.setForeground(new Color(125, 211, 252));
        packetList.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(packetList);
        scroll.setBorder(new LineBorder(new Color(30, 41, 59), 1));

        bottomBar.add(streamHeader, BorderLayout.NORTH);
        bottomBar.add(scroll, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private void applyScenarioPreset(int idx) {
        if (idx == 0) {
            // Live Stream Mode
            if (!connected) connect();
            logPacket("[MODE] Switched to Live Telemetry Stream.");
            return;
        }
        // Disconnect virtual stream for local manual inspection
        switch (idx) {
            case 1 -> { // Occupied
                roomState = "OCCUPIED";
                lightRelayOn = true;
                hvacRelayOn = true;
                pirActive = true;
                doorOpen = true;
                radarConf = 88.5;
                radarVoltage = 2.92;
                hystRemainingSec = 0;
                logPacket("[PRESET] Mode 1: Occupancy Active (Daylight Entry).");
            }
            case 2 -> { // Sedentary Study
                roomState = "OCCUPIED";
                lightRelayOn = true;
                hvacRelayOn = true;
                pirActive = false;
                doorOpen = false;
                radarConf = 78.0;
                radarVoltage = 2.57;
                hystRemainingSec = 0;
                logPacket("[PRESET] Mode 2: Sedentary Desk Study (Micro-Motions).");
            }
            case 3 -> { // Sleep Mode
                roomState = "SLEEP";
                lightRelayOn = false;
                hvacRelayOn = true;
                pirActive = false;
                doorOpen = false;
                radarConf = 59.0;
                radarVoltage = 1.95;
                hystRemainingSec = 0;
                logPacket("[PRESET] Mode 3: Person Resting in Bed (Sleep Mode).");
            }
            case 4 -> { // Grace Hysteresis
                roomState = "HYSTERESIS";
                lightRelayOn = true;
                hvacRelayOn = true;
                pirActive = false;
                doorOpen = true;
                radarConf = 15.0;
                radarVoltage = 0.50;
                hystRemainingSec = 24;
                logPacket("[PRESET] Mode 4: Person Leaves Room (Grace Hysteresis Window).");
            }
            case 5 -> { // Vacant Standby
                roomState = "VACANT";
                lightRelayOn = false;
                hvacRelayOn = false;
                pirActive = false;
                doorOpen = false;
                radarConf = 2.0;
                radarVoltage = 0.07;
                hystRemainingSec = 0;
                logPacket("[PRESET] Mode 5: Vacant Standby (All Relays Off).");
            }
        }
        repaint();
    }

    private JPanel createCard(String title, JComponent inner) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(new Color(51, 65, 85), 1, true),
            new EmptyBorder(12, 16, 12, 16)
        ));

        JLabel lbl = new JLabel(title);
        lbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        lbl.setForeground(new Color(226, 232, 240)); // High-contrast silver white
        card.add(lbl, BorderLayout.NORTH);
        card.add(inner, BorderLayout.CENTER);
        return card;
    }

    // =========================================================================
    // CUSTOM VISUAL COMPONENTS
    // =========================================================================

    // 1. Room State Glowing 3D Glass Bubble / Orb (Exact 5-Step Glossy Ball Rendering)
    private class RoomStateOrbPanel extends JPanel {
        RoomStateOrbPanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 14;
            int radius = Math.min(w, h) / 3;
            if (radius < 26) radius = 26;

            Color stateBase = switch (roomState) {
                case "OCCUPIED"   -> COLOR_OCCUPIED; // Vivid Emerald Green (#22C55E)
                case "HYSTERESIS" -> COLOR_HYST;     // Vivid Amber (#FBBF24)
                case "SLEEP"      -> COLOR_SLEEP;    // Vivid Purple (#C084FC)
                default           -> COLOR_VACANT;   // Slate Blue (#475569)
            };

            // ── STEP 0: Outer Ambient Pulsing Glow Halo ──────────────────────
            float pulse = (float) (0.85 + 0.15 * Math.sin(System.currentTimeMillis() / 250.0));
            Color glowColor = new Color(stateBase.getRed(), stateBase.getGreen(), stateBase.getBlue(), (int) (40 * pulse));
            g2.setColor(glowColor);
            g2.fillOval(cx - radius - 12, cy - radius - 12, (radius + 12) * 2, (radius + 12) * 2);

            // ── STEP 1: Volumetric 3D Sphere Base with Radial Illumination ────
            // Replicates the spherical 3D volume with darker perimeter and luminous core
            Point2D gradCenter = new Point2D.Float(cx, cy + radius * 0.18f);
            float gradRadius = radius * 1.15f;
            float[] dist = {0.0f, 0.50f, 0.85f, 1.0f};

            Color brightCore = blend(stateBase, Color.WHITE, 0.38f);
            Color darkTone   = blend(stateBase, Color.BLACK, 0.35f);
            Color rimTone    = blend(stateBase, Color.BLACK, 0.65f);
            Color[] colors   = {brightCore, stateBase, darkTone, rimTone};

            RadialGradientPaint rgp = new RadialGradientPaint(gradCenter, gradRadius, dist, colors);
            g2.setPaint(rgp);
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // ── STEP 4: Bottom Bounce Light (Inner Reflective Rim) ───────────
            Shape oldClip = g2.getClip();
            g2.clip(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));

            int bounceW = (int) (radius * 1.45);
            int bounceH = (int) (radius * 0.55);
            int bounceX = cx - bounceW / 2;
            int bounceY = cy + radius - bounceH + (int) (radius * 0.05);

            LinearGradientPaint bounceGrad = new LinearGradientPaint(
                cx, bounceY, cx, bounceY + bounceH,
                new float[]{0.0f, 1.0f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 130)}
            );
            g2.setPaint(bounceGrad);
            g2.fillOval(bounceX, bounceY, bounceW, bounceH);

            // ── STEP 2 & 3: Top Glossy Specular Highlight (The Curved White Crescent) ──
            int hlW = (int) (radius * 1.42);
            int hlH = (int) (radius * 0.80);
            int hlX = cx - hlW / 2;
            int hlY = cy - radius + (int) (radius * 0.05);

            LinearGradientPaint topGlare = new LinearGradientPaint(
                cx, hlY, cx, hlY + hlH,
                new float[]{0.0f, 0.40f, 1.0f},
                new Color[]{
                    new Color(255, 255, 255, 245), // Crisp pure white glossy glare
                    new Color(255, 255, 255, 130), // Translucent curved falloff
                    new Color(255, 255, 255, 0)    // Transparent bottom
                }
            );
            g2.setPaint(topGlare);
            g2.fillOval(hlX, hlY, hlW, hlH);

            // Subtle secondary specular glint
            g2.setColor(new Color(255, 255, 255, 85));
            g2.fillOval(cx - (int) (radius * 0.35), cy - (int) (radius * 0.55), (int) (radius * 0.22), (int) (radius * 0.12));

            // Restore clip
            g2.setClip(oldClip);

            // ── STEP 5: Outer Crisp Glass Rim ────────────────────────────────
            g2.setColor(new Color(255, 255, 255, 110));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // ── STATE TEXT: High-Contrast Translucent Dark Glass Pill ────────
            // Guarantees font color is NEVER similar or same as background!
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 17));
            FontMetrics fm = g2.getFontMetrics();
            int strW = fm.stringWidth(roomState);
            int pillW = strW + 28;
            int pillH = 32;
            int pillX = cx - pillW / 2;
            int pillY = cy - pillH / 2;

            g2.setColor(new Color(6, 10, 18, 220)); // High-contrast deep obsidian
            g2.fillRoundRect(pillX, pillY, pillW, pillH, 16, 16);
            g2.setColor(new Color(255, 255, 255, 85));
            g2.drawRoundRect(pillX, pillY, pillW, pillH, 16, 16);

            // Text rendered in ultra-crisp Pure White (Contrast ratio > 19:1)
            g2.setColor(Color.WHITE);
            int tx = cx - strW / 2;
            int ty = cy + fm.getAscent() / 2 - 3;
            g2.drawString(roomState, tx, ty);

            // ── Subtitle Description Below Orb (Card Background Contrast) ─────
            String desc = switch (roomState) {
                case "OCCUPIED"   -> "Occupancy Active — Systems Running";
                case "HYSTERESIS" -> "Grace Window — Awaiting Micro-Presence";
                case "SLEEP"      -> "Sleep Mode — Vital Respiration Active";
                default           -> "Room Empty — Energy Saving Standby";
            };
            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(desc, cx - fm2.stringWidth(desc) / 2, cy + radius + 24);

            g2.dispose();
        }
    }

    private static Color blend(Color c1, Color c2, float ratio) {
        float r = c1.getRed() * (1 - ratio) + c2.getRed() * ratio;
        float g = c1.getGreen() * (1 - ratio) + c2.getGreen() * ratio;
        float b = c1.getBlue() * (1 - ratio) + c2.getBlue() * ratio;
        return new Color((int) Math.min(255, Math.max(0, r)),
                         (int) Math.min(255, Math.max(0, g)),
                         (int) Math.min(255, Math.max(0, b)));
    }

    // 2. Hysteresis Countdown Gauge
    private class HysteresisGaugePanel extends JPanel {
        HysteresisGaugePanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 12;
            int radius = Math.min(w, h) / 3;

            // Background ring
            g2.setColor(new Color(30, 41, 59));
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
                g2.setColor(COLOR_OCCUPIED);
                g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            } else if (roomState.equals("SLEEP")) {
                g2.setColor(COLOR_SLEEP);
                g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            }

            // Center Label (Pure White on Dark Card Background — High Contrast)
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 22));
            g2.setColor(TEXT_MAIN);
            String centerStr = roomState.equals("HYSTERESIS")
                ? (hystRemainingSec + "s")
                : (roomState.equals("OCCUPIED") ? "SAFE" : (roomState.equals("SLEEP") ? "SLEEP" : "IDLE"));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(centerStr, cx - fm.stringWidth(centerStr) / 2, cy + fm.getAscent() / 2 - 3);

            // Subtitle
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            g2.setColor(TEXT_MUTED);
            String subStr = roomState.equals("HYSTERESIS") ? "Remaining Grace Time" : "No Grace Active";
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(subStr, cx - fm2.stringWidth(subStr) / 2, cy + radius + 24);

            g2.dispose();
        }
    }

    // 3. Relays Display Panel (No Emojis, High-Contrast Text)
    private class RelaysDisplayPanel extends JPanel {
        RelaysDisplayPanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int col1 = w / 4, col2 = (w * 3) / 4;
            int cy = h / 2 - 12;
            int r = 34;

            // ── LIGHT RELAY (No emoji) ──
            drawRelayCircle(g2, col1, cy, r, lightRelayOn, new Color(250, 204, 21), "LIGHTS (GPIO 21)", lightRelayOn ? "ENERGIZED (ON)" : "OFF");

            // ── HVAC RELAY (No emoji) ──
            drawRelayCircle(g2, col2, cy, r, hvacRelayOn, new Color(56, 189, 248), "HVAC / AC (GPIO 22)", hvacRelayOn ? "RUNNING (ON)" : "STANDBY (OFF)");

            g2.dispose();
        }

        private void drawRelayCircle(Graphics2D g2, int cx, int cy, int r, boolean active, Color activeColor, String label, String status) {
            // Glow if active
            if (active) {
                g2.setColor(new Color(activeColor.getRed(), activeColor.getGreen(), activeColor.getBlue(), 65));
                g2.fillOval(cx - r - 8, cy - r - 8, (r + 8) * 2, (r + 8) * 2);
            }

            g2.setColor(active ? activeColor : new Color(30, 41, 59));
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);

            // Contrast check: When active, dark navy text on bright yellow/cyan.
            // When inactive, bright silver text on dark slate. NEVER same or similar!
            g2.setColor(active ? new Color(15, 23, 42) : new Color(226, 232, 240));
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 14));
            String iconText = active ? "ON" : "OFF";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(iconText, cx - fm.stringWidth(iconText) / 2, cy + fm.getAscent() / 2 - 2);

            // Labels below
            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            FontMetrics fmLabel = g2.getFontMetrics();
            g2.drawString(label, cx - fmLabel.stringWidth(label) / 2, cy + r + 22);

            g2.setColor(active ? activeColor : TEXT_MUTED);
            g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
            FontMetrics fmStatus = g2.getFontMetrics();
            g2.drawString(status, cx - fmStatus.stringWidth(status) / 2, cy + r + 38);
        }
    }

    // 4. Sensor Telemetry Readout Panel (No Emojis)
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
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

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
                radarConf > 35 ? ACCENT_BLUE : TEXT_MUTED);

            g2.dispose();
        }

        private void drawSensorRow(Graphics2D g2, int x, int y, int w, int h, String name, String val, Color statusColor) {
            g2.setColor(new Color(24, 32, 47));
            g2.fillRoundRect(x, y, w, h, 8, 8);
            g2.setColor(new Color(51, 65, 85));
            g2.drawRoundRect(x, y, w, h, 8, 8);

            // Indicator dot
            g2.setColor(statusColor);
            g2.fillOval(x + 10, y + (h / 2) - 5, 10, 10);

            // Name (Crisp White on Dark Row Card)
            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            g2.drawString(name, x + 28, y + (h / 2) + 4);

            // Value
            g2.setColor(statusColor);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
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
                updateStatus("[CONNECTED: " + port + "]", COLOR_OCCUPIED);
                logPacket("[CONNECTED] Listening to virtual Wi-Fi telemetry at " + host + ":" + port);

                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String packet = line;
                    SwingUtilities.invokeLater(() -> handleTelemetry(packet));
                }
            } catch (IOException e) {
                updateStatus("[DISCONNECTED]", new Color(244, 63, 94));
                logPacket("[DISCONNECTED] " + e.getMessage());
                connected = false;
            }
        }, "DashboardTelemetryReader").start();
    }

    private void handleTelemetry(String json) {
        logPacket("<- " + json);

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
