import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

// ============================================================================
// LED BLINK APPLET — Interactive Realistic Physical LED & Telemetry Visualizer
// ============================================================================
// Focuses on the LED visualizer and telemetry monitor for ESP32 Simple Blink:
// - Realistic glowing 3D-styled physical 5mm LED with internal leadframe,
//   specular glass reflections, and dynamic radial bloom flares.
// - Real-time rolling square wave logic analyzer waveform.
// - Digital telemetry HUD: State, Voltage (3.3V/0V), Period, Frequency, Cycles.
// - Seamless dual-mode: Synchronizes with Port 9092 (PORT_ACTUATORS) physical bus or
//   Port 9090 (PORT_TELEMETRY) radio socket bridge, with automatic fallback to internal clock.
// - Space Mono typography across all UI elements and custom graphics.
// ============================================================================
public class LedBlinkApplet extends JFrame {

    public static final String FONT_FAMILY = "Space Mono";

    // ─── Theme Colors ────────────────────────────────────────────────────────
    private static final Color BG_DARK       = new Color(13, 17, 23);
    private static final Color CARD_BG       = new Color(22, 27, 34);
    private static final Color CARD_BORDER   = new Color(48, 54, 61);
    private static final Color TEXT_MAIN      = new Color(240, 246, 252);
    private static final Color TEXT_MUTED     = new Color(139, 148, 158);
    private static final Color ACCENT_BLUE   = new Color(88, 166, 255);

    // ─── LED Color Palettes ──────────────────────────────────────────────────
    public enum LedTheme {
        EMERALD_GREEN("Emerald Green", new Color(0, 230, 118), new Color(0, 160, 80), new Color(0, 50, 25)),
        NEON_CYAN("Neon Cyan",         new Color(0, 229, 255), new Color(0, 150, 200), new Color(0, 45, 60)),
        RUBY_RED("Ruby Red",           new Color(255, 61, 80),  new Color(180, 30, 45),  new Color(55, 12, 18)),
        AMBER_GOLD("Amber Gold",       new Color(255, 171, 0), new Color(190, 120, 0), new Color(60, 38, 0)),
        VIOLET_PURPLE("Electric Violet",new Color(179, 136, 255), new Color(125, 80, 210), new Color(42, 20, 75));

        public final String name;
        public final Color litCore;
        public final Color litMid;
        public final Color unlit;

        LedTheme(String name, Color litCore, Color litMid, Color unlit) {
            this.name = name;
            this.litCore = litCore;
            this.litMid = litMid;
            this.unlit = unlit;
        }

        @Override public String toString() { return name; }
    }

    private LedTheme currentTheme = LedTheme.EMERALD_GREEN;

    // ─── Networking (Port 1: IoT Radio Telemetry) ────────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Telemetry State Registers ───────────────────────────────────────────
    private volatile boolean ledState = false;          // true = HIGH (3.3V), false = LOW (0.0V)
    private volatile double  voltage = 0.0;
    private volatile long    toggleCount = 0;
    private volatile int     periodMs = 1000;           // default 1000ms = 1.0 Hz
    private volatile String  deviceName = "ESP32_BlinkNode";
    private volatile String  firmwareName = "ESP32_SimpleBlink_v1.0";
    private volatile String  ledPin = "GPIO_02";
    private volatile long    uptimeSec = 0;

    // ─── Standalone Simulation Clock ─────────────────────────────────────────
    private volatile boolean standaloneMode = true;
    private volatile boolean simPaused = false;
    private javax.swing.Timer standaloneTimer;

    // ─── Smooth Visual Animation Smoothing ───────────────────────────────────
    private float smoothGlow = 0.0f; // 0.0f (off) to 1.0f (fully bright)

    // ─── Waveform History for Scope ──────────────────────────────────────────
    private static final int WAVEFORM_HISTORY_SIZE = 240;
    private final List<Double> waveformHistory = new ArrayList<>();
    private final List<Long>   waveformTimestamps = new ArrayList<>();

    // ─── UI Components ───────────────────────────────────────────────────────
    private final PhysicalLedPanel ledCanvas = new PhysicalLedPanel();
    private final WaveformScopePanel scopePanel = new WaveformScopePanel();

    private final JLabel headerTitleLbl    = new JLabel("ESP32 HARDWARE BLINK VISUALIZER");
    private final JLabel headerStatusPill  = new JLabel("● STANDALONE CLOCK");
    private final JLabel headerDeviceLbl   = new JLabel("DEVICE: ESP32_BlinkNode [GPIO_02]");

