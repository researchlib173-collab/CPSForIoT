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
// ============================================================================
public class SmartRoomDashboard extends JFrame {

    // ─── Colours ──────────────────────────────────────────────────────────────
    static final Color BG        = new Color(18, 18, 28);
    static final Color PANEL_BG  = new Color(26, 26, 40);
    static final Color ACCENT    = new Color(80, 140, 255);
    static final Color GREEN     = new Color(50, 220, 100);
    static final Color AMBER     = new Color(255, 190, 50);
    static final Color RED_C     = new Color(230, 70,  60);
    static final Color DIM       = new Color(70,  70,  90);
    static final Color TEXT      = new Color(210, 215, 230);

    // ─── Network ─────────────────────────────────────────────────────────────
    private Socket       socket;
    private PrintWriter  out;
    private BufferedReader in_br;
    private volatile boolean connected = false;
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
    private final JLabel           statusLabel    = new JLabel("● DISCONNECTED");
    private final JLabel           deviceLabel    = new JLabel("Device: —");

    // ─── Input controls (inject sensor values) ────────────────────────────────
    private final JToggleButton pirBtn    = styledToggle("PIR",   "Motion", "No Motion");
    private final JToggleButton doorBtn   = styledToggle("DOOR",  "Open",   "Closed");
    private final JToggleButton radarBtn  = styledToggle("RADAR", "Present","No Presence");
    private final JSlider       radarSlider = new JSlider(0, 100, 0);

    // ─── Refresh timer ────────────────────────────────────────────────────────
    private javax.swing.Timer uiTimer;

    public SmartRoomDashboard(String host, int port) {
        super("Smart Room Dashboard — CPS Sensor Fusion");
        this.host = host; this.port = port;
        buildUI();
        startUiTimer();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1050, 720);
        setMinimumSize(new Dimension(900, 600));
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
        deviceLabel.setFont(new Font("Monospaced", Font.BOLD, 13));

        statusLabel.setForeground(RED_C);
        statusLabel.setFont(new Font("Monospaced", Font.BOLD, 12));

        JButton connectBtn = dashBtn("Connect", GREEN);
        connectBtn.addActionListener(e -> attemptConnect());
        JButton disconnectBtn = dashBtn("Disconnect", RED_C);
        disconnectBtn.addActionListener(e -> disconnect());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        right.setOpaque(false);
        right.add(new JLabel(host + ":" + port + "  "));
        right.add(connectBtn); right.add(disconnectBtn);

        bar.add(deviceLabel, BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(right,       BorderLayout.EAST);
        return bar;
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

    /** Bottom bar — sensor toggle buttons sent to board via socket */
    private JPanel buildInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(new Color(22, 22, 38));
        bar.setBorder(titledBorder("🎛  SENSOR INPUTS  —  toggle to inject signal into board"));

        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        toggles.setOpaque(false);
        toggles.add(pirBtn);
        toggles.add(doorBtn);
        toggles.add(radarBtn);
        toggles.add(new JLabel("  Radar Confidence: "));
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
        manualField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        manualField.setBackground(new Color(30, 30, 50));
        manualField.setForeground(TEXT);
        manualField.setCaretColor(Color.WHITE);
        JButton sendBtn = dashBtn("Send ▶", ACCENT);
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
        double radarV = pirBtn.isSelected()
            ? 0.0                                     // PIR overrides radar slot?  No — independent
            : 0.0;
        // Build the command packet
        boolean pirOn   = pirBtn.isSelected();
        boolean doorOn  = doorBtn.isSelected();
        boolean radarOn = radarBtn.isSelected();
        int     conf    = radarSlider.getValue();

        // Translate to voltage commands for the board pins
        sendRaw(String.format(
            "{\"cmd\":\"SET_PINS\",\"GPIO_12\":%.1f,\"GPIO_14\":%.1f,\"GPIO_27\":%.3f}",
            pirOn  ? 3.3 : 0.0,
            doorOn ? 3.3 : 0.0,
            radarOn ? (conf / 100.0 * 3.3) : 0.0
        ));
    }

    // ─── Networking ───────────────────────────────────────────────────────────
    private void attemptConnect() {
        new Thread(() -> {
            try {
                setStatus("Connecting...", AMBER);
                socket = new Socket(host, port);
                out   = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                in_br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                setStatus("● CONNECTED  →  " + host + ":" + port, GREEN);
                logPanel.add("Connected to CPS workbench at " + host + ":" + port);
                new Thread(this::readLoop, "DashReader").start();
            } catch (IOException ex) {
                setStatus("● FAILED: " + ex.getMessage(), RED_C);
                logPanel.add("[ERROR] " + ex.getMessage());
            }
        }, "DashConnect").start();
    }

