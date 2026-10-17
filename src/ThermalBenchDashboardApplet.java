import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// ============================================================================
// THERMAL BENCH DASHBOARD APPLET — Telemetry Monitor & Efficiency Visualizer
// ============================================================================
// Connects to Port 1 (9092) - Virtual Wi-Fi Radio Telemetry Stream.
// Visualizes:
//   - Real-time Efficiency Arc Gauge (eta = Pout / Pin * 100%)
//   - Electrical Power Balance (Pin, Pout, Ploss)
//   - 4-Zone Thermocouple Array Hotspot Map (MOSFET, Core, Diode, Ambient)
//   - Closed-Loop Fan Speed & Safety Interlock Status
//   - Live JSON Telemetry Feed
// ============================================================================
public class ThermalBenchDashboardApplet extends JFrame {

    // ─── Theme Colors ────────────────────────────────────────────────────────
    private static final Color BG_DARK       = new Color(15, 18, 26);
    private static final Color CARD_BG       = new Color(22, 28, 42);
    private static final Color ACCENT_GREEN  = new Color(50, 220, 120);
    private static final Color ACCENT_AMBER  = new Color(255, 180, 50);
    private static final Color ACCENT_RED    = new Color(245, 70, 70);
    private static final Color ACCENT_CYAN   = new Color(40, 200, 245);
    private static final Color ACCENT_PURPLE = new Color(160, 110, 245);
    private static final Color TEXT_MAIN     = new Color(230, 235, 245);
    private static final Color TEXT_MUTED    = new Color(130, 140, 165);

    // ─── Networking (Port 1 Wireless Telemetry) ──────────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Telemetry State Registers ───────────────────────────────────────────
    private String statusStr = "NORMAL";
    private boolean psuEnabled = true;

    private double vin = 24.0, iin = 0.0, pinW = 0.0;
    private double vout = 12.0, iout = 0.0, poutW = 0.0;
    private double efficiency = 0.0;
    private double plossW = 0.0;

    private double tempMosfet = 25.0;
    private double tempCore = 25.0;
    private double tempDiode = 25.0;
    private double tempAmbient = 25.0;

    private int fanPwm = 15;

    // ─── UI Widgets ──────────────────────────────────────────────────────────
    private final JLabel headerStatusLbl = new JLabel("● DISCONNECTED");
    private final JLabel headerDeviceLbl = new JLabel("DEVICE: BenchMasterMCU");

    private final EfficiencyGaugePanel gaugePanel = new EfficiencyGaugePanel();
    private final ElectricalPanel elecPanel = new ElectricalPanel();
    private final ThermalHotspotPanel thermalPanel = new ThermalHotspotPanel();

    private final DefaultListModel<String> packetLogModel = new DefaultListModel<>();
    private final JList<String> packetList = new JList<>(packetLogModel);

    public ThermalBenchDashboardApplet(String host, int port) {
        super("Thermal-Aware Power Supply Benchmark — Telemetry Dashboard");
        this.host = host;
        this.port = port;

        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1020, 720);
        setMinimumSize(new Dimension(880, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        // Repaint timer for smooth gauge animations
        Timer animTimer = new Timer(50, e -> repaint());
        animTimer.start();

        SwingUtilities.invokeLater(this::connect);
    }

    public ThermalBenchDashboardApplet() {
        this("localhost", 9092);
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(12, 14, 12, 14));

        // ── TOP: Header Bar ──────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setBackground(CARD_BG);
        topBar.setBorder(new CompoundBorder(
            new LineBorder(new Color(36, 44, 66), 1, true),
            new EmptyBorder(10, 16, 10, 16)
        ));

        JLabel titleLbl = new JLabel("⚡ THERMAL-AWARE POWER BENCHMARK DASHBOARD");
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_CYAN);

        headerDeviceLbl.setFont(new Font("Monospaced", Font.BOLD, 12));
        headerDeviceLbl.setForeground(TEXT_MAIN);

        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        leftBox.setOpaque(false);
        leftBox.add(titleLbl);
        leftBox.add(headerDeviceLbl);

