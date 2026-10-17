import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

// ============================================================================
// PART 4: HARDWARE INPUT/OUTPUT SIMULATOR GUI
// Connects to the workbench via TCP socket to send sensor inputs and
// visualize board outputs in real-time.
// Usage: java SimulatorApp [host] [port]
//   Defaults: localhost 9090
// ============================================================================
public class SimulatorApp extends JFrame {

    // ─── Network ─────────────────────────────────────────────────────────────
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── UI State ────────────────────────────────────────────────────────────
    private final JLabel  statusLabel    = new JLabel("● DISCONNECTED", SwingConstants.CENTER);
    private final JTextArea logArea      = new JTextArea();
    private final DefaultListModel<String> messageModel = new DefaultListModel<>();

    // ─── Sensor Sliders (Inputs → board) ─────────────────────────────────────
    private final JSlider tempSlider     = new JSlider(0, 100, 25);    // 0-100°C
    private final JSlider humidSlider    = new JSlider(0, 100, 50);    // 0-100% RH
    private final JSlider lightSlider    = new JSlider(0, 1023, 512);  // ADC raw 0-1023
    private final JToggleButton motionBtn = new JToggleButton("PIR: NO MOTION");

    // ─── Output Displays (board → GUI) ───────────────────────────────────────
    private final JProgressBar ledBar    = new JProgressBar(0, 100);
    private final JLabel       ledLabel  = new JLabel("LED: OFF");
    private final JLabel       gpio12Lbl = new JLabel("GPIO12: LOW");
    private final JLabel       deviceLbl = new JLabel("Device: —");

    // ─── Gauge panels ────────────────────────────────────────────────────────
    private GaugePanel tempGauge;
    private GaugePanel humidGauge;

    // ─── Settings ────────────────────────────────────────────────────────────
    private final String host;
    private final int    port;

