import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

// ============================================================================
// SMART ROOM DASHBOARD — Phase 2 Swing GUI
// Visualises sensor fusion, room state, relay outputs and hysteresis timer.
// Usage: java SmartRoomDashboard [host] [port]
// Features:
//   - Glossy 3D glass bubble effect with high-contrast text pill
//   - Interactive scenario drop down box with emojis
//   - Strict color contrast verification across all components
//   - Emojis removed everywhere else
// ============================================================================
public class SmartRoomDashboard extends JFrame {

    // ─── Colours ──────────────────────────────────────────────────────────────
    static final Color BG        = new Color(10, 13, 20);
    static final String FONT_FAMILY = "Segoe UI Emoji";
    static final Color PANEL_BG  = new Color(18, 23, 34);
    static final Color ACCENT    = new Color(56, 189, 248);
    static final Color GREEN     = new Color(34, 197, 94);
    static final Color AMBER     = new Color(251, 191, 36);
    static final Color RED_C     = new Color(244, 63,  94);
    static final Color DIM       = new Color(71,  85,  105);
    static final Color TEXT      = new Color(248, 250, 252);

    // ─── Network ─────────────────────────────────────────────────────────────
    private Socket       socket;
    private PrintWriter  out;
    private BufferedReader in_br;
    private volatile boolean connected = false;

    // PORT 2: Direct Physical Terminal Bus Tap (defaults to port + 1 = 9091)
    private Socket       terminalSocket;
    private PrintWriter  terminalOut;
    private BufferedReader terminalIn_br;
    private volatile boolean terminalConnected = false;
    private final int terminalPort;

    private final String host; private final int port;

    // ─── Parsed telemetry state ───────────────────────────────────────────────
    private volatile String  roomState        = "VACANT";
    private volatile boolean pirActive        = false;
    private volatile boolean doorOpen         = false;
    private volatile double  radarConf        = 0.0;
    private volatile boolean lightOn          = false;
    private volatile boolean hvacOn           = false;
    private volatile long    hystRemainingSec = 0;
    private volatile String  deviceName       = "—";

    // ─── UI components ────────────────────────────────────────────────────────
    private final RoomStatePanel   roomStatePanel = new RoomStatePanel();
    private final SensorPanel      sensorPanel    = new SensorPanel();
    private final RelayPanel       relayPanel     = new RelayPanel();
    private final HysteresisPanel  hystPanel      = new HysteresisPanel();
    private final EventLogPanel    logPanel       = new EventLogPanel();
    private final JLabel           statusLabel    = new JLabel("[DISCONNECTED]");
    private final JLabel           deviceLabel    = new JLabel("Device: —");

    // ─── Drop Down Box (Scenario Mode - Emojis allowed inside dropdown items) ─
    private JComboBox<String> scenarioCombo;

    // ─── Input controls (inject sensor values) ────────────────────────────────
    private final JToggleButton pirBtn    = styledToggle("PIR",   "Motion", "No Motion");
    private final JToggleButton doorBtn   = styledToggle("DOOR",  "Open",   "Closed");
    private final JToggleButton radarBtn  = styledToggle("RADAR", "Present","No Presence");
    private final JSlider       radarSlider = new JSlider(0, 100, 0);

    // ─── Refresh timer ────────────────────────────────────────────────────────
    private javax.swing.Timer uiTimer;