        headerStatusLbl.setFont(new Font("Monospaced", Font.BOLD, 12));
        headerStatusLbl.setForeground(ACCENT_RED);

        topBar.add(leftBox, BorderLayout.CENTER);
        topBar.add(headerStatusLbl, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ── CENTER: Visualizer Panels ─────────────────────────────────────────
        JPanel centerGrid = new JPanel(new GridLayout(1, 3, 10, 0));
        centerGrid.setOpaque(false);

        centerGrid.add(gaugePanel);
        centerGrid.add(elecPanel);
        centerGrid.add(thermalPanel);

        add(centerGrid, BorderLayout.CENTER);

        // ── BOTTOM: Telemetry Packet Log ─────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.setBackground(CARD_BG);
        bottomPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(36, 44, 66), 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));
        bottomPanel.setPreferredSize(new Dimension(100, 160));

        JLabel logHeader = new JLabel("Virtual Wi-Fi Telemetry Stream (Port " + port + "):");
        logHeader.setFont(new Font("Segoe UI", Font.BOLD, 12));
        logHeader.setForeground(TEXT_MUTED);

        packetList.setBackground(new Color(10, 14, 22));
        packetList.setForeground(new Color(140, 225, 255));
        packetList.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(packetList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        bottomPanel.add(logHeader, BorderLayout.NORTH);
        bottomPanel.add(scroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // =========================================================================
    // 1. EFFICIENCY GAUGE PANEL
    // =========================================================================
    private class EfficiencyGaugePanel extends JPanel {
        public EfficiencyGaugePanel() {
            setOpaque(false);
            setBorder(new CompoundBorder(
                new LineBorder(new Color(36, 44, 66), 1, true),
                new EmptyBorder(12, 12, 12, 12)
            ));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, w, h, 12, 12);

            // Title
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.setColor(TEXT_MUTED);
            g2.drawString("CONVERTER EFFICIENCY (η)", 14, 24);

            // Circular Gauge Arc
            int size = Math.min(w - 60, h - 140);
            int cx = w / 2;
            int cy = 35 + size / 2;
            int r = size / 2;

            // Background Track Arc (240 degrees)
            g2.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(30, 36, 54));
            g2.drawArc(cx - r, cy - r, size, size, 150, -240);

            // Progress Arc
            Color gaugeColor = efficiency >= 90.0 ? ACCENT_GREEN : (efficiency >= 80.0 ? ACCENT_AMBER : ACCENT_RED);
            if (efficiency < 1.0) gaugeColor = TEXT_MUTED;

            int sweep = (int) Math.round((efficiency / 100.0) * -240.0);
            g2.setColor(gaugeColor);
            g2.drawArc(cx - r, cy - r, size, size, 150, sweep);

            // Center Text: Value
            g2.setFont(new Font("Segoe UI", Font.BOLD, 32));
            g2.setColor(TEXT_MAIN);
            String effStr = String.format(Locale.US, "%.1f%%", efficiency);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(effStr, cx - fm.stringWidth(effStr) / 2, cy + 10);

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g2.setColor(TEXT_MUTED);
            String sub = (efficiency > 0.1) ? "Pout / Pin" : "IDLE";
            g2.drawString(sub, cx - g2.getFontMetrics().stringWidth(sub) / 2, cy + 30);

            // Bottom Badges: Status & Fan
            int badgeY = h - 65;
            drawBadge(g2, 14, badgeY, (w / 2) - 18, 50, "RIG STATUS", statusStr, getStatusColor(statusStr));
            drawBadge(g2, (w / 2) + 4, badgeY, (w / 2) - 18, 50, "FAN PWM", fanPwm + "%", ACCENT_CYAN);

            g2.dispose();
        }

        private Color getStatusColor(String st) {
            return switch (st) {
                case "NORMAL" -> ACCENT_GREEN;
                case "FAN_ACTIVE" -> ACCENT_CYAN;
                case "THERMAL_THROTTLING" -> ACCENT_AMBER;
                default -> ACCENT_RED; // TRIP
            };
        }

        private void drawBadge(Graphics2D g2, int x, int y, int bw, int bh, String label, String val, Color c) {
            g2.setColor(new Color(16, 20, 32));
            g2.fillRoundRect(x, y, bw, bh, 8, 8);
            g2.setColor(TEXT_MUTED);
            g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            g2.drawString(label, x + 8, y + 16);
            g2.setColor(c);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.drawString(val, x + 8, y + 36);
        }
    }

    // =========================================================================
    // 2. ELECTRICAL METRICS PANEL
    // =========================================================================
    private class ElectricalPanel extends JPanel {
        public ElectricalPanel() {
            setOpaque(false);
            setBorder(new CompoundBorder(
                new LineBorder(new Color(36, 44, 66), 1, true),
                new EmptyBorder(12, 12, 12, 12)
            ));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, w, h, 12, 12);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.setColor(TEXT_MUTED);
            g2.drawString("ELECTRICAL POWER BALANCE", 14, 24);

            int cardH = (h - 55) / 3;

            // Box 1: Input Power
            drawPowerCard(g2, 12, 38, w - 24, cardH - 6,
                "INPUT DC RAIL (Bench Source)",
                String.format(Locale.US, "%.1f W", pinW),
                String.format(Locale.US, "Vin = %.2f V  |  Iin = %.2f A", vin, iin),
                ACCENT_CYAN);

            // Box 2: Output Power
            drawPowerCard(g2, 12, 38 + cardH, w - 24, cardH - 6,
                "OUTPUT POWER (Regulated Rail)",
                String.format(Locale.US, "%.1f W", poutW),
                String.format(Locale.US, "Vout = %.2f V  |  Iout = %.2f A", vout, iout),
                ACCENT_GREEN);

            // Box 3: Dissipated Heat Loss
            drawPowerCard(g2, 12, 38 + (cardH * 2), w - 24, cardH - 6,
                "THERMAL DISSIPATION LOSS (Ploss)",
                String.format(Locale.US, "%.1f W (Heat)", plossW),
                String.format(Locale.US, "Loss = Pin - Pout  [%s]", psuEnabled ? "PSU ACTIVE" : "SAFETY CUTOFF"),
                psuEnabled ? ACCENT_ORANGE : ACCENT_RED);

            g2.dispose();
        }

        private static final Color ACCENT_ORANGE = new Color(255, 140, 50);

        private void drawPowerCard(Graphics2D g2, int x, int y, int bw, int bh, String header, String mainVal, String sub, Color color) {
            g2.setColor(new Color(16, 20, 32));
            g2.fillRoundRect(x, y, bw, bh, 8, 8);

            // Left color pill
            g2.setColor(color);
            g2.fillRoundRect(x + 8, y + 10, 4, bh - 20, 2, 2);

            g2.setColor(TEXT_MUTED);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g2.drawString(header, x + 20, y + 20);

            g2.setColor(color);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 20));
            g2.drawString(mainVal, x + 20, y + 44);

            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g2.drawString(sub, x + 20, y + 64);
        }
    }

    // =========================================================================
    // 3. 4-ZONE THERMAL HOTSPOT MONITOR
    // =========================================================================
    private class ThermalHotspotPanel extends JPanel {
        public ThermalHotspotPanel() {
            setOpaque(false);
            setBorder(new CompoundBorder(
                new LineBorder(new Color(36, 44, 66), 1, true),
                new EmptyBorder(12, 12, 12, 12)
            ));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, w, h, 12, 12);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 13));
            g2.setColor(TEXT_MUTED);
            g2.drawString("4-ZONE THERMOCOUPLE ARRAY", 14, 24);

            int barY = 40;
            int barH = (h - 60) / 4;

            drawTempBar(g2, 12, barY, w - 24, barH - 8, "MOSFET Hotspot", tempMosfet, 125.0);
            drawTempBar(g2, 12, barY + barH, w - 24, barH - 8, "Transformer Core", tempCore, 125.0);
            drawTempBar(g2, 12, barY + (barH * 2), w - 24, barH - 8, "Output Rectifier Diode", tempDiode, 125.0);
            drawTempBar(g2, 12, barY + (barH * 3), w - 24, barH - 8, "Chamber Ambient (Tamb)", tempAmbient, 100.0);

            g2.dispose();
        }

        private void drawTempBar(Graphics2D g2, int x, int y, int bw, int bh, String label, double temp, double maxT) {
            g2.setColor(new Color(16, 20, 32));
            g2.fillRoundRect(x, y, bw, bh, 8, 8);

            Color tempColor = temp >= 90.0 ? ACCENT_RED : (temp >= 70.0 ? ACCENT_AMBER : ACCENT_GREEN);

            g2.setColor(TEXT_MAIN);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2.drawString(label, x + 10, y + 18);

            String tStr = String.format(Locale.US, "%.1f °C", temp);
            g2.setColor(tempColor);
            g2.drawString(tStr, x + bw - g2.getFontMetrics().stringWidth(tStr) - 10, y + 18);

            // Progress bar
            int pX = x + 10;
            int pY = y + 26;
            int pW = bw - 20;
            int pH = bh - 34;

            g2.setColor(new Color(28, 34, 50));
            g2.fillRoundRect(pX, pY, pW, pH, 4, 4);

            int fillW = (int) Math.min(pW, Math.round((temp / maxT) * pW));
            g2.setColor(tempColor);
            g2.fillRoundRect(pX, pY, fillW, pH, 4, 4);
        }
    }

    // =========================================================================
    // NETWORKING (Port 1 Telemetry Reader)
    // =========================================================================
    private void connect() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to " + host + ":" + port + "...", ACCENT_AMBER);
                socket = new Socket(host, port);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                updateStatus("● CONNECTED (Port " + port + ")", ACCENT_GREEN);
                logPacket("[CONNECTED] Listening to benchmark telemetry at " + host + ":" + port);

                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String packet = line;
                    SwingUtilities.invokeLater(() -> handleTelemetry(packet));
                }
            } catch (IOException e) {
                updateStatus("● DISCONNECTED", ACCENT_RED);
                logPacket("[DISCONNECTED] " + e.getMessage());
                connected = false;
            }
        }, "BenchDashboardTelemetryReader").start();
    }

    private void handleTelemetry(String json) {
        logPacket("← " + json);

        String dev = extractJsonVal(json, "device");
        if (!dev.isEmpty()) headerDeviceLbl.setText("DEVICE: " + dev);

        String st = extractJsonVal(json, "status");
        if (!st.isEmpty()) statusStr = st;

        psuEnabled = extractJsonBool(json, "psu_enabled", psuEnabled);

        vin  = extractJsonDouble(json, "vin", vin);
        iin  = extractJsonDouble(json, "iin", iin);
        pinW = extractJsonDouble(json, "pin_w", pinW);

        vout  = extractJsonDouble(json, "vout", vout);
        iout  = extractJsonDouble(json, "iout", iout);
        poutW = extractJsonDouble(json, "pout_w", poutW);

        efficiency = extractJsonDouble(json, "efficiency", efficiency);
        plossW     = extractJsonDouble(json, "ploss_w", plossW);

        tempMosfet  = extractJsonDouble(json, "temp_mosfet", tempMosfet);
        tempCore    = extractJsonDouble(json, "temp_core", tempCore);
        tempDiode   = extractJsonDouble(json, "temp_diode", tempDiode);
        tempAmbient = extractJsonDouble(json, "temp_ambient", tempAmbient);

        fanPwm = (int) extractJsonDouble(json, "fan_pwm", fanPwm);
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
            if (packetLogModel.size() > 50) packetLogModel.removeElementAt(packetLogModel.size() - 1);
        });
    }

    // ─── JSON Helpers ────────────────────────────────────────────────────────
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
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9092;
        SwingUtilities.invokeLater(() -> new ThermalBenchDashboardApplet(host, port));
    }
}