    public SimulatorApp(String host, int port) {
        super("CPS Hardware Simulator – I/O Testbed");
        this.host = host;
        this.port = port;
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 680);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ─── UI Construction ─────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout(6, 6));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── Top toolbar ──────────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(new Color(30, 30, 40));

        statusLabel.setForeground(Color.RED);
        statusLabel.setFont(new Font("Space Mono", Font.BOLD, 13));
        topBar.add(statusLabel, BorderLayout.WEST);

        JButton connectBtn = makeButton("Connect", new Color(40, 180, 80));
        connectBtn.addActionListener(e -> attemptConnect());
        JButton disconnectBtn = makeButton("Disconnect", new Color(200, 60, 60));
        disconnectBtn.addActionListener(e -> disconnect());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        btnPanel.setOpaque(false);
        btnPanel.add(new JLabel("Host: " + host + ":" + port + "  ", SwingConstants.RIGHT));
        btnPanel.add(connectBtn);
        btnPanel.add(disconnectBtn);
        topBar.add(btnPanel, BorderLayout.EAST);
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(topBar, BorderLayout.NORTH);

        // ── Center: split into input (left) and output (right) ──────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildInputPanel(), buildOutputPanel());
        split.setDividerLocation(430);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        // ── Bottom: telemetry log ────────────────────────────────────────────
        logArea.setEditable(false);
        logArea.setFont(new Font("Space Mono", Font.PLAIN, 11));
        logArea.setBackground(new Color(15, 15, 25));
        logArea.setForeground(new Color(100, 220, 100));
        logArea.setRows(6);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(titledBorder("📡 Telemetry Log (incoming JSON from board)"));
        add(logScroll, BorderLayout.SOUTH);
    }

    private JPanel buildInputPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(titledBorder("🎛  SENSOR INPUTS  (sent to board every tick)"));
        p.setBackground(new Color(22, 22, 32));

        tempGauge  = new GaugePanel("Temperature", "°C", 0, 100, Color.ORANGE);
        humidGauge = new GaugePanel("Humidity",    "%RH", 0, 100, Color.CYAN);

        p.add(labeledSlider("🌡  Temperature (°C)", tempSlider, v -> {
            tempGauge.setValue(v);
            if (connected) sendCommand(String.format("{\"cmd\":\"SET_TEMP\",\"value\":%.1f}", (double) v));
        }));

        p.add(labeledSlider("💧 Humidity (%RH)", humidSlider, v -> {
            humidGauge.setValue(v);
            if (connected) sendCommand(String.format("{\"cmd\":\"SET_HUMID\",\"value\":%.1f}", (double) v));
        }));

        p.add(labeledSlider("💡 Light Level (ADC 0-1023)", lightSlider, v -> {
            double voltage = v / 1023.0 * 3.3;
            if (connected) sendCommand(String.format("{\"cmd\":\"SET_LIGHT\",\"adc\":%d,\"voltage\":%.3f}", v, voltage));
        }));

        // PIR motion toggle
        motionBtn.setBackground(new Color(50, 50, 70));
        motionBtn.setForeground(Color.WHITE);
        motionBtn.setFont(new Font("Space Mono", Font.BOLD, 12));
        motionBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        motionBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        motionBtn.addActionListener(e -> {
            boolean m = motionBtn.isSelected();
            motionBtn.setText(m ? "PIR: MOTION DETECTED !" : "PIR: NO MOTION");
            motionBtn.setBackground(m ? new Color(200, 60, 30) : new Color(50, 50, 70));
            if (connected) sendCommand(String.format("{\"cmd\":\"SET_PIR\",\"motion\":%b}", m));
        });
        p.add(Box.createVerticalStrut(10));
        p.add(motionBtn);
        p.add(Box.createVerticalStrut(10));

        // Gauge row
        JPanel gaugeRow = new JPanel(new GridLayout(1, 2, 6, 0));
        gaugeRow.setBackground(new Color(22, 22, 32));
        gaugeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        gaugeRow.add(tempGauge);
        gaugeRow.add(humidGauge);
        p.add(gaugeRow);
        p.add(Box.createVerticalGlue());

        // Manual JSON command
        p.add(Box.createVerticalStrut(10));
        p.add(new JLabel("  Manual JSON Command:"));
        JTextField manualField = new JTextField("{\"cmd\":\"PING\"}");
        manualField.setFont(new Font("Space Mono", Font.PLAIN, 12));
        manualField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JButton sendBtn = makeButton("Send ▶", new Color(40, 120, 200));
        sendBtn.addActionListener(e -> {
            sendCommand(manualField.getText().trim());
            manualField.selectAll();
        });
        manualField.addActionListener(e -> sendBtn.doClick());
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.add(manualField, BorderLayout.CENTER);
        row.add(sendBtn, BorderLayout.EAST);
        p.add(row);
        p.add(Box.createVerticalStrut(6));
        return p;
    }

    private JPanel buildOutputPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(titledBorder("📊  BOARD OUTPUTS  (received from board)"));
        p.setBackground(new Color(22, 22, 32));

        deviceLbl.setForeground(new Color(180, 220, 255));
        deviceLbl.setFont(new Font("Space Mono", Font.BOLD, 13));
        deviceLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(8));
        p.add(deviceLbl);

        // LED output
        ledLabel.setForeground(Color.YELLOW);
        ledLabel.setFont(new Font("Space Mono", Font.BOLD, 13));
        ledLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ledBar.setStringPainted(true);
        ledBar.setString("0%");
        ledBar.setForeground(Color.YELLOW);
        ledBar.setBackground(new Color(30, 30, 40));
        ledBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        ledBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(Box.createVerticalStrut(12));
        p.add(ledLabel);
        p.add(Box.createVerticalStrut(4));
        p.add(ledBar);

        // GPIO12 digital state indicator
        gpio12Lbl.setFont(new Font("Space Mono", Font.BOLD, 13));
        gpio12Lbl.setForeground(Color.LIGHT_GRAY);
        gpio12Lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(12));
        p.add(gpio12Lbl);

        // Incoming message list
        p.add(Box.createVerticalStrut(12));
        p.add(new JLabel("  Recent Messages:"));
        JList<String> msgList = new JList<>(messageModel);
        msgList.setFont(new Font("Space Mono", Font.PLAIN, 11));
        msgList.setBackground(new Color(15, 15, 25));
        msgList.setForeground(new Color(100, 200, 100));
        JScrollPane msgScroll = new JScrollPane(msgList);
        msgScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(msgScroll);

        p.add(Box.createVerticalStrut(6));
        return p;
    }

    // ─── Networking ──────────────────────────────────────────────────────────

    private void attemptConnect() {
        new Thread(() -> {
            try {
                setStatus("Connecting to " + host + ":" + port + "...", Color.ORANGE);
                socket = new Socket(host, port);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                setStatus("● CONNECTED  →  " + host + ":" + port, new Color(40, 200, 80));
                log("Connected to CPS workbench at " + host + ":" + port);
                // Start reading incoming telemetry
                new Thread(this::readLoop, "SimReader").start();
            } catch (IOException ex) {
                setStatus("● FAILED: " + ex.getMessage(), Color.RED);
                log("[ERROR] " + ex.getMessage());
            }
        }, "SimConnect").start();
    }

    private void readLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> handleIncoming(msg));
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                setStatus("● DISCONNECTED", Color.RED);
                log("[DISCONNECTED] " + e.getMessage());
                connected = false;
            });
        }
    }

    private void handleIncoming(String msg) {
        log("← " + msg);
        // Prepend to message list
        if (messageModel.size() > 50) messageModel.removeElementAt(messageModel.size() - 1);
        messageModel.add(0, msg);

        // Parse simple JSON fields manually (avoid extra dependencies)
        String device   = jsonStr(msg, "device");
        String gpio12   = jsonStr(msg, "gpio12_state");
        double gpio04v  = jsonDouble(msg, "gpio04_v", -1);
        double a0v      = jsonDouble(msg, "A0_v", -1);

        if (!device.isEmpty()) deviceLbl.setText("Device: " + device);

        if (!gpio12.isEmpty()) {
            boolean hi = gpio12.equalsIgnoreCase("HIGH");
            gpio12Lbl.setText("GPIO12: " + gpio12);
            gpio12Lbl.setForeground(hi ? new Color(80, 255, 80) : Color.LIGHT_GRAY);
        }

        // Infer LED brightness from voltage on GPIO04 or A0
        double v = (gpio04v >= 0) ? gpio04v : a0v;
        if (v >= 0) {
            int pct = (int) Math.min(100, Math.max(0, (v - 2.0) / 1.3 * 100.0));
            ledBar.setValue(pct);
            ledBar.setString(pct + "%");
            ledLabel.setText(pct > 0 ? "LED: ON  (" + pct + "%)" : "LED: OFF");
            ledLabel.setForeground(pct > 0 ? Color.YELLOW : Color.LIGHT_GRAY);
        }
    }

    private void sendCommand(String json) {
        if (out != null && connected) {
            out.println(json);
            log("→ " + json);
        } else {
            log("[WARN] Not connected. Command dropped.");
        }
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        setStatus("● DISCONNECTED", Color.RED);
        log("Disconnected.");
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private JPanel labeledSlider(String label, JSlider slider, java.util.function.IntConsumer onChange) {
        slider.setOpaque(false);
        slider.setForeground(Color.WHITE);
        JLabel lbl = new JLabel(label + "  [" + slider.getValue() + "]");
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Space Mono", Font.PLAIN, 12));
        slider.addChangeListener(e -> {
            lbl.setText(label + "  [" + slider.getValue() + "]");
            onChange.accept(slider.getValue());
        });
        JPanel panel = new JPanel(new BorderLayout(4, 2));
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        panel.add(lbl,    BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return panel;
    }

    private JButton makeButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        return b;
    }

    private Border titledBorder(String title) {
        return BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60, 80, 120)),
            title, TitledBorder.LEFT, TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 12), new Color(160, 200, 255));
    }

    // Naive JSON string extractor – avoids heavyweight JSON dependency
    private String jsonStr(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return "";
        i += search.length();
        int j = json.indexOf('"', i);
        return (j < 0) ? "" : json.substring(i, j);
    }

    private double jsonDouble(String json, String key, double def) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return def;
        i += search.length();
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '.' || json.charAt(j) == '-')) j++;
        try { return Double.parseDouble(json.substring(i, j)); } catch (NumberFormatException e) { return def; }
    }

    // ─── Inner: GaugePanel (arc-based analogue meter) ────────────────────────
    static class GaugePanel extends JPanel {
        private double value, min, max;
        private final String label, unit;
        private final Color color;

        GaugePanel(String label, String unit, double min, double max, Color color) {
            this.label = label; this.unit = unit;
            this.min = min; this.max = max;
            this.color = color; this.value = min;
            setBackground(new Color(20, 20, 30));
            setPreferredSize(new Dimension(180, 140));
        }

        void setValue(double v) { this.value = v; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h - 20, r = Math.min(cx, cy) - 12;

            // Background arc (dark)
            g2.setColor(new Color(40, 40, 55));
            g2.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 0, 180);

            // Value arc
            double pct = (value - min) / (max - min);
            int sweepAngle = (int) (pct * 180);
            g2.setColor(color);
            g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 180, sweepAngle);

            // Needle
            double angle = Math.PI - pct * Math.PI;
            int nx = cx + (int) (r * Math.cos(angle));
            int ny = cy - (int) (r * Math.sin(angle));
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2));
            g2.drawLine(cx, cy, nx, ny);
            g2.fillOval(cx - 4, cy - 4, 8, 8);

            // Labels
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.setColor(color);
            String valStr = String.format("%.1f %s", value, unit);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(valStr, cx - fm.stringWidth(valStr) / 2, cy - r / 2);
            g2.setColor(Color.LIGHT_GRAY);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g2.drawString(label, cx - fm.stringWidth(label) / 2, cy + 14);
        }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String host = (args.length > 0) ? args[0] : "localhost";
        int    port = (args.length > 1) ? Integer.parseInt(args[1]) : 9090;
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SimulatorApp(host, port));
    }
}