    private void readLoop() {
        try {
            String line;
            while (connected && (line = in_br.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> handleTelemetry(msg));
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                setStatus("● DISCONNECTED", RED_C);
                logPanel.add("[DISCONNECT] " + e.getMessage());
                connected = false;
            });
        }
    }

    private void handleTelemetry(String msg) {
        logPanel.add("← " + msg);

        String state = jsonStr(msg, "state");
        if (!state.isEmpty()) roomState = state.toUpperCase();

        String dev = jsonStr(msg, "device");
        if (!dev.isEmpty()) { deviceName = dev; deviceLabel.setText("Device: " + dev); }

        pirActive        = jsonBool(msg, "pir",          pirActive);
        doorOpen         = jsonBool(msg, "door",         doorOpen);
        radarConf        = jsonDouble(msg, "radar",      radarConf);
        lightOn          = jsonBool(msg, "light_relay",  lightOn);
        hvacOn           = jsonBool(msg, "hvac_relay",   hvacOn);
        hystRemainingSec = (long) jsonDouble(msg, "hysteresis_remaining_s", hystRemainingSec);
    }

    private void sendRaw(String json) {
        if (out != null && connected) { out.println(json); logPanel.add("→ " + json); }
        else logPanel.add("[WARN] Not connected.");
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        setStatus("● DISCONNECTED", RED_C);
        logPanel.add("Disconnected.");
    }

    // ─── UI Refresh timer (re-paints custom panels every 500 ms) ─────────────
    private void startUiTimer() {
        uiTimer = new javax.swing.Timer(500, e -> {
            roomStatePanel.repaint();
            sensorPanel.repaint();
            relayPanel.repaint();
            hystPanel.repaint();
        });
        uiTimer.start();
    }

    private void setStatus(String t, Color c) {
        SwingUtilities.invokeLater(() -> { statusLabel.setText(t); statusLabel.setForeground(c); });
    }

    // ─── Custom drawing panels ────────────────────────────────────────────────

    /** Large centre panel — shows VACANT / OCCUPIED / HYSTERESIS with animated glow */
    class RoomStatePanel extends JPanel {
        RoomStatePanel() { setBackground(PANEL_BG); setBorder(titledBorder("🏠  ROOM STATE")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2;

            Color stateColor = switch (roomState) {
                case "OCCUPIED"   -> GREEN;
                case "HYSTERESIS" -> AMBER;
                default           -> DIM;
            };

            // Glow circle
            int r = Math.min(w, h) / 3;
            for (int i = r + 30; i > r; i -= 2) {
                float alpha = (r + 30f - i) / 40f * 0.15f;
                g2.setColor(new Color(stateColor.getRed(), stateColor.getGreen(),
                    stateColor.getBlue(), (int)(alpha * 255)));
                g2.fillOval(cx - i, cy - i, 2 * i, 2 * i);
            }
            g2.setColor(stateColor);
            g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);

            // Icon / letter
            g2.setColor(BG);
            g2.setFont(new Font("Monospaced", Font.BOLD, r / 2));
            String icon = switch (roomState) {
                case "OCCUPIED"   -> "●";
                case "HYSTERESIS" -> "◑";
                default           -> "○";
            };
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(icon, cx - fm.stringWidth(icon) / 2, cy + fm.getAscent() / 3);

            // State label
            g2.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2.setColor(stateColor);
            fm = g2.getFontMetrics();
            g2.drawString(roomState, cx - fm.stringWidth(roomState) / 2, cy + r + 22);
        }
    }

    /** Three LED-style indicators for PIR, Door, Radar */
    class SensorPanel extends JPanel {
        SensorPanel() { setBackground(PANEL_BG); setBorder(titledBorder("👁  SENSOR READINGS")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int step = h / 4;

            drawSensorRow(g2, w, step,     "PIR Motion",        pirActive,          pirActive  ? GREEN : DIM, pirActive ? "MOTION" : "IDLE");
            drawSensorRow(g2, w, step * 2, "Door Contact",       doorOpen,           doorOpen   ? AMBER : DIM, doorOpen  ? "OPEN"   : "CLOSED");
            drawRadarRow( g2, w, step * 3, "mmWave Radar",       radarConf);
        }

        private void drawSensorRow(Graphics2D g, int w, int y, String label, boolean active, Color c, String val) {
            int led = 16;
            g.setColor(c);
            g.fillOval(20, y - led / 2, led, led);
            g.setColor(TEXT);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(label, 46, y + 5);
            g.setColor(active ? c : DIM);
            g.setFont(new Font("Monospaced", Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(val, w - fm.stringWidth(val) - 16, y + 5);
        }

        private void drawRadarRow(Graphics2D g, int w, int y, String label, double conf) {
            int led = 16;
            Color c = conf >= 60 ? GREEN : conf >= 30 ? AMBER : DIM;
            g.setColor(c);
            g.fillOval(20, y - led / 2, led, led);
            g.setColor(TEXT);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(label, 46, y + 5);

            // Confidence bar
            int barW = w / 3;
            int barX = w / 2 - barW / 2;
            g.setColor(new Color(40, 40, 60));
            g.fillRoundRect(barX, y - 8, barW, 14, 6, 6);
            g.setColor(c);
            g.fillRoundRect(barX, y - 8, (int)(barW * conf / 100.0), 14, 6, 6);
            g.setColor(TEXT);
            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            String pctStr = String.format("%.0f%%", conf);
            g.drawString(pctStr, w - 40, y + 5);
        }
    }

    /** Light and HVAC relay output indicators */
    class RelayPanel extends JPanel {
        RelayPanel() { setBackground(PANEL_BG); setBorder(titledBorder("⚡  ACTUATOR OUTPUTS")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            drawRelay(g2, w / 4,     h / 2, "💡 LIGHTS",   lightOn, GREEN);
            drawRelay(g2, 3 * w / 4, h / 2, "❄  HVAC",     hvacOn,  ACCENT);
        }

        private void drawRelay(Graphics2D g, int cx, int cy, String label, boolean on, Color c) {
            int r = Math.min(50, Math.min(cx, cy) - 20);
            // Glow
            if (on) {
                for (int i = r + 20; i > r; i -= 2) {
                    float a = (r + 20f - i) / 25f * 0.2f;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(a * 255)));
                    g.fillOval(cx - i, cy - i, 2 * i, 2 * i);
                }
            }
            g.setColor(on ? c : DIM);
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);

            g.setColor(BG);
            g.setFont(new Font("SansSerif", Font.BOLD, on ? 11 : 10));
            String status = on ? "ON" : "OFF";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(status, cx - fm.stringWidth(status) / 2, cy + fm.getAscent() / 3);

            g.setColor(on ? c : DIM);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            fm = g.getFontMetrics();
            g.drawString(label, cx - fm.stringWidth(label) / 2, cy + r + 18);
        }
    }

    /** Hysteresis countdown arc */
    class HysteresisPanel extends JPanel {
        HysteresisPanel() { setBackground(PANEL_BG); setBorder(titledBorder("⏱  GRACE TIMER (Hysteresis)")); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2;
            int r = Math.min(cx, cy) - 20;

            boolean inHyst = roomState.equals("HYSTERESIS");
            long totalSec  = 900; // Default 15 min — override from telemetry if needed
            double pct     = inHyst && totalSec > 0
                ? Math.min(1.0, (double) hystRemainingSec / totalSec) : 0.0;

            // Background ring
            g2.setColor(new Color(40, 40, 60));
            g2.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);

            // Countdown arc (shrinks as time runs out)
            if (inHyst && pct > 0) {
                g2.setColor(AMBER);
                int angle = (int)(pct * 360);
                g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 90, angle);
            }

            g2.setStroke(new BasicStroke(1));
            g2.setFont(new Font("Monospaced", Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();

            if (inHyst) {
                long mins = hystRemainingSec / 60, secs = hystRemainingSec % 60;
                String timeStr = String.format("%02d:%02d", mins, secs);
                g2.setColor(AMBER);
                g2.drawString(timeStr, cx - fm.stringWidth(timeStr) / 2, cy + 6);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                String sub = "remaining";
                g2.drawString(sub, cx - fm.stringWidth(sub) / 2, cy + 22);
            } else {
                g2.setColor(DIM);
                String idle = roomState.equals("OCCUPIED") ? "OCCUPIED" : "INACTIVE";
                g2.drawString(idle, cx - fm.stringWidth(idle) / 2, cy + 6);
            }
        }
    }

    /** Scrolling event log */
    class EventLogPanel extends JPanel {
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String>            list  = new JList<>(model);

        EventLogPanel() {
            setLayout(new BorderLayout());
            setBorder(titledBorder("📋  EVENT LOG"));
            setBackground(PANEL_BG);
            list.setFont(new Font("Monospaced", Font.PLAIN, 11));
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
        b.setBackground(c); b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        return b;
    }

    private static JToggleButton styledToggle(String tag, String onTxt, String offTxt) {
        JToggleButton tb = new JToggleButton(tag + ": " + offTxt);
        tb.setBackground(DIM); tb.setForeground(TEXT);
        tb.setFocusPainted(false);
        tb.setFont(new Font("Monospaced", Font.BOLD, 12));
        tb.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        tb.addChangeListener(e -> {
            boolean sel = tb.isSelected();
            tb.setText(tag + ": " + (sel ? onTxt : offTxt));
            tb.setBackground(sel ? new Color(50, 180, 80) : DIM);
        });
        return tb;
    }

    private Border titledBorder(String title) {
        return BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(50, 70, 110)),
            title, TitledBorder.LEFT, TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 12), new Color(150, 180, 255));
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