    public SmartRoomDashboard(String host, int port) {
        super("Smart Room Dashboard — CPS Sensor Fusion");
        this.host = host;
        this.port = (port > 0) ? port : CpsDomain.PORT_TELEMETRY; // 9090
        this.terminalPort = CpsDomain.PORT_SENSORS; // 9091 (Sensors Bus)
        buildUI();
        startUiTimer();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1080, 740);
        setMinimumSize(new Dimension(920, 620));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setVisible(true);
    }

    // ─── UI Layout ────────────────────────────────────────────────────────────
    private void buildUI() {
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildTopBar(),    BorderLayout.NORTH);
        add(buildCenter(),    BorderLayout.CENTER);
        add(buildInputBar(),  BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(new Color(22, 22, 38));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        deviceLabel.setForeground(ACCENT);
        deviceLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));

        statusLabel.setForeground(RED_C);
        statusLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));

        // ── Drop Down Box: Emojis preserved inside dropdown box ──
        scenarioCombo = new JComboBox<>(new String[] {
            "📡 Live Telemetry Stream",
            "🚶 1. Person Enters Room (Occupied & Lights ON)",
            "💻 2. Sedentary Desk Study (Micro-Presence)",
            "🛏️ 3. Person Resting in Bed (Sleep Mode)",
            "🚪 4. Person Leaves Room (Grace Hysteresis)",
            "⚪ 5. Vacant Standby (All Relays Off)"
        });
        scenarioCombo.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scenarioCombo.setBackground(new Color(24, 32, 47));
        scenarioCombo.setForeground(TEXT);
        scenarioCombo.setFocusable(false);
        scenarioCombo.setBorder(new LineBorder(new Color(51, 65, 85), 1, true));
        scenarioCombo.addActionListener(e -> {
            int idx = scenarioCombo.getSelectedIndex();
            applyScenarioPreset(idx);
        });

        JButton connectBtn = dashBtn("Connect", GREEN);
        connectBtn.addActionListener(e -> attemptConnect());
        JButton disconnectBtn = dashBtn("Disconnect", RED_C);
        disconnectBtn.addActionListener(e -> disconnect());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        right.setOpaque(false);
        right.add(new JLabel("Mode: "));
        right.add(scenarioCombo);
        right.add(connectBtn);
        right.add(disconnectBtn);

        bar.add(deviceLabel, BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(right,       BorderLayout.EAST);
        return bar;
    }

    private void applyScenarioPreset(int idx) {
        if (idx == 0) {
            logPanel.add("Switched to Live Telemetry Stream.");
            return;
        }
        switch (idx) {
            case 1 -> {
                roomState = "OCCUPIED";
                lightOn = true;
                hvacOn = true;
                pirActive = true;
                doorOpen = true;
                radarConf = 85.0;
                hystRemainingSec = 0;
                logPanel.add("Preset 1: Occupancy Active applied.");
            }
            case 2 -> {
                roomState = "OCCUPIED";
                lightOn = true;
                hvacOn = true;
                pirActive = false;
                doorOpen = false;
                radarConf = 75.0;
                hystRemainingSec = 0;
                logPanel.add("Preset 2: Sedentary Desk Study applied.");
            }
            case 3 -> {
                roomState = "SLEEP";
                lightOn = false;
                hvacOn = true;
                pirActive = false;
                doorOpen = false;
                radarConf = 55.0;
                hystRemainingSec = 0;
                logPanel.add("Preset 3: Sleep Mode applied.");
            }
            case 4 -> {
                roomState = "HYSTERESIS";
                lightOn = true;
                hvacOn = true;
                pirActive = false;
                doorOpen = true;
                radarConf = 15.0;
                hystRemainingSec = 25;
                logPanel.add("Preset 4: Grace Window Hysteresis applied.");
            }
            case 5 -> {
                roomState = "VACANT";
                lightOn = false;
                hvacOn = false;
                pirActive = false;
                doorOpen = false;
                radarConf = 0.0;
                hystRemainingSec = 0;
                logPanel.add("Preset 5: Vacant Standby applied.");
            }
        }
        repaint();
    }

    private JPanel buildCenter() {
        // Top row: Room State (big) + Relay outputs
        JPanel topRow = new JPanel(new GridLayout(1, 2, 8, 0));
        topRow.setOpaque(false);
        topRow.add(roomStatePanel);
        topRow.add(relayPanel);

        // Middle row: 3 sensor indicators + hysteresis bar
        JPanel midRow = new JPanel(new GridLayout(1, 2, 8, 0));
        midRow.setOpaque(false);
        midRow.add(sensorPanel);
        midRow.add(hystPanel);

        // Assemble vertically
        JPanel center = new JPanel(new GridLayout(3, 1, 0, 8));
        center.setOpaque(false);
        center.add(topRow);
        center.add(midRow);
        center.add(logPanel);
        return center;
    }

    /** Bottom bar — sensor toggle buttons sent to board via socket (No emojis) */
    private JPanel buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(new Color(22, 22, 38));
        bar.setBorder(titledBorder("SENSOR INPUTS — toggle to inject signal into board"));

        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        toggles.setOpaque(false);
        toggles.add(pirBtn);
        toggles.add(doorBtn);
        toggles.add(radarBtn);

        JLabel radLbl = new JLabel("  Radar Confidence: ");
        radLbl.setForeground(TEXT);
        radLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        toggles.add(radLbl);

        radarSlider.setPreferredSize(new Dimension(200, 28));
        radarSlider.setOpaque(false);
        radarSlider.setForeground(ACCENT);
        toggles.add(radarSlider);

        // Wire toggle actions → send JSON to board
        pirBtn.addActionListener(e   -> sendSensorJson());
        doorBtn.addActionListener(e  -> sendSensorJson());
        radarBtn.addActionListener(e -> sendSensorJson());
        radarSlider.addChangeListener(e -> sendSensorJson());

        // Manual JSON field
        JTextField manualField = new JTextField("{\"cmd\":\"PING\"}");
        manualField.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        manualField.setBackground(new Color(30, 30, 50));
        manualField.setForeground(TEXT);
        manualField.setCaretColor(Color.WHITE);
        JButton sendBtn = dashBtn("Send", ACCENT);
        sendBtn.addActionListener(e -> { sendRaw(manualField.getText().trim()); manualField.selectAll(); });
        manualField.addActionListener(e -> sendBtn.doClick());

        JPanel manualRow = new JPanel(new BorderLayout(4, 0));
        manualRow.setOpaque(false);
        manualRow.add(manualField, BorderLayout.CENTER);
        manualRow.add(sendBtn,     BorderLayout.EAST);

        bar.add(toggles,   BorderLayout.CENTER);
        bar.add(manualRow, BorderLayout.EAST);
        return bar;
    }

    private void sendSensorJson() {
        boolean pirOn   = pirBtn.isSelected();
        boolean doorOn  = doorBtn.isSelected();
        boolean radarOn = radarBtn.isSelected();
        int     conf    = radarSlider.getValue();
        double  radarV  = radarOn ? (conf / 100.0 * 3.3) : 0.0;

        // 1. PRIMARY: Drive physical terminals directly via Port 2 (Workspace Terminal Bus)
        if (terminalConnected && terminalOut != null) {
            terminalOut.printf("SET PIR_MotionSensor DATA %.1f%n", pirOn ? 3.3 : 0.0);
            terminalOut.printf("SET DoorContactSensor STATE %.1f%n", doorOn ? 3.3 : 0.0);
            terminalOut.printf("SET MillimeterWaveRadar PRESENCE_SIG %.3f%n", radarV);
            terminalOut.flush();
        }

        // 2. BACKUP: Transmit JSON packet over Port 1
        String json = String.format(Locale.US,
            "{\"type\":\"SENSOR_OVERRIDE\",\"pir\":%b,\"door\":%b,\"radar\":%d,\"radar_v\":%.3f}",
            pirOn, doorOn, radarOn ? conf : 0, radarV);
        sendRaw(json);
    }

    private void sendRaw(String msg) {
        if (connected && out != null) {
            out.println(msg);
            logPanel.add("-> " + msg);
        } else {
            logPanel.add("[WARN] Not connected — cannot send: " + msg);
        }
    }

    // ─── Network connection ───────────────────────────────────────────────────
    private void attemptConnect() {
        if (connected) return;
        new Thread(() -> {
            try {
                logPanel.add("Connecting to Port 1 (Telemetry) at " + host + ":" + port + "...");
                socket = new Socket(host, port);
                out    = new PrintWriter(socket.getOutputStream(), true);
                in_br  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                statusLabel.setText("[CONNECTED: " + port + "]");
                statusLabel.setForeground(GREEN);
                logPanel.add("Connected to Telemetry Bridge!");

                // Connect to Port 2 (Physical Sensors Bus)
                connectTerminalBus();

                // Telemetry listening loop
                String line;
                while (connected && (line = in_br.readLine()) != null) {
                    final String packet = line;
                    SwingUtilities.invokeLater(() -> parsePacket(packet));
                }
            } catch (IOException e) {
                logPanel.add("[CONN ERROR] " + e.getMessage());
            } finally {
                disconnect();
            }
        }, "DashboardNetReader").start();
    }

    private void connectTerminalBus() {
        new Thread(() -> {
            try {
                logPanel.add("Connecting to Port 2 (Terminal Sensors Bus) at " + host + ":" + terminalPort + "...");
                terminalSocket = new Socket(host, terminalPort);
                terminalOut    = new PrintWriter(terminalSocket.getOutputStream(), true);
                terminalIn_br  = new BufferedReader(new InputStreamReader(terminalSocket.getInputStream()));
                terminalConnected = true;
                logPanel.add("Tapped into Port 2 (Physical Terminal Bus)!");
                String line;
                while (terminalConnected && (line = terminalIn_br.readLine()) != null) {
                    final String tPacket = line;
                    SwingUtilities.invokeLater(() -> logPanel.add("[BUS] " + tPacket));
                }
            } catch (IOException e) {
                logPanel.add("[PORT 2 ERROR] Terminal bus tap failed: " + e.getMessage());
                terminalConnected = false;
            }
        }, "DashboardTerminalBusReader").start();
    }

    private void disconnect() {
        connected = false;
        terminalConnected = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (terminalSocket != null) terminalSocket.close(); } catch (Exception ignored) {}
        statusLabel.setText("[DISCONNECTED]");
        statusLabel.setForeground(RED_C);
        logPanel.add("Disconnected from server.");
    }

    private void parsePacket(String json) {
        logPanel.add("<- " + json);
        String st = jsonStr(json, "state");
        if (!st.isEmpty()) roomState = st.toUpperCase();

        pirActive        = jsonBool(json, "pir",         pirActive);
        doorOpen         = jsonBool(json, "door",        doorOpen);
        radarConf        = jsonDouble(json, "radar",     radarConf);
        lightOn          = jsonBool(json, "light_relay", lightOn);
        hvacOn           = jsonBool(json, "hvac_relay",  hvacOn);
        hystRemainingSec = (long) jsonDouble(json, "hysteresis_remaining_s", hystRemainingSec);

        String dev = jsonStr(json, "device");
        if (!dev.isEmpty()) {
            deviceName = dev;
            deviceLabel.setText("Device: " + dev);
        }
    }

    private void startUiTimer() {
        uiTimer = new javax.swing.Timer(40, e -> repaint());
        uiTimer.start();
        attemptConnect();
    }

    // ─── Custom drawing panels ────────────────────────────────────────────────

    /** Large centre panel — shows VACANT / OCCUPIED / HYSTERESIS with glossy 3D glass bubble */
    class RoomStatePanel extends JPanel {
        RoomStatePanel() { setBackground(PANEL_BG); setBorder(titledBorder("ROOM STATE")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 14;
            int r = Math.min(w, h) / 3;
            if (r < 24) r = 24;

            Color stateBase = switch (roomState) {
                case "OCCUPIED"   -> GREEN;
                case "HYSTERESIS" -> AMBER;
                case "SLEEP"      -> new Color(192, 132, 252);
                default           -> DIM;
            };

            // ── STEP 0: Ambient Glow Halo ──
            float pulse = (float) (0.85 + 0.15 * Math.sin(System.currentTimeMillis() / 250.0));
            Color glowColor = new Color(stateBase.getRed(), stateBase.getGreen(), stateBase.getBlue(), (int) (40 * pulse));
            g2.setColor(glowColor);
            g2.fillOval(cx - r - 12, cy - r - 12, (r + 12) * 2, (r + 12) * 2);

            // ── STEP 1: Volumetric 3D Sphere Base with Radial Illumination ──
            Point2D center = new Point2D.Float(cx, cy + r * 0.18f);
            float gradRadius = r * 1.15f;
            float[] dist = {0.0f, 0.50f, 0.85f, 1.0f};

            Color brightCore = blendColor(stateBase, Color.WHITE, 0.38f);
            Color darkTone   = blendColor(stateBase, Color.BLACK, 0.35f);
            Color rimTone    = blendColor(stateBase, Color.BLACK, 0.65f);
            Color[] colors   = {brightCore, stateBase, darkTone, rimTone};

            RadialGradientPaint rgp = new RadialGradientPaint(center, gradRadius, dist, colors);
            g2.setPaint(rgp);
            g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);

            // ── STEP 4: Bottom Bounce Light (Reflective Rim) ──
            Shape oldClip = g2.getClip();
            g2.clip(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));

            int bounceW = (int) (r * 1.45);
            int bounceH = (int) (r * 0.55);
            int bounceX = cx - bounceW / 2;
            int bounceY = cy + r - bounceH + (int) (r * 0.05);

            LinearGradientPaint bounceGrad = new LinearGradientPaint(
                cx, bounceY, cx, bounceY + bounceH,
                new float[]{0.0f, 1.0f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 120)}
            );
            g2.setPaint(bounceGrad);
            g2.fillOval(bounceX, bounceY, bounceW, bounceH);

            // ── STEP 2 & 3: Top Glossy Specular Highlight (The 3D Glare) ──
            int hlW = (int) (r * 1.42);
            int hlH = (int) (r * 0.80);
            int hlX = cx - hlW / 2;
            int hlY = cy - r + (int) (r * 0.05);

            LinearGradientPaint topGlare = new LinearGradientPaint(
                cx, hlY, cx, hlY + hlH,
                new float[]{0.0f, 0.40f, 1.0f},
                new Color[]{
                    new Color(255, 255, 255, 245),
                    new Color(255, 255, 255, 130),
                    new Color(255, 255, 255, 0)
                }
            );
            g2.setPaint(topGlare);
            g2.fillOval(hlX, hlY, hlW, hlH);

            g2.setClip(oldClip);

            // ── STEP 5: Outer Crisp Glass Rim ──
            g2.setColor(new Color(255, 255, 255, 105));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);

            // ── STATE TEXT: High-Contrast Translucent Dark Glass Pill ──
            // Guarantees font color is NEVER similar or same as background!
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 17));
            FontMetrics fm = g2.getFontMetrics();
            int strW = fm.stringWidth(roomState);
            int pillW = strW + 28;
            int pillH = 32;
            int pillX = cx - pillW / 2;
            int pillY = cy - pillH / 2;

            g2.setColor(new Color(6, 10, 18, 220));
            g2.fillRoundRect(pillX, pillY, pillW, pillH, 16, 16);
            g2.setColor(new Color(255, 255, 255, 80));
            g2.drawRoundRect(pillX, pillY, pillW, pillH, 16, 16);

            g2.setColor(Color.WHITE);
            int tx = cx - strW / 2;
            int ty = cy + fm.getAscent() / 2 - 3;
            g2.drawString(roomState, tx, ty);

            // ── Subtitle Description Below Orb ──
            String desc = switch (roomState) {
                case "OCCUPIED"   -> "Occupancy Active — Systems Running";
                case "HYSTERESIS" -> "Grace Window — Awaiting Micro-Presence";
                case "SLEEP"      -> "Sleep Mode — Vital Respiration Active";
                default           -> "Room Empty — Energy Saving Standby";
            };
            g2.setColor(TEXT);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.drawString(desc, cx - fm2.stringWidth(desc) / 2, cy + r + 24);

            g2.dispose();
        }
    }

    private static Color blendColor(Color c1, Color c2, float ratio) {
        float r = c1.getRed() * (1 - ratio) + c2.getRed() * ratio;
        float g = c1.getGreen() * (1 - ratio) + c2.getGreen() * ratio;
        float b = c1.getBlue() * (1 - ratio) + c2.getBlue() * ratio;
        return new Color((int) Math.min(255, Math.max(0, r)),
                         (int) Math.min(255, Math.max(0, g)),
                         (int) Math.min(255, Math.max(0, b)));
    }

    /** Three LED-style indicators for PIR, Door, Radar (No emojis) */
    class SensorPanel extends JPanel {
        SensorPanel() { setBackground(PANEL_BG); setBorder(titledBorder("SENSOR READINGS")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int rowH = h / 3;

            drawLedRow(g2, w, rowH / 2,         "PIR Motion",        pirActive, pirActive ? "ACTIVE" : "NO MOTION");
            drawLedRow(g2, w, rowH + rowH / 2,     "Door Contact",      doorOpen,  doorOpen ? "OPEN" : "CLOSED");
            drawRadarRow(g2, w, rowH * 2 + rowH / 2, "Radar Confidence", radarConf);
        }

        private void drawLedRow(Graphics2D g, int w, int y, String label, boolean active, String val) {
            int led = 16;
            Color c = active ? GREEN : DIM;
            g.setColor(c);
            g.fillOval(20, y - led / 2, led, led);
            g.setColor(TEXT);
            g.setFont(new Font(FONT_FAMILY, Font.PLAIN, 13));
            g.drawString(label, 46, y + 5);
            g.setColor(active ? GREEN : new Color(148, 163, 184));
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(val, w - fm.stringWidth(val) - 16, y + 5);
        }

        private void drawRadarRow(Graphics2D g, int w, int y, String label, double conf) {
            int led = 16;
            Color c = conf >= 60 ? GREEN : conf >= 30 ? AMBER : DIM;
            g.setColor(c);
            g.fillOval(20, y - led / 2, led, led);
            g.setColor(TEXT);
            g.setFont(new Font(FONT_FAMILY, Font.PLAIN, 13));
            g.drawString(label, 46, y + 5);

            // Confidence bar
            int barW = w / 3;
            int barX = w / 2 - barW / 2;
            g.setColor(new Color(40, 40, 60));
            g.fillRoundRect(barX, y - 8, barW, 14, 6, 6);
            g.setColor(c);
            g.fillRoundRect(barX, y - 8, (int)(barW * conf / 100.0), 14, 6, 6);
            g.setColor(TEXT);
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
            String pctStr = String.format(Locale.US, "%.0f%%", conf);
            g.drawString(pctStr, w - 40, y + 5);
        }
    }

    /** Light and HVAC relay output indicators (No emojis) */
    class RelayPanel extends JPanel {
        RelayPanel() { setBackground(PANEL_BG); setBorder(titledBorder("ACTUATOR OUTPUTS")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            drawRelay(g2, w / 4,     h / 2, "LIGHTS (GPIO 21)",   lightOn, GREEN);
            drawRelay(g2, 3 * w / 4, h / 2, "HVAC / AC (GPIO 22)", hvacOn,  ACCENT);
        }

        private void drawRelay(Graphics2D g, int cx, int cy, String label, boolean on, Color c) {
            int r = Math.min(48, Math.min(cx, cy) - 22);
            // Glow
            if (on) {
                for (int i = r + 18; i > r; i -= 2) {
                    float a = (r + 18f - i) / 22f * 0.25f;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(a * 255)));
                    g.fillOval(cx - i, cy - i, 2 * i, 2 * i);
                }
            }
            g.setColor(on ? c : new Color(30, 41, 59));
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);

            // High contrast text inside relay circle: Dark navy on bright green/cyan, or silver on dark slate
            g.setColor(on ? new Color(15, 23, 42) : new Color(226, 232, 240));
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, on ? 13 : 12));
            String status = on ? "ON" : "OFF";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(status, cx - fm.stringWidth(status) / 2, cy + fm.getAscent() / 3);

            // Label below
            g.setColor(TEXT);
            g.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            fm = g.getFontMetrics();
            g.drawString(label, cx - fm.stringWidth(label) / 2, cy + r + 20);
        }
    }

    /** Hysteresis countdown arc (No emojis) */
    class HysteresisPanel extends JPanel {
        HysteresisPanel() { setBackground(PANEL_BG); setBorder(titledBorder("GRACE TIMER (Hysteresis)")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2;
            int r = Math.min(cx, cy) - 20;

            boolean inHyst = roomState.equals("HYSTERESIS");
            long totalSec  = 30; // 30s grace window
            double pct     = inHyst && totalSec > 0
                ? Math.min(1.0, (double) hystRemainingSec / totalSec) : 0.0;

            // Background ring
            g2.setColor(new Color(40, 40, 60));
            g2.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);

            // Countdown arc
            if (inHyst && pct > 0) {
                g2.setColor(AMBER);
                int angle = (int)(pct * 360);
                g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 90, angle);
            }

            g2.setStroke(new BasicStroke(1));
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 16));
            FontMetrics fm = g2.getFontMetrics();

            if (inHyst) {
                String timeStr = hystRemainingSec + " s";
                g2.setColor(AMBER);
                g2.drawString(timeStr, cx - fm.stringWidth(timeStr) / 2, cy + 4);
                g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                String sub = "Grace Countdown";
                g2.setColor(TEXT);
                g2.drawString(sub, cx - fm.stringWidth(sub) / 2, cy + 22);
            } else {
                g2.setColor(TEXT);
                String idle = roomState.equals("OCCUPIED") ? "SAFE" : "IDLE";
                g2.drawString(idle, cx - fm.stringWidth(idle) / 2, cy + 6);
            }
        }
    }

    /** Scrolling event log (No emojis) */
    class EventLogPanel extends JPanel {
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String>            list  = new JList<>(model);

        EventLogPanel() {
            setLayout(new BorderLayout());
            setBorder(titledBorder("EVENT LOG"));
            setBackground(PANEL_BG);
            list.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
            list.setBackground(new Color(14, 14, 22));
            list.setForeground(new Color(100, 220, 100));
            add(new JScrollPane(list), BorderLayout.CENTER);
        }

        void add(String msg) {
            SwingUtilities.invokeLater(() -> {
                if (model.size() > 200) model.removeElementAt(model.size() - 1);
                model.add(0, "[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg);
            });
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private JButton dashBtn(String text, Color c) {
        JButton b = new JButton(text);
        b.setBackground(c);
        // Contrast check: on bright green/amber, dark text; on red/accent, white text
        if (c.equals(GREEN) || c.equals(AMBER)) {
            b.setForeground(new Color(10, 15, 26));
        } else {
            b.setForeground(Color.WHITE);
        }
        b.setFocusPainted(false);
        b.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        b.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        return b;
    }

    private static JToggleButton styledToggle(String tag, String onTxt, String offTxt) {
        JToggleButton tb = new JToggleButton(tag + ": " + offTxt);
        tb.setBackground(new Color(30, 41, 59));
        tb.setForeground(TEXT);
        tb.setFocusPainted(false);
        tb.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        tb.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        tb.addChangeListener(e -> {
            boolean sel = tb.isSelected();
            tb.setText(tag + ": " + (sel ? onTxt : offTxt));
            // High contrast: When active, bright neon green background with dark navy text!
            // When inactive, dark slate background with crisp white text!
            if (sel) {
                tb.setBackground(new Color(34, 197, 94));
                tb.setForeground(new Color(10, 15, 26));
            } else {
                tb.setBackground(new Color(30, 41, 59));
                tb.setForeground(TEXT);
            }
        });
        return tb;
    }

    private Border titledBorder(String title) {
        return BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(50, 70, 110)),
            title, TitledBorder.LEFT, TitledBorder.TOP,
            new Font(FONT_FAMILY, Font.BOLD, 12), new Color(150, 180, 255));
    }

    // Naive JSON extractors (no extra deps)
    private String  jsonStr(String j, String k) {
        String s = "\"" + k + "\":\""; int i = j.indexOf(s);
        if (i < 0) return ""; i += s.length(); int e = j.indexOf('"', i);
        return e < 0 ? "" : j.substring(i, e);
    }
    private double  jsonDouble(String j, String k, double def) {
        String s = "\"" + k + "\":"; int i = j.indexOf(s);
        if (i < 0) return def; i += s.length(); int e = i;
        while (e < j.length() && (Character.isDigit(j.charAt(e)) || j.charAt(e) == '.' || j.charAt(e) == '-')) e++;
        try { return Double.parseDouble(j.substring(i, e)); } catch (Exception x) { return def; }
    }
    private boolean jsonBool(String j, String k, boolean def) {
        String s = "\"" + k + "\":"; int i = j.indexOf(s);
        if (i < 0) return def; i += s.length();
        if (j.startsWith("true",  i)) return true;
        if (j.startsWith("false", i)) return false;
        return def;
    }

    // ─── Entry point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int    port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SmartRoomDashboard(host, port));
    }
}