    // Telemetry HUD Labels
    private final JLabel stateValueLbl    = new JLabel("LOW (0.00V)");
    private final JLabel freqValueLbl     = new JLabel("1.0 Hz (1000 ms)");
    private final JLabel cyclesValueLbl   = new JLabel("0 Toggles");
    private final JLabel uptimeValueLbl   = new JLabel("00:00:00");

    private JSlider speedSlider;
    private JComboBox<LedTheme> themeSelector;
    private JButton pauseResumeBtn;

    public LedBlinkApplet(String host, int port) {
        super("CPS ESP32 Visualizer — Interactive LED Telemetry Applet");
        this.host = host;
        this.port = port;

        buildUI();
        initHistory();
        initStandaloneClock();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1060, 720);
        setMinimumSize(new Dimension(920, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        // 60 FPS smooth rendering loop for glows and scope
        javax.swing.Timer renderTimer = new javax.swing.Timer(16, e -> {
            float targetGlow = ledState ? 1.0f : 0.0f;
            smoothGlow += (targetGlow - smoothGlow) * 0.28f;
            ledCanvas.repaint();
            scopePanel.repaint();
        });
        renderTimer.start();

        // Background network connector thread
        new Thread(this::networkLoop, "LED-Radio-Client").start();
    }

    public LedBlinkApplet() {
        this("localhost", CpsDomain.PORT_ACTUATORS);
    }

    private void initHistory() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < WAVEFORM_HISTORY_SIZE; i++) {
            waveformHistory.add(0.0);
            waveformTimestamps.add(now - (WAVEFORM_HISTORY_SIZE - i) * 50);
        }
    }

    private void initStandaloneClock() {
        // Half-period tick (toggle state every half period)
        int halfPeriod = Math.max(50, periodMs / 2);
        standaloneTimer = new javax.swing.Timer(halfPeriod, e -> {
            if (!connected && !simPaused) {
                stepSimulationClock();
            }
        });
        standaloneTimer.start();
    }

    private synchronized void stepSimulationClock() {
        ledState = !ledState;
        voltage = ledState ? 3.30 : 0.00;
        toggleCount++;
        uptimeSec = toggleCount * (periodMs / 2) / 1000;

        recordSample(voltage);
        updateTelemetryCards();
    }

    private synchronized void recordSample(double v) {
        if (waveformHistory.size() >= WAVEFORM_HISTORY_SIZE) {
            waveformHistory.remove(0);
            waveformTimestamps.remove(0);
        }
        waveformHistory.add(v);
        waveformTimestamps.add(System.currentTimeMillis());
    }

    // ─── Network Receiver Loop ───────────────────────────────────────────────
    private void networkLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            if (!connected) {
                try {
                    socket = new Socket(host, port);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    connected = true;
                    standaloneMode = false;
                    SwingUtilities.invokeLater(() -> {
                        headerStatusPill.setText("● CONNECTED [PORT " + port + "]");
                        headerStatusPill.setForeground(new Color(46, 204, 113));
                        headerStatusPill.setBackground(new Color(20, 60, 35));
                    });
                } catch (Exception ex) {
                    connected = false;
                    standaloneMode = true;
                    SwingUtilities.invokeLater(() -> {
                        headerStatusPill.setText("● STANDALONE CLOCK");
                        headerStatusPill.setForeground(new Color(255, 171, 0));
                        headerStatusPill.setBackground(new Color(60, 40, 10));
                    });
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    continue;
                }
            }

            try {
                String line;
                while ((line = in.readLine()) != null) {
                    parseTelemetryJson(line);
                }
            } catch (Exception ex) {
                // Connection dropped
                connected = false;
                standaloneMode = true;
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void parseTelemetryJson(String json) {
        try {
            // Expected JSON:
            // {"device":"ESP32_BlinkNode","firmware":"ESP32_SimpleBlink_v1.0","time":"11:30:15",
            //  "ledPin":"GPIO_02","ledState":"HIGH","voltage":3.30,"toggleCount":14,"periodMs":1000,"freqHz":1.00}
            String trimmed = json.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return;

            // Handle Actuator Bus (Port 9092) PIN_CHANGED events
            if (trimmed.contains("\"event\":\"PIN_CHANGED\"") || trimmed.contains("\"PIN_CHANGED\"")) {
                String term = extractJsonString(trimmed, "terminal");
                Double v = extractJsonDouble(trimmed, "voltage");
                String st = extractJsonString(trimmed, "state");
                if (term != null && (term.contains("LED") || term.contains("GPIO_02") || term.contains("GPIO_2") || term.contains("ANODE"))) {
                    SwingUtilities.invokeLater(() -> {
                        if (v != null) voltage = v;
                        if (st != null) {
                            ledState = st.equalsIgnoreCase("HIGH") || (v != null && v > 1.65);
                        } else if (v != null) {
                            ledState = (v > 1.65);
                        }
                        toggleCount++;
                        recordSample(voltage);
                        updateTelemetryCards();
                    });
                    return;
                }
            }

            String dev = extractJsonString(trimmed, "device");
            String fw = extractJsonString(trimmed, "firmware");
            String pin = extractJsonString(trimmed, "ledPin");
            String stateStr = extractJsonString(trimmed, "ledState");
            Double v = extractJsonDouble(trimmed, "voltage");
            Long count = extractJsonLong(trimmed, "toggleCount");
            Integer pMs = extractJsonInt(trimmed, "periodMs");
            Long up = extractJsonLong(trimmed, "uptimeSec");

            SwingUtilities.invokeLater(() -> {
                if (dev != null) deviceName = dev;
                if (fw != null) firmwareName = fw;
                if (pin != null) ledPin = pin;
                if (stateStr != null) {
                    ledState = stateStr.equalsIgnoreCase("HIGH") || stateStr.equalsIgnoreCase("ON");
                }
                if (v != null) voltage = v;
                if (count != null) toggleCount = count;
                if (pMs != null && pMs > 0) {
                    periodMs = pMs;
                    if (speedSlider != null && speedSlider.getValue() != (int)(1000.0 / periodMs * 10)) {
                        speedSlider.setValue((int)(1000.0 / periodMs * 10));
                    }
                }
                if (up != null) uptimeSec = up;

                recordSample(voltage);
                updateTelemetryCards();
            });
        } catch (Exception ignored) {}
    }

    private void updateTelemetryCards() {
        if (ledState) {
            stateValueLbl.setText(String.format(Locale.US, "HIGH (%.2fV)", voltage));
            stateValueLbl.setForeground(currentTheme.litCore);
        } else {
            stateValueLbl.setText(String.format(Locale.US, "LOW (%.2fV)", voltage));
            stateValueLbl.setForeground(TEXT_MUTED);
        }

        double freq = 1000.0 / Math.max(50, periodMs);
        freqValueLbl.setText(String.format(Locale.US, "%.1f Hz (%d ms)", freq, periodMs));

        long cycles = toggleCount / 2;
        cyclesValueLbl.setText(String.format(Locale.US, "%d Toggles (%d cycles)", toggleCount, cycles));

        long hrs = uptimeSec / 3600;
        long mins = (uptimeSec % 3600) / 60;
        long secs = uptimeSec % 60;
        uptimeValueLbl.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs));

        headerDeviceLbl.setText(String.format("DEVICE: %s [%s] • FW: %s", deviceName, ledPin, firmwareName));
    }

    // ─── UI Construction ─────────────────────────────────────────────────────
    private void buildUI() {
        setLayout(new BorderLayout(14, 14));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(14, 16, 14, 16));

        // ── TOP HEADER BAR ───────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(16, 0));
        topBar.setBackground(CARD_BG);
        topBar.setBorder(new CompoundBorder(
            new LineBorder(CARD_BORDER, 1, true),
            new EmptyBorder(12, 18, 12, 18)
        ));

        JPanel titlePanel = new JPanel(new GridLayout(2, 1, 0, 4));
        titlePanel.setOpaque(false);

        headerTitleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 17));
        headerTitleLbl.setForeground(TEXT_MAIN);
        titlePanel.add(headerTitleLbl);

        headerDeviceLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        headerDeviceLbl.setForeground(TEXT_MUTED);
        titlePanel.add(headerDeviceLbl);

        topBar.add(titlePanel, BorderLayout.WEST);

        headerStatusPill.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        headerStatusPill.setForeground(new Color(255, 171, 0));
        headerStatusPill.setBackground(new Color(60, 40, 10));
        headerStatusPill.setOpaque(true);
        headerStatusPill.setBorder(new EmptyBorder(6, 14, 6, 14));
        topBar.add(headerStatusPill, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // ── CENTER SPLIT: Left = 3D Glowing LED / Right = Scope & Telemetry ──
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setOpaque(false);
        splitPane.setBorder(null);
        splitPane.setDividerSize(8);
        splitPane.setResizeWeight(0.52);

        // Left Container: Physical LED Canvas
        JPanel leftContainer = new JPanel(new BorderLayout(0, 8));
        leftContainer.setBackground(CARD_BG);
        leftContainer.setBorder(new CompoundBorder(
            new LineBorder(CARD_BORDER, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JPanel leftHeader = new JPanel(new BorderLayout());
        leftHeader.setOpaque(false);
        JLabel leftTitle = new JLabel("PHYSICAL 5MM LED EMITTER DOME");
        leftTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        leftTitle.setForeground(TEXT_MUTED);
        leftHeader.add(leftTitle, BorderLayout.WEST);

        leftContainer.add(leftHeader, BorderLayout.NORTH);
        leftContainer.add(ledCanvas, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftContainer);

        // Right Container: Telemetry Cards + Logic Analyzer + Controls
        JPanel rightContainer = new JPanel(new BorderLayout(0, 12));
        rightContainer.setOpaque(false);

        // Telemetry Grid (2x2 Cards)
        JPanel hudGrid = new JPanel(new GridLayout(2, 2, 10, 10));
        hudGrid.setOpaque(false);

        hudGrid.add(createCard("OUTPUT STATE & VOLTAGE", stateValueLbl, new Color(46, 204, 113)));
        hudGrid.add(createCard("BLINK FREQUENCY / PERIOD", freqValueLbl, ACCENT_BLUE));
        hudGrid.add(createCard("CYCLE & TOGGLE COUNT", cyclesValueLbl, new Color(255, 171, 0)));
        hudGrid.add(createCard("SYSTEM RUNTIME", uptimeValueLbl, new Color(179, 136, 255)));

        rightContainer.add(hudGrid, BorderLayout.NORTH);

        // Center: Logic Waveform Analyzer
        JPanel scopeContainer = new JPanel(new BorderLayout(0, 6));
        scopeContainer.setBackground(CARD_BG);
        scopeContainer.setBorder(new CompoundBorder(
            new LineBorder(CARD_BORDER, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JPanel scopeHeader = new JPanel(new BorderLayout());
        scopeHeader.setOpaque(false);
        JLabel scopeTitle = new JLabel("REAL-TIME LOGIC ANALYZER SCOPE (GPIO_02)");
        scopeTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scopeTitle.setForeground(TEXT_MUTED);
        scopeHeader.add(scopeTitle, BorderLayout.WEST);

        JLabel railLabel = new JLabel("RAIL: 3.3V CMOS");
        railLabel.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
        railLabel.setForeground(TEXT_MUTED);
        scopeHeader.add(railLabel, BorderLayout.EAST);

        scopeContainer.add(scopeHeader, BorderLayout.NORTH);
        scopeContainer.add(scopePanel, BorderLayout.CENTER);

        rightContainer.add(scopeContainer, BorderLayout.CENTER);

        // Bottom: Interactive Control Bar
        JPanel controlBar = buildControlBar();
        rightContainer.add(controlBar, BorderLayout.SOUTH);

        splitPane.setRightComponent(rightContainer);
        add(splitPane, BorderLayout.CENTER);
    }

    private JPanel createCard(String title, JLabel valueLbl, Color accent) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(CARD_BORDER, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel tLbl = new JLabel(title);
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        tLbl.setForeground(TEXT_MUTED);
        card.add(tLbl, BorderLayout.NORTH);

        valueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        valueLbl.setForeground(accent);
        card.add(valueLbl, BorderLayout.CENTER);

        return card;
    }

    private JPanel buildControlBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 8));
        bar.setBackground(CARD_BG);
        bar.setBorder(new CompoundBorder(
            new LineBorder(CARD_BORDER, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        // Speed slider panel
        JPanel speedPanel = new JPanel(new BorderLayout(8, 0));
        speedPanel.setOpaque(false);
        JLabel speedLbl = new JLabel("SPEED (0.2Hz - 5.0Hz):");
        speedLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        speedLbl.setForeground(TEXT_MUTED);
        speedPanel.add(speedLbl, BorderLayout.WEST);

        speedSlider = new JSlider(2, 50, 10); // 10 = 1.0 Hz
        speedSlider.setOpaque(false);
        speedSlider.addChangeListener(e -> {
            int val = speedSlider.getValue();
            double f = val / 10.0;
            int newPeriod = (int) (1000.0 / f);
            periodMs = newPeriod;
            if (standaloneTimer != null) {
                standaloneTimer.setDelay(Math.max(50, newPeriod / 2));
            }
            updateTelemetryCards();
        });
        speedPanel.add(speedSlider, BorderLayout.CENTER);

        bar.add(speedPanel, BorderLayout.NORTH);

        // Action buttons & Color Selector
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.setOpaque(false);

        // Color Theme
        JLabel themeLbl = new JLabel("COLOR:");
        themeLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        themeLbl.setForeground(TEXT_MUTED);
        actionRow.add(themeLbl);

        themeSelector = new JComboBox<>(LedTheme.values());
        themeSelector.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        themeSelector.setBackground(new Color(30, 36, 46));
        themeSelector.setForeground(TEXT_MAIN);
        themeSelector.addActionListener(e -> {
            currentTheme = (LedTheme) themeSelector.getSelectedItem();
            updateTelemetryCards();
            ledCanvas.repaint();
            scopePanel.repaint();
        });
        actionRow.add(themeSelector);

        // Quick Preset Buttons
        actionRow.add(createSpeedPresetBtn("0.5Hz", 2000));
        actionRow.add(createSpeedPresetBtn("1.0Hz", 1000));
        actionRow.add(createSpeedPresetBtn("2.0Hz", 500));
        actionRow.add(createSpeedPresetBtn("5.0Hz", 200));

        // Manual Toggle Button
        JButton toggleBtn = new JButton("TOGGLE");
        styleButton(toggleBtn, ACCENT_BLUE);
        toggleBtn.addActionListener(e -> {
            ledState = !ledState;
            voltage = ledState ? 3.30 : 0.00;
            toggleCount++;
            recordSample(voltage);
            updateTelemetryCards();
        });
        actionRow.add(toggleBtn);

        // Pause/Resume Button
        pauseResumeBtn = new JButton("PAUSE");
        styleButton(pauseResumeBtn, new Color(255, 171, 0));
        pauseResumeBtn.addActionListener(e -> {
            simPaused = !simPaused;
            pauseResumeBtn.setText(simPaused ? "RESUME" : "PAUSE");
            pauseResumeBtn.setBackground(simPaused ? new Color(46, 204, 113) : new Color(255, 171, 0));
        });
        actionRow.add(pauseResumeBtn);

        bar.add(actionRow, BorderLayout.SOUTH);

        return bar;
    }

    private JButton createSpeedPresetBtn(String label, int ms) {
        JButton btn = new JButton(label);
        styleButton(btn, new Color(40, 48, 62));
        btn.setForeground(TEXT_MAIN);
        btn.addActionListener(e -> {
            periodMs = ms;
            speedSlider.setValue((int) (1000.0 / ms * 10));
            if (standaloneTimer != null) {
                standaloneTimer.setDelay(Math.max(50, ms / 2));
            }
            updateTelemetryCards();
        });
        return btn;
    }

    private void styleButton(JButton btn, Color bg) {
        btn.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(new CompoundBorder(
            new LineBorder(bg.brighter(), 1, true),
            new EmptyBorder(6, 10, 6, 10)
        ));
    }

    // =========================================================================
    // CUSTOM PANEL 1: PHOTOREALISTIC 3D GLOWING PHYSICAL LED
    // =========================================================================
    private class PhysicalLedPanel extends JPanel {
        public PhysicalLedPanel() {
            setBackground(new Color(10, 13, 18));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2;
            int cy = h / 2 - 20;

            // 1. Subtle Breadboard / Lab Canvas Background Grid
            g2.setColor(new Color(20, 24, 32));
            for (int x = 20; x < w; x += 25) g2.drawLine(x, 0, x, h);
            for (int y = 20; y < h; y += 25) g2.drawLine(0, y, w, y);

            // 2. Dynamic Radial Bloom Flares when illuminated
            float intensity = smoothGlow; // 0.0 to 1.0
            if (intensity > 0.02f) {
                // Outermost diffuse ambient glow
                int flareRadius = (int) (160 * intensity);
                RadialGradientPaint ambientFlare = new RadialGradientPaint(
                    new Point2D.Float(cx, cy), Math.max(20, flareRadius),
                    new float[]{0.0f, 0.45f, 1.0f},
                    new Color[]{
                        blendColorAlpha(currentTheme.litCore, 0.40f * intensity),
                        blendColorAlpha(currentTheme.litMid, 0.15f * intensity),
                        new Color(0, 0, 0, 0)
                    }
                );
                g2.setPaint(ambientFlare);
                g2.fillOval(cx - flareRadius, cy - flareRadius, flareRadius * 2, flareRadius * 2);

                // Mid-layer concentrated aura
                int midRadius = (int) (90 * intensity);
                RadialGradientPaint midAura = new RadialGradientPaint(
                    new Point2D.Float(cx, cy - 10), Math.max(10, midRadius),
                    new float[]{0.0f, 0.5f, 1.0f},
                    new Color[]{
                        blendColorAlpha(Color.WHITE, 0.70f * intensity),
                        blendColorAlpha(currentTheme.litCore, 0.50f * intensity),
                        new Color(0, 0, 0, 0)
                    }
                );
                g2.setPaint(midAura);
                g2.fillOval(cx - midRadius, cy - 10 - midRadius, midRadius * 2, midRadius * 2);
            }

            // 3. Realistic Nickel-Plated Silver Leads extending down
            g2.setColor(new Color(170, 180, 195));
            g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Anode lead (longer, left)
            g2.drawLine(cx - 20, cy + 90, cx - 20, cy + 160);
            // Cathode lead (shorter / flat side, right)
            g2.drawLine(cx + 20, cy + 90, cx + 20, cy + 145);

            // Solder pad terminals at bottom of leads
            drawLeadTerminal(g2, cx - 20, cy + 160, "+ ANODE", new Color(46, 204, 113));
            drawLeadTerminal(g2, cx + 20, cy + 145, "- CATHODE", new Color(255, 61, 80));

            // 4. LED Flange / Base Rim (Cathode flat notch on right)
            int flangeW = 100, flangeH = 26;
            int fx = cx - flangeW / 2, fy = cy + 74;

            Color baseRimColor = blendColors(currentTheme.unlit, currentTheme.litMid, intensity * 0.7f);
            g2.setColor(baseRimColor);
            g2.fillOval(fx, fy, flangeW, flangeH);
            g2.setColor(new Color(30, 36, 48));
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawOval(fx, fy, flangeW, flangeH);

            // 5. LED Cylindrical Epoxy Body (80px wide x 90px tall)
            int bodyW = 80, bodyH = 80;
            int bx = cx - bodyW / 2, by = cy;

            Color epoxyBodyColor = blendColors(currentTheme.unlit, currentTheme.litCore, intensity * 0.85f);
            g2.setColor(epoxyBodyColor);
            g2.fillRoundRect(bx, by, bodyW, bodyH, 12, 12);

            // 6. LED Hemispherical Top Lens Dome
            int domeDiameter = bodyW;
            g2.fillArc(bx, cy - domeDiameter / 2, domeDiameter, domeDiameter, 0, 180);

            // 7. Internal Leadframe Visible Through Translucent Glass
            // Anode Post (left)
            g2.setColor(new Color(220, 225, 235, 180));
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawLine(cx - 16, cy + 70, cx - 16, cy + 20);

            // Anode Fine Gold Bond Wire arched to semiconductor die
            g2.setColor(new Color(255, 215, 0, 220));
            g2.setStroke(new BasicStroke(1.5f));
            QuadCurve2D bondWire = new QuadCurve2D.Float(cx - 16, cy + 20, cx - 8, cy - 2, cx + 6, cy + 10);
            g2.draw(bondWire);

            // Cathode Anvil (right, wide flat reflective reflector cup)
            g2.setColor(new Color(210, 215, 230, 220));
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawLine(cx + 16, cy + 70, cx + 16, cy + 26);
            // Triangular Reflector Cup
            Polygon cup = new Polygon();
            cup.addPoint(cx + 16, cy + 26);
            cup.addPoint(cx + 24, cy + 12);
            cup.addPoint(cx + 4, cy + 12);
            cup.addPoint(cx + 10, cy + 26);
            g2.fillPolygon(cup);

            // Semiconductor Emitter Die inside cup
            int dieX = cx + 11, dieY = cy + 12;
            if (intensity > 0.05f) {
                // Burning bright hotspot when active
                g2.setColor(Color.WHITE);
                g2.fillOval(dieX - 3, dieY - 3, 7, 7);
                g2.setColor(currentTheme.litCore);
                g2.drawOval(dieX - 6, dieY - 6, 13, 13);
            } else {
                g2.setColor(new Color(60, 68, 80));
                g2.fillRect(dieX - 2, dieY - 2, 5, 5);
            }

            // 8. 3D Cylindrical Shading & Glass Highlights
            // Left specular reflection strip along dome and cylinder
            GradientPaint glassSheen = new GradientPaint(
                bx + 8, cy - 30, new Color(255, 255, 255, (int)(140 + 80 * intensity)),
                bx + 24, cy + 60, new Color(255, 255, 255, (int)(20 + 30 * intensity))
            );
            g2.setPaint(glassSheen);
            g2.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Curved arc on dome
            g2.drawArc(bx + 10, cy - 34, 30, 40, 100, 75);
            // Vertical strip on body
            g2.drawLine(bx + 12, cy + 4, bx + 12, cy + 65);

            // Glass boundary outline
            g2.setColor(new Color(255, 255, 255, (int)(60 + 80 * intensity)));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(bx, by, bodyW, bodyH, 12, 12);
            g2.drawArc(bx, cy - domeDiameter / 2, domeDiameter, domeDiameter, 0, 180);

            // 9. Status Banner at Bottom of Canvas
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
            String stateStr = ledState ? "● ACTIVE HIGH [3.30V]" : "○ ACTIVE LOW [0.00V]";
            Color stateColor = ledState ? currentTheme.litCore : TEXT_MUTED;
            g2.setColor(stateColor);
            int sw = g2.getFontMetrics().stringWidth(stateStr);
            g2.drawString(stateStr, cx - sw / 2, h - 18);

            g2.dispose();
        }

        private void drawLeadTerminal(Graphics2D g2, int x, int y, String label, Color c) {
            g2.setColor(new Color(212, 175, 55)); // Gold pad
            g2.fillOval(x - 6, y - 6, 12, 12);
            g2.setColor(new Color(15, 18, 24));
            g2.fillOval(x - 3, y - 3, 6, 6);

            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
            g2.setColor(c);
            int tw = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, x - tw / 2, y + 16);
        }
    }

    // =========================================================================
    // CUSTOM PANEL 2: REAL-TIME ROLLING SQUARE WAVE LOGIC ANALYZER SCOPE
    // =========================================================================
    private class WaveformScopePanel extends JPanel {
        public WaveformScopePanel() {
            setBackground(new Color(10, 13, 18));
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int topMargin = 25, bottomMargin = 30;
            int leftMargin = 55, rightMargin = 20;

            int graphW = w - leftMargin - rightMargin;
            int graphH = h - topMargin - bottomMargin;
            int yLow = topMargin + graphH - 10;
            int yHigh = topMargin + 15;

            // 1. Grid & Voltage Level Markings
            g2.setColor(new Color(26, 32, 44));
            g2.setStroke(new BasicStroke(1.0f));
            for (int x = leftMargin; x <= leftMargin + graphW; x += 40) {
                g2.drawLine(x, topMargin, x, topMargin + graphH);
            }

            // High Rail (3.3V)
            g2.setColor(new Color(38, 48, 66));
            g2.drawLine(leftMargin, yHigh, leftMargin + graphW, yHigh);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
            g2.setColor(currentTheme.litCore);
            g2.drawString("+3.3V", 10, yHigh + 4);

            // Mid Reference (1.65V)
            int yMid = (yHigh + yLow) / 2;
            float[] dash = {4.0f, 4.0f};
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
            g2.setColor(new Color(30, 38, 52));
            g2.drawLine(leftMargin, yMid, leftMargin + graphW, yMid);
            g2.drawString("1.65V", 10, yMid + 4);

            // Low Rail (0.0V / GND)
            g2.setStroke(new BasicStroke(1.0f));
            g2.setColor(new Color(38, 48, 66));
            g2.drawLine(leftMargin, yLow, leftMargin + graphW, yLow);
            g2.setColor(TEXT_MUTED);
            g2.drawString(" 0.0V", 10, yLow + 4);

            // 2. Plot Rolling Square Wave
            synchronized (waveformHistory) {
                int count = waveformHistory.size();
                if (count > 1) {
                    Path2D wavePath = new Path2D.Float();
                    Path2D fillPath = new Path2D.Float();

                    double dx = (double) graphW / (WAVEFORM_HISTORY_SIZE - 1);
                    double curX = leftMargin;
                    double curY = (waveformHistory.get(0) > 1.5) ? yHigh : yLow;

                    wavePath.moveTo(curX, curY);
                    fillPath.moveTo(curX, yLow);
                    fillPath.lineTo(curX, curY);

                    for (int i = 1; i < count; i++) {
                        double nextX = leftMargin + i * dx;
                        double nextY = (waveformHistory.get(i) > 1.5) ? yHigh : yLow;

                        if (nextY != curY) {
                            // Crisp square transition edge
                            wavePath.lineTo(nextX, curY);
                            wavePath.lineTo(nextX, nextY);

                            fillPath.lineTo(nextX, curY);
                            fillPath.lineTo(nextX, nextY);
                        } else {
                            wavePath.lineTo(nextX, nextY);
                            fillPath.lineTo(nextX, nextY);
                        }
                        curX = nextX;
                        curY = nextY;
                    }

                    fillPath.lineTo(curX, yLow);
                    fillPath.closePath();

                    // Subtle semi-transparent gradient fill below the active waveform
                    GradientPaint fillGrad = new GradientPaint(
                        0, yHigh, blendColorAlpha(currentTheme.litCore, 0.20f),
                        0, yLow, new Color(0, 0, 0, 0)
                    );
                    g2.setPaint(fillGrad);
                    g2.fill(fillPath);

                    // Bright neon trace stroke
                    g2.setColor(currentTheme.litCore);
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(wavePath);

                    // Live active head pulse dot
                    g2.setColor(Color.WHITE);
                    g2.fillOval((int) curX - 4, (int) curY - 4, 8, 8);
                    g2.setColor(currentTheme.litCore);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval((int) curX - 6, (int) curY - 6, 12, 12);
                }
            }

            // Time axis labels at bottom
            g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 9));
            g2.setColor(TEXT_MUTED);
            g2.drawString("-12s", leftMargin, h - 10);
            g2.drawString("-6s", leftMargin + graphW / 2 - 10, h - 10);
            g2.drawString("NOW (0s)", leftMargin + graphW - 40, h - 10);

            g2.dispose();
        }
    }

    // ─── Color Utility Helpers ───────────────────────────────────────────────
    private static Color blendColors(Color c1, Color c2, float ratio) {
        float r = Math.min(1.0f, Math.max(0.0f, ratio));
        int red = (int) (c1.getRed() * (1 - r) + c2.getRed() * r);
        int green = (int) (c1.getGreen() * (1 - r) + c2.getGreen() * r);
        int blue = (int) (c1.getBlue() * (1 - r) + c2.getBlue() * r);
        return new Color(red, green, blue);
    }

    private static Color blendColorAlpha(Color c, float alpha) {
        int a = (int) (Math.min(1.0f, Math.max(0.0f, alpha)) * 255);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // ─── Quick JSON Extractors ───────────────────────────────────────────────
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return (end != -1) ? json.substring(start, end) : null;
    }

    private static Double extractJsonDouble(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = findNumberEnd(json, start);
        try { return Double.parseDouble(json.substring(start, end).trim()); } catch (Exception e) { return null; }
    }

    private static Long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = findNumberEnd(json, start);
        try { return Long.parseLong(json.substring(start, end).trim()); } catch (Exception e) { return null; }
    }

    private static Integer extractJsonInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = findNumberEnd(json, start);
        try { return Integer.parseInt(json.substring(start, end).trim()); } catch (Exception e) { return null; }
    }

    private static int findNumberEnd(String json, int start) {
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ' ' || c == '\n' || c == '\r') break;
            i++;
        }
        return i;
    }

    // ─── Entry Point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String h = "localhost";
        int p = -1;
        if (args.length > 0) {
            try { p = Integer.parseInt(args[0]); } catch (Exception ignored) { h = args[0]; }
        }
        if (args.length > 1) {
            try { p = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        if (p <= 0 || (args.length > 0 && args[0].equalsIgnoreCase("--prompt"))) {
            String pStr = JOptionPane.showInputDialog(null,
                "Enter Actuator Bus Port noted down from the backend terminal:\n(Leave blank to exit)",
                "LED Blink Applet — Port Input",
                JOptionPane.QUESTION_MESSAGE);
            if (pStr == null || pStr.trim().isEmpty()) return;
            try { p = Integer.parseInt(pStr.trim()); } catch (Exception e) { return; }
        }
        final String finalHost = h;
        final int finalPort = p;
        SwingUtilities.invokeLater(() -> new LedBlinkApplet(finalHost, finalPort));
    }
}
