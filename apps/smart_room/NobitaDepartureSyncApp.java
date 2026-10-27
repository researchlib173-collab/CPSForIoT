import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.List;

/**
 * NobitaDepartureSyncApp
 * ==============================================================================
 * Cyber-Physical Embedded Video Player & Multi-Scenario Simulation Synchronizer.
 *
 * Integrated Scenarios (All 4 Video Feeds):
 * 1. 🚪 Scenario 1: Nobita Departure via Bamboo Copter (Timeline 1.mov)
 *    - 6.00s | 20 FPS (120 frames) | Window exit cut at t = 3.00s
 *    - t < 3.0s: PIR = 3.3V, mmWave = 2.80V (OCCUPIED)
 *    - t ≥ 3.0s: PIR = 0.0V, mmWave = 0.05V (DEPARTED / VACANT -> Hysteresis)
 *
 * 2. 🛏️ Scenario 2: Nobita Sleeping & Dimming (Nobita sleeping.mov)
 *    - 5.00s | 20 FPS (100 frames) | Sleep dimming cut at t = 4.00s
 *    - t < 4.0s: PIR = 3.3V, mmWave = 2.80V (OCCUPIED / AWAKE)
 *    - t ≥ 4.0s: PIR = 0.0V, mmWave = 1.95V (SLEEP MODE / Vital micro-motion)
 *
 * 3. 🚶 Scenario 3: Nobita Entering Room (Nobita enters the room.mov)
 *    - 11.00s | 15 FPS (165 frames) | Entry threshold cut at t = 6.60s
 *    - t < 6.6s: PIR = 0.0V, mmWave = 0.05V (VACANT & DARK)
 *    - t ≥ 6.6s: PIR = 3.3V, mmWave = 2.85V (NOBITA ENTERS -> LIGHTS ON)
 *
 * 4. 🚨 Scenario 4: PIR Motion Sensor Activated (PIR motion sensor activated.mp4)
 *    - 14.00s | 15 FPS (210 frames) | Motion active from t = 2.30s to 10.30s
 *    - t < 2.3s: PIR = 0.0V, mmWave = 0.05V (STANDBY)
 *    - 2.3s ≤ t < 10.3s: PIR = 3.3V, mmWave = 2.90V (MOTION DETECTED / ACTIVE)
 *    - t ≥ 10.3s: PIR = 0.0V, mmWave = 0.05V (CLEAR / IDLE)
 *
 * Styling:
 * - High-contrast palette: Obsidian background (#06090E), vivid slate cards (#0F172A),
 *   ultra-bright white text (#FFFFFF), electric neon cyan (#00E5FF), neon green (#00FF80),
 *   vivid gold (#FFD700), coral red (#FF3366), and neon purple (#D946EF).
 * - Full Segoe UI Emoji font typography across all UI components, buttons, and HUD badges.
 * ==============================================================================
 */
public class NobitaDepartureSyncApp extends JFrame {

    // High Contrast Typography & Palette
    private static final String FONT_FAMILY   = "Segoe UI Emoji";
    private static final Color BG_DARK        = new Color(6, 9, 14);       // Pitch Obsidian
    private static final Color CARD_BG        = new Color(15, 23, 42);     // Deep Slate Card
    private static final Color BORDER_COLOR   = new Color(51, 65, 85);     // High-Contrast Slate Border
    private static final Color BORDER_BRIGHT  = new Color(96, 165, 250);    // Vibrant Border Accent
    private static final Color ACCENT_CYAN    = new Color(0, 229, 255);    // Electric Cyan
    private static final Color ACCENT_GREEN   = new Color(0, 255, 128);    // Neon Green
    private static final Color ACCENT_GOLD    = new Color(255, 215, 0);    // Vivid Gold / Amber
    private static final Color ACCENT_PURPLE  = new Color(217, 70, 239);   // Neon Purple
    private static final Color ACCENT_RED     = new Color(255, 51, 102);   // Coral Red
    private static final Color TEXT_WHITE     = new Color(255, 255, 255);  // Pure Crisp White
    private static final Color TEXT_MUTED     = new Color(226, 232, 240);  // High Contrast Light Slate

    // Network & Target Configuration (Dynamic Workspace Ports)
    private String host;
    private int sensorPort;     // Sensors Bus (dynamic)
    private int telemetryPort;  // Telemetry Stream (dynamic)

    // Socket connections
    private Socket sensorSocket;
    private PrintWriter sensorOut;
    private BufferedReader sensorIn;
    private Thread sensorThread;
    private volatile boolean sensorConnected = false;

    private Socket telemetrySocket;
    private BufferedReader telemetryIn;
    private Thread telemetryThread;
    private volatile boolean telemetryConnected = false;

    // ─── Scenario Definitions ────────────────────────────────────────────────
    public enum Scenario {
        DEPARTURE(
            "🚪 1. Nobita Departure (Timeline 1.mov — Exit at 3.0s)",
            "Timeline 1.mov",
            "assets/nobita_frames",
            6.00,
            20,
            3.00,
            100.0, // No second trigger
            "● 🚪 NOBITA IN ROOM (t < 3.0s)",
            "★ 🚁 BAMBOO-COPTER EXIT (t ≥ 3.0s)",
            "",
            "🚁 TRIGGER 3.0s EXIT"
        ),
        SLEEPING(
            "🛏️ 2. Nobita Sleeping & Dimming (Nobita sleeping.mov — Dims at 4.0s)",
            "Nobita sleeping.mov",
            "assets/sleeping_frames",
            5.00,
            20,
            4.00,
            100.0,
            "● 🛏️ NOBITA AWAKE / IN BED (t < 4.0s)",
            "🌙 💤 SLEEP MODE — LIGHTS DIMMED (t ≥ 4.0s)",
            "",
            "🌙 TRIGGER 4.0s SLEEP"
        ),
        ENTERING(
            "🚶 3. Nobita Entering Room (Nobita enters the room.mov — Entry at 6.6s)",
            "Nobita enters the room.mov",
            "assets/entering_frames",
            11.00,
            15,
            6.60,
            100.0,
            "● 🌑 ROOM VACANT / DARK (t < 6.6s)",
            "💡 🚶 NOBITA ENTERS — LIGHTS ON (t ≥ 6.6s)",
            "",
            "🚶 TRIGGER 6.6s ENTRY"
        ),
        PIR_ACTIVATED(
            "🚨 4. PIR Sensor Activated (PIR motion sensor activated.mp4 — Motion at 2.3s)",
            "PIR motion sensor activated.mp4",
            "assets/pir_frames",
            14.00,
            15,
            2.30,
            10.30,
            "● ⏳ PIR STANDBY (t < 2.3s)",
            "🚨 ⚡ PIR MOTION DETECTED (2.3s ≤ t < 10.3s)",
            "💤 IDLE / CLEAR (t ≥ 10.3s)",
            "🚨 TRIGGER MOTION ACTIVE"
        );

        public final String displayName;
        public final String videoFileName;
        public final String framesDir;
        public final double durationSec;
        public final int fps;
        public final double triggerSec;
        public final double trigger2Sec;
        public final String hudBeforeTrigger;
        public final String hudAfterTrigger;
        public final String hudAfterTrigger2;
        public final String triggerButtonLabel;

        Scenario(String displayName, String videoFileName, String framesDir, double durationSec, int fps,
                 double triggerSec, double trigger2Sec,
                 String hudBeforeTrigger, String hudAfterTrigger, String hudAfterTrigger2, String triggerButtonLabel) {
            this.displayName = displayName;
            this.videoFileName = videoFileName;
            this.framesDir = framesDir;
            this.durationSec = durationSec;
            this.fps = fps;
            this.triggerSec = triggerSec;
            this.trigger2Sec = trigger2Sec;
            this.hudBeforeTrigger = hudBeforeTrigger;
            this.hudAfterTrigger = hudAfterTrigger;
            this.hudAfterTrigger2 = hudAfterTrigger2;
            this.triggerButtonLabel = triggerButtonLabel;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private Scenario currentScenario = Scenario.ENTERING; // Default to the newly added entering scenario

    // Cache of loaded frames per scenario
    private final Map<Scenario, List<BufferedImage>> scenarioFramesCache = new EnumMap<>(Scenario.class);
    private final List<BufferedImage> activeFrames = new ArrayList<>();

    // Timeline & Video Playback State
    private volatile boolean isPlaying = false;
    private volatile double currentTimelineSec = 0.0;
    private boolean eventDispatched = false;
    private boolean event2Dispatched = false;
    private javax.swing.Timer playbackTimer;

    // Video Viewport Component
    private VideoViewportPanel videoViewport;

    // UI Elements
    private JComboBox<Scenario> scenarioSelector;
    private JLabel subLbl;
    private JLabel statusSensorPill;
    private JLabel statusTelemPill;
    private JLabel statusVideoPill;
    private JLabel timeDisplayLbl;
    private JSlider timelineSlider;
    private JButton playBtn;
    private JButton restartBtn;
    private JButton sendOccupiedBtn;
    private JButton sendTriggerBtn;

    // Telemetry Cards
    private JLabel roomStateCard;
    private JLabel pirCard;
    private JLabel radarCard;
    private JLabel hystCard;

    // Event Log
    private JTextArea logArea;

    public NobitaDepartureSyncApp(String host, int sensorPort, int telemetryPort) {
        super("🎬 Nobita Smart Room CPS Video Synchronizer — Multi-Scenario Suite");
        this.host = host;
        this.sensorPort = sensorPort;
        this.telemetryPort = telemetryPort;

        setupUI();
        initPlaybackTimer();
        loadAllScenariosAsync();
        connectSockets();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1320, 840);
        setMinimumSize(new Dimension(1120, 720));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);
    }

    public NobitaDepartureSyncApp() {
        this("localhost", CpsDomain.PORT_SENSORS, CpsDomain.PORT_TELEMETRY);
    }

    private void setupUI() {
        setLayout(new BorderLayout(14, 14));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(16, 18, 16, 18));

        // ── TOP: Header Banner ────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(CARD_BG);
        header.setBorder(new CompoundBorder(
            new LineBorder(BORDER_BRIGHT, 2, true),
            new EmptyBorder(14, 18, 14, 18)
        ));

        JPanel titleGrp = new JPanel(new GridLayout(2, 1, 0, 4));
        titleGrp.setOpaque(false);
        JLabel titleLbl = new JLabel("🎬 NOBITA SMART ROOM — CPS MULTI-SCENARIO SYNCHRONIZER");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 16));
        titleLbl.setForeground(ACCENT_CYAN);

        subLbl = new JLabel(formatSubLabel());
        subLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        subLbl.setForeground(TEXT_WHITE);
        titleGrp.add(titleLbl);
        titleGrp.add(subLbl);

        JPanel pillsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        pillsPanel.setOpaque(false);

        JButton btnSetPorts = createStyledButton("🔌 ENTER PORTS", CARD_BG, ACCENT_CYAN);
        btnSetPorts.setToolTipText("Enter dynamic port numbers hosted by workspace");
        btnSetPorts.addActionListener(e -> promptAndSetPorts());

        statusVideoPill  = createPill("🎬 VIDEO: LOADING...", ACCENT_GOLD);
        statusSensorPill = createPill("⚡ SENSORS: " + (sensorPort > 0 ? sensorPort : "SIM"), ACCENT_GOLD);
        statusTelemPill  = createPill("📡 TELEMETRY: " + (telemetryPort > 0 ? telemetryPort : "SIM"), ACCENT_GOLD);

        statusSensorPill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusSensorPill.setToolTipText("Click to change Sensors Port");
        statusSensorPill.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { promptAndSetPorts(); }
        });

        statusTelemPill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusTelemPill.setToolTipText("Click to change Telemetry Port");
        statusTelemPill.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { promptAndSetPorts(); }
        });

        pillsPanel.add(btnSetPorts);
        pillsPanel.add(statusVideoPill);
        pillsPanel.add(statusSensorPill);
        pillsPanel.add(statusTelemPill);

        header.add(titleGrp, BorderLayout.WEST);
        header.add(pillsPanel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ── MAIN SPLIT: Left = Video Player | Right = CPS Telemetry & Controls ─
        JPanel mainContent = new JPanel(new BorderLayout(14, 14));
        mainContent.setOpaque(false);

        // LEFT SIDE: Scenario Chooser + Video Viewport + Transport Controls
        JPanel leftVideoCard = new JPanel(new BorderLayout(12, 12));
        leftVideoCard.setBackground(CARD_BG);
        leftVideoCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(14, 16, 14, 16)
        ));
        leftVideoCard.setPreferredSize(new Dimension(740, 0));

        // Scenario Selector Row
        JPanel scenarioRow = new JPanel(new BorderLayout(10, 0));
        scenarioRow.setOpaque(false);
        JLabel scLbl = new JLabel("🎯 ACTIVE SCENARIO:");
        scLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        scLbl.setForeground(ACCENT_GOLD);

        scenarioSelector = new JComboBox<>(Scenario.values());
        scenarioSelector.setSelectedItem(currentScenario);
        scenarioSelector.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        scenarioSelector.setBackground(new Color(0,0,0));
        scenarioSelector.setForeground(TEXT_WHITE);
        scenarioSelector.setFocusable(false);
        scenarioSelector.setBorder(new LineBorder(BORDER_BRIGHT, 1, true));
        scenarioSelector.addActionListener(e -> {
            Scenario selected = (Scenario) scenarioSelector.getSelectedItem();
            if (selected != null && selected != currentScenario) {
                switchScenario(selected);
            }
        });

        scenarioRow.add(scLbl, BorderLayout.WEST);
        scenarioRow.add(scenarioSelector, BorderLayout.CENTER);
        leftVideoCard.add(scenarioRow, BorderLayout.NORTH);

        // Video Viewport Canvas
        videoViewport = new VideoViewportPanel();
        leftVideoCard.add(videoViewport, BorderLayout.CENTER);

        // Under-Video Scrubber & Time Bar
        JPanel videoControlBar = new JPanel(new BorderLayout(10, 8));
        videoControlBar.setOpaque(false);

        timelineSlider = new JSlider(0, (int) (currentScenario.durationSec * 1000), 0);
        timelineSlider.setBackground(CARD_BG);
        timelineSlider.setForeground(ACCENT_CYAN);
        timelineSlider.setFocusable(false);
        timelineSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                seekFromSlider();
            }
        });
        timelineSlider.addChangeListener(e -> {
            if (timelineSlider.getValueIsAdjusting()) {
                seekFromSlider();
            }
        });

        JPanel transportBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        transportBtnRow.setOpaque(false);

        playBtn = createStyledButton("▶ PLAY VIDEO & SYNC", ACCENT_GREEN, Color.BLACK);
        playBtn.addActionListener(e -> togglePlay());

        restartBtn = createStyledButton("↺ RESTART", new Color(71, 85, 105), TEXT_WHITE);
        restartBtn.addActionListener(e -> restartTimeline());

        timeDisplayLbl = new JLabel(formatTimeString(0.0, currentScenario.durationSec));
        timeDisplayLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 16));
        timeDisplayLbl.setForeground(ACCENT_GOLD);

        transportBtnRow.add(playBtn);
        transportBtnRow.add(restartBtn);
        transportBtnRow.add(Box.createHorizontalStrut(14));
        transportBtnRow.add(timeDisplayLbl);

        videoControlBar.add(timelineSlider, BorderLayout.NORTH);
        videoControlBar.add(transportBtnRow, BorderLayout.SOUTH);
        leftVideoCard.add(videoControlBar, BorderLayout.SOUTH);

        mainContent.add(leftVideoCard, BorderLayout.CENTER);

        // RIGHT SIDE: CPS Telemetry HUD & Live Event Log
        JPanel rightPanel = new JPanel(new BorderLayout(14, 14));
        rightPanel.setOpaque(false);
        rightPanel.setPreferredSize(new Dimension(520, 0));

        // Upper: Cyber-Physical Status Metrics
        JPanel telemetryCard = new JPanel(new BorderLayout(12, 12));
        telemetryCard.setBackground(CARD_BG);
        telemetryCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(14, 16, 14, 16)
        ));

        JLabel telemTitle = new JLabel("⚡ CPS REAL-TIME TELEMETRY HUD (PORT 9090)");
        telemTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        telemTitle.setForeground(ACCENT_CYAN);
        telemetryCard.add(telemTitle, BorderLayout.NORTH);

        JPanel metricsGrid = new JPanel(new GridLayout(2, 2, 12, 12));
        metricsGrid.setOpaque(false);
        roomStateCard = createMetricCard("ROOM STATE", "VACANT", ACCENT_GOLD, metricsGrid);
        pirCard       = createMetricCard("PIR SENSOR (GPIO_12)", "0.0 V [LOW]", TEXT_WHITE, metricsGrid);
        radarCard     = createMetricCard("RADAR CONF (GPIO_27)", "0.0% (0.00V)", TEXT_WHITE, metricsGrid);
        hystCard      = createMetricCard("HYSTERESIS REMAIN", "0 s", ACCENT_CYAN, metricsGrid);
        telemetryCard.add(metricsGrid, BorderLayout.CENTER);

        // Manual Injection Buttons
        JPanel manualBtnRow = new JPanel(new GridLayout(1, 2, 10, 0));
        manualBtnRow.setOpaque(false);
        sendOccupiedBtn = createStyledButton("💡 FORCE OCCUPIED", new Color(14, 165, 233), TEXT_WHITE);
        sendOccupiedBtn.addActionListener(e -> injectOccupiedStimulus("Manual User Override"));

        sendTriggerBtn = createStyledButton(currentScenario.triggerButtonLabel, ACCENT_PURPLE, TEXT_WHITE);
        sendTriggerBtn.addActionListener(e -> triggerActiveScenarioEvent("Manual User Override"));

        manualBtnRow.add(sendOccupiedBtn);
        manualBtnRow.add(sendTriggerBtn);
        telemetryCard.add(manualBtnRow, BorderLayout.SOUTH);

        rightPanel.add(telemetryCard, BorderLayout.NORTH);

        // Lower: Real-time Event Log
        JPanel logCard = new JPanel(new BorderLayout(10, 10));
        logCard.setBackground(CARD_BG);
        logCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel logHeader = new JLabel("📝 SENSOR BUS & TELEMETRY STREAM LOG");
        logHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        logHeader.setForeground(TEXT_WHITE);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(8, 12, 20));
        logArea.setForeground(new Color(240, 253, 244)); // Ultra crisp mint white
        logArea.setFont(new Font("Consolas", Font.BOLD, 12));
        logArea.setCaretColor(Color.WHITE);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new LineBorder(BORDER_COLOR, 1));

        logCard.add(logHeader, BorderLayout.NORTH);
        logCard.add(scroll, BorderLayout.CENTER);
        rightPanel.add(logCard, BorderLayout.CENTER);

        mainContent.add(rightPanel, BorderLayout.EAST);
        add(mainContent, BorderLayout.CENTER);
    }

    private String formatSubLabel() {
        return String.format(Locale.US,
            "Video: %s (Trigger at t = %.1fs / %.1fs) | Sensors Bus: %d | Telemetry: %d",
            currentScenario.videoFileName, currentScenario.triggerSec, currentScenario.durationSec, sensorPort, telemetryPort);
    }

    private String formatTimeString(double current, double total) {
        int curSec = (int) current;
        int curCs  = (int) ((current - curSec) * 100);
        int totSec = (int) total;
        int totCs  = (int) ((total - totSec) * 100);
        return String.format(Locale.US, "%02d:%02d.%02d / %02d:%02d.%02d",
            curSec / 60, curSec % 60, curCs, totSec / 60, totSec % 60, totCs);
    }

    // ─── Scenario Switching Logic ────────────────────────────────────────────
    private void switchScenario(Scenario newScenario) {
        if (isPlaying) {
            isPlaying = false;
            if (playbackTimer != null) playbackTimer.stop();
        }

        currentScenario = newScenario;
        currentTimelineSec = 0.0;
        eventDispatched = false;
        event2Dispatched = false;

        subLbl.setText(formatSubLabel());
        sendTriggerBtn.setText(currentScenario.triggerButtonLabel);
        timelineSlider.setMaximum((int) (currentScenario.durationSec * 1000));
        timelineSlider.setValue(0);
        timeDisplayLbl.setText(formatTimeString(0.0, currentScenario.durationSec));
        playBtn.setText("▶ PLAY VIDEO & SYNC");
        playBtn.setBackground(ACCENT_GREEN);
        playBtn.setForeground(Color.BLACK);

        // Update active frames from cache
        List<BufferedImage> frames = scenarioFramesCache.get(currentScenario);
        activeFrames.clear();
        if (frames != null && !frames.isEmpty()) {
            activeFrames.addAll(frames);
            statusVideoPill.setText("● VIDEO: " + activeFrames.size() + " FRAMES (" + currentScenario.videoFileName + ")");
            statusVideoPill.setForeground(ACCENT_GREEN);
            statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 2, true), new EmptyBorder(4, 10, 4, 10)));
        } else {
            statusVideoPill.setText("● VIDEO: LOADING...");
            statusVideoPill.setForeground(ACCENT_GOLD);
            statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GOLD, 2, true), new EmptyBorder(4, 10, 4, 10)));
            loadScenarioFrames(currentScenario);
        }

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("Switched to scenario: " + currentScenario.displayName);
        log("Duration: " + currentScenario.durationSec + "s | Frame Rate: " + currentScenario.fps + " FPS");
        log("Key Stimulus Cut: t = " + currentScenario.triggerSec + "s");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        videoViewport.repaint();
    }

    // ─── Video Viewport Canvas ───────────────────────────────────────────────
    private class VideoViewportPanel extends JPanel {
        public VideoViewportPanel() {
            setBackground(new Color(8, 12, 20));
            setBorder(new LineBorder(BORDER_COLOR, 2, true));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            BufferedImage frame = getCurrentFrameImage();
            if (frame != null) {
                // Fit frame with proper aspect ratio
                double imgAspect = (double) frame.getWidth() / frame.getHeight();
                double panelAspect = (double) getWidth() / getHeight();
                int drawW, drawH, drawX, drawY;

                if (panelAspect > imgAspect) {
                    drawH = getHeight();
                    drawW = (int) (drawH * imgAspect);
                    drawX = (getWidth() - drawW) / 2;
                    drawY = 0;
                } else {
                    drawW = getWidth();
                    drawH = (int) (drawW / imgAspect);
                    drawX = 0;
                    drawY = (getHeight() - drawH) / 2;
                }

                g2.drawImage(frame, drawX, drawY, drawW, drawH, null);

                // Video HUD Overlay
                String tag;
                Color tagColor;
                if (currentScenario == Scenario.PIR_ACTIVATED) {
                    if (currentTimelineSec < currentScenario.triggerSec) {
                        tag = currentScenario.hudBeforeTrigger;
                        tagColor = ACCENT_GOLD;
                    } else if (currentTimelineSec < currentScenario.trigger2Sec) {
                        tag = currentScenario.hudAfterTrigger;
                        tagColor = ACCENT_GREEN;
                    } else {
                        tag = currentScenario.hudAfterTrigger2;
                        tagColor = ACCENT_CYAN;
                    }
                } else {
                    boolean isBeforeCut = currentTimelineSec < currentScenario.triggerSec;
                    tag = isBeforeCut ? currentScenario.hudBeforeTrigger : currentScenario.hudAfterTrigger;
                    if (currentScenario == Scenario.DEPARTURE) {
                        tagColor = isBeforeCut ? ACCENT_GREEN : ACCENT_RED;
                    } else if (currentScenario == Scenario.SLEEPING) {
                        tagColor = isBeforeCut ? ACCENT_GREEN : ACCENT_PURPLE;
                    } else { // ENTERING
                        tagColor = isBeforeCut ? ACCENT_GOLD : ACCENT_GREEN;
                    }
                }

                Font hudFont = new Font(FONT_FAMILY, Font.BOLD, 13);
                g2.setFont(hudFont);
                FontMetrics fm = g2.getFontMetrics(hudFont);
                int tagW = fm.stringWidth(tag) + 26;

                // High contrast background badge for HUD
                g2.setColor(new Color(0, 0, 0, 225));
                g2.fillRoundRect(drawX + 14, drawY + 14, tagW, 34, 10, 10);
                g2.setColor(tagColor);
                g2.setStroke(new BasicStroke(2.0f));
                g2.drawRoundRect(drawX + 14, drawY + 14, tagW, 34, 10, 10);
                g2.drawString(tag, drawX + 27, drawY + 36);

                // Scenario Watermark in top right
                String wmark = currentScenario.videoFileName + " [" + currentScenario.fps + " FPS]";
                Font wmFont = new Font(FONT_FAMILY, Font.BOLD, 11);
                g2.setFont(wmFont);
                FontMetrics wmfm = g2.getFontMetrics(wmFont);
                int ww = wmfm.stringWidth(wmark) + 20;
                g2.setColor(new Color(0, 0, 0, 210));
                g2.fillRoundRect(drawX + drawW - ww - 14, drawY + 14, ww, 26, 8, 8);
                g2.setColor(TEXT_WHITE);
                g2.drawString(wmark, drawX + drawW - ww - 4, drawY + 31);
            } else {
                // Placeholder while loading
                g2.setColor(TEXT_WHITE);
                g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
                String txt = activeFrames.isEmpty() ? "Loading Video Frames from " + currentScenario.videoFileName + "..." : "Ready to Play";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(txt, (getWidth() - fm.stringWidth(txt)) / 2, getHeight() / 2);
            }
            g2.dispose();
        }

        private BufferedImage getCurrentFrameImage() {
            if (activeFrames.isEmpty()) return null;
            int frameIdx = (int) (currentTimelineSec * currentScenario.fps);
            if (frameIdx < 0) frameIdx = 0;
            if (frameIdx >= activeFrames.size()) frameIdx = activeFrames.size() - 1;
            return activeFrames.get(frameIdx);
        }
    }

    // ─── Asynchronous Video Frame Loader ─────────────────────────────────────
    private void loadAllScenariosAsync() {
        new Thread(() -> {
            for (Scenario sc : Scenario.values()) {
                loadScenarioFrames(sc);
            }
            // Once initial load finishes, assign activeFrames for currentScenario
            SwingUtilities.invokeLater(() -> {
                List<BufferedImage> frames = scenarioFramesCache.get(currentScenario);
                if (frames != null && !frames.isEmpty()) {
                    activeFrames.clear();
                    activeFrames.addAll(frames);
                    statusVideoPill.setText("● VIDEO: " + activeFrames.size() + " FRAMES (READY)");
                    statusVideoPill.setForeground(ACCENT_GREEN);
                    statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 2, true), new EmptyBorder(4, 10, 4, 10)));
                    log("All 4 scenario feeds loaded! Active: " + activeFrames.size() + " frames (" + currentScenario.videoFileName + ")");
                    videoViewport.repaint();
                }
            });
        }, "MultiScenarioFrameLoader").start();
    }

    private void loadScenarioFrames(Scenario sc) {
        try {
            File dir = new File(sc.framesDir);
            List<BufferedImage> list = new ArrayList<>();
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> name.startsWith("frame_") && name.endsWith(".jpg"));
                if (files != null && files.length > 0) {
                    Arrays.sort(files);
                    for (File f : files) {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null) list.add(img);
                    }
                }
            }
            scenarioFramesCache.put(sc, list);
            log(String.format(Locale.US, "Loaded %d frames for %s from ./%s", list.size(), sc.name(), sc.framesDir));
        } catch (Exception ex) {
            log("[ERR] Failed to load frames for " + sc.name() + ": " + ex.getMessage());
        }
    }

    private JLabel createMetricCard(String title, String val, Color valColor, JPanel container) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 4));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel tLbl = new JLabel(title);
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        tLbl.setForeground(TEXT_MUTED);

        JLabel vLbl = new JLabel(val);
        vLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 17));
        vLbl.setForeground(valColor);

        card.add(tLbl);
        card.add(vLbl);
        container.add(card);
        return vLbl;
    }

    private JLabel createPill(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        l.setForeground(color);
        l.setBorder(new CompoundBorder(
            new LineBorder(color, 2, true),
            new EmptyBorder(4, 10, 4, 10)
        ));
        return l;
    }

    private JButton createStyledButton(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        b.setForeground(fg);
        b.setBackground(bg);
        b.setFocusPainted(false);
        b.setBorder(new CompoundBorder(new LineBorder(bg.brighter(), 1, true), new EmptyBorder(10, 16, 10, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(String.format(Locale.US, "[%s] %s%n",
                new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()), msg));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ─── Network Communications ──────────────────────────────────────────────
    public synchronized void reconnectToPorts(String newHost, int newSPort, int newTPort) {
        log(String.format("Updating connection ports -> Host: %s, Sensors: %d, Telemetry: %d", newHost, newSPort, newTPort));
        this.host = newHost;
        this.sensorPort = newSPort;
        this.telemetryPort = newTPort;

        try { if (sensorSocket != null) sensorSocket.close(); } catch (Exception ignored) {}
        try { if (telemetrySocket != null) telemetrySocket.close(); } catch (Exception ignored) {}
        if (sensorThread != null) sensorThread.interrupt();
        if (telemetryThread != null) telemetryThread.interrupt();
        sensorConnected = false;
        telemetryConnected = false;

        connectSockets();
    }

    public void promptAndSetPorts() {
        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10));
        panel.setBackground(CARD_BG);

        JLabel hLbl = new JLabel("Host IP / Hostname:");
        hLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        hLbl.setForeground(TEXT_WHITE);
        JTextField hostFld = new JTextField(host);

        JLabel sLbl = new JLabel("⚡ Physical Sensors Port (Input):");
        sLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        sLbl.setForeground(ACCENT_GOLD);
        JTextField sPortFld = new JTextField(sensorPort > 0 ? String.valueOf(sensorPort) : "");

        JLabel tLbl = new JLabel("📡 Radio Telemetry Port (Monitor):");
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        tLbl.setForeground(ACCENT_CYAN);
        JTextField tPortFld = new JTextField(telemetryPort > 0 ? String.valueOf(telemetryPort) : "");

        panel.add(hLbl); panel.add(hostFld);
        panel.add(sLbl); panel.add(sPortFld);
        panel.add(tLbl); panel.add(tPortFld);

        int res = JOptionPane.showConfirmDialog(this, panel,
            "🔌 Input Dynamic Workspace Ports", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            try {
                String nHost = hostFld.getText().trim();
                int nSPort = Integer.parseInt(sPortFld.getText().trim());
                int nTPort = Integer.parseInt(tPortFld.getText().trim());
                reconnectToPorts(nHost, nSPort, nTPort);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid port number. Please enter numeric ports.", "Port Input Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void connectSockets() {
        // Sensors Bus Client
        if (sensorPort <= 0) {
            sensorConnected = false;
            SwingUtilities.invokeLater(() -> {
                statusSensorPill.setText("● SENSORS: SIM ONLY");
                statusSensorPill.setForeground(ACCENT_GOLD);
                statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GOLD, 2, true), new EmptyBorder(4, 10, 4, 10)));
            });
            log("[INFO] Sensors Bus set to 0. Operating in standalone simulation mode.");
        } else {
            sensorThread = new Thread(() -> {
                try {
                    sensorSocket = new Socket(host, sensorPort);
                    sensorOut = new PrintWriter(new OutputStreamWriter(sensorSocket.getOutputStream()), true);
                    sensorIn  = new BufferedReader(new InputStreamReader(sensorSocket.getInputStream()));
                    sensorConnected = true;

                    SwingUtilities.invokeLater(() -> {
                        statusSensorPill.setText("● SENSORS: " + sensorPort);
                        statusSensorPill.setForeground(ACCENT_GREEN);
                        statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 2, true), new EmptyBorder(4, 10, 4, 10)));
                    });
                    log("Connected to Physical Sensor Bus on " + host + ":" + sensorPort);

                    String line;
                    while (sensorConnected && (line = sensorIn.readLine()) != null) {
                        if (line.contains("ERR")) {
                            log("Sensors Bus Error: " + line);
                        }
                    }
                } catch (Exception ex) {
                    sensorConnected = false;
                    SwingUtilities.invokeLater(() -> {
                        statusSensorPill.setText("● SENSORS: OFF (" + sensorPort + ")");
                        statusSensorPill.setForeground(ACCENT_RED);
                        statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_RED, 2, true), new EmptyBorder(4, 10, 4, 10)));
                    });
                    log("[WARN] Sensor Bus unreachable on " + host + ":" + sensorPort + ". Click [🔌 ENTER PORTS] to update.");
                }
            }, "SensorsBusClient");
            sensorThread.start();
        }

        // Telemetry Receiver
        if (telemetryPort <= 0) {
            telemetryConnected = false;
            SwingUtilities.invokeLater(() -> {
                statusTelemPill.setText("● TELEMETRY: SIM ONLY");
                statusTelemPill.setForeground(ACCENT_GOLD);
                statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GOLD, 2, true), new EmptyBorder(4, 10, 4, 10)));
            });
            log("[INFO] Telemetry stream set to 0. Operating in standalone simulation mode.");
        } else {
            telemetryThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (!telemetryConnected) {
                        try {
                            telemetrySocket = new Socket(host, telemetryPort);
                            telemetryIn = new BufferedReader(new InputStreamReader(telemetrySocket.getInputStream()));
                            telemetryConnected = true;
                            SwingUtilities.invokeLater(() -> {
                                statusTelemPill.setText("● TELEMETRY: " + telemetryPort);
                                statusTelemPill.setForeground(ACCENT_GREEN);
                                statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 2, true), new EmptyBorder(4, 10, 4, 10)));
                            });
                            log("Connected to MCU Wi-Fi Telemetry Stream on " + host + ":" + telemetryPort);
                        } catch (Exception e) {
                            telemetryConnected = false;
                            SwingUtilities.invokeLater(() -> {
                                statusTelemPill.setText("● TELEMETRY: OFF (" + telemetryPort + ")");
                                statusTelemPill.setForeground(ACCENT_RED);
                                statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_RED, 2, true), new EmptyBorder(4, 10, 4, 10)));
                            });
                            try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                            continue;
                        }
                    }

                    try {
                        String line;
                        while ((line = telemetryIn.readLine()) != null) {
                            parseTelemetry(line);
                        }
                    } catch (Exception ex) {
                        telemetryConnected = false;
                        try { if (telemetrySocket != null) telemetrySocket.close(); } catch (Exception ignored) {}
                    }
                }
            }, "TelemetryReceiver");
            telemetryThread.start();
        }
    }

    private void parseTelemetry(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) return;
        String st = extractJsonVal(json, "state");
        boolean pir = extractJsonBool(json, "pir", false);
        double radV = extractJsonDouble(json, "radar_v", 0.0);
        double radPct = extractJsonDouble(json, "radar", 0.0);
        long hyst = (long) extractJsonDouble(json, "hysteresis_remaining_s", 0.0);

        SwingUtilities.invokeLater(() -> {
            if (!st.isEmpty()) {
                roomStateCard.setText(st);
                if (st.equalsIgnoreCase("OCCUPIED") || st.equalsIgnoreCase("SLEEP")) roomStateCard.setForeground(ACCENT_GREEN);
                else if (st.equalsIgnoreCase("HYSTERESIS")) roomStateCard.setForeground(ACCENT_GOLD);
                else roomStateCard.setForeground(TEXT_WHITE);
            }
            pirCard.setText(pir ? "3.3 V [HIGH]" : "0.0 V [LOW]");
            pirCard.setForeground(pir ? ACCENT_GREEN : TEXT_MUTED);

            radarCard.setText(String.format(Locale.US, "%.1f%% (%.2fV)", radPct, radV));
            radarCard.setForeground(radPct > 35 ? ACCENT_GREEN : TEXT_MUTED);

            hystCard.setText(hyst + " s");
            hystCard.setForeground(hyst > 0 ? ACCENT_GOLD : TEXT_MUTED);
        });
    }

    // ─── Playback & Video Sync Loop ──────────────────────────────────────────
    private void initPlaybackTimer() {
        // High fluid tick rate (40ms = 25 FPS update rate)
        playbackTimer = new javax.swing.Timer(40, e -> {
            if (!isPlaying) return;

            currentTimelineSec += 0.040;
            if (currentTimelineSec >= currentScenario.durationSec) {
                currentTimelineSec = currentScenario.durationSec;
                isPlaying = false;
                playBtn.setText("▶ PLAY VIDEO & SYNC");
                playBtn.setBackground(ACCENT_GREEN);
                playBtn.setForeground(Color.BLACK);
                log("Video playback completed (" + String.format(Locale.US, "%.2fs", currentScenario.durationSec) + ").");
            }

            // Frame-accurate primary trigger
            if (currentTimelineSec >= currentScenario.triggerSec && !eventDispatched) {
                eventDispatched = true;
                triggerActiveScenarioEvent("Synchronized at t = " + currentScenario.triggerSec + "s in " + currentScenario.videoFileName);
            }

            // Secondary trigger (for 2-phase scenarios like PIR motion end)
            if (currentTimelineSec >= currentScenario.trigger2Sec && !event2Dispatched) {
                event2Dispatched = true;
                triggerSecondaryEvent("Secondary event at t = " + currentScenario.trigger2Sec + "s in " + currentScenario.videoFileName);
            }

            updateTimelineDisplay();
            videoViewport.repaint();
        });
    }

    private void updateTimelineDisplay() {
        int millis = (int) (currentTimelineSec * 1000);
        timelineSlider.setValue(millis);
        timeDisplayLbl.setText(formatTimeString(currentTimelineSec, currentScenario.durationSec));
    }

    private void seekFromSlider() {
        currentTimelineSec = timelineSlider.getValue() / 1000.0;
        if (currentTimelineSec < currentScenario.triggerSec) {
            eventDispatched = false;
        }
        if (currentTimelineSec < currentScenario.trigger2Sec) {
            event2Dispatched = false;
        }
        updateTimelineDisplay();
        videoViewport.repaint();
    }

    private void togglePlay() {
        if (!isPlaying) {
            if (currentTimelineSec >= currentScenario.durationSec) {
                restartTimeline();
            }
            isPlaying = true;
            playBtn.setText("⏸ PAUSE VIDEO");
            playBtn.setBackground(ACCENT_GOLD);
            playBtn.setForeground(Color.BLACK);
            playbackTimer.start();
            log("Started synchronized playback (" + currentScenario.videoFileName + ")...");

            // Apply pre-trigger baseline stimulus
            if (currentTimelineSec < currentScenario.triggerSec) {
                applyBaselineStimulus();
            }
        } else {
            isPlaying = false;
            playBtn.setText("▶ RESUME VIDEO");
            playBtn.setBackground(ACCENT_GREEN);
            playBtn.setForeground(Color.BLACK);
            playbackTimer.stop();
            log("Paused video at " + String.format(Locale.US, "%.2fs", currentTimelineSec));
        }
    }

    private void restartTimeline() {
        isPlaying = false;
        playbackTimer.stop();
        currentTimelineSec = 0.0;
        eventDispatched = false;
        event2Dispatched = false;
        playBtn.setText("▶ PLAY VIDEO & SYNC");
        playBtn.setBackground(ACCENT_GREEN);
        playBtn.setForeground(Color.BLACK);

        updateTimelineDisplay();
        videoViewport.repaint();
        log("Reset video and timeline to 00:00.00.");

        applyBaselineStimulus();
    }

    private void applyBaselineStimulus() {
        switch (currentScenario) {
            case DEPARTURE:
            case SLEEPING:
                injectOccupiedStimulus("Video Start (Active Presence in Room)");
                break;
            case ENTERING:
            case PIR_ACTIVATED:
                injectVacantStimulus("Video Start (Room Vacant / Standby)");
                break;
        }
    }

    // ─── Scenario-Aware Physical Stimuli ─────────────────────────────────────
    public void injectOccupiedStimulus(String reason) {
        log("💡 [STIMULUS] OCCUPIED applied: " + reason);
        log("  → PIR DATA = 3.30V [HIGH]");
        log("  → MillimeterWaveRadar = 2.80V [HIGH OCCUPANCY]");
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.80);
    }

    public void injectVacantStimulus(String reason) {
        log("🌑 [STIMULUS] VACANT applied: " + reason);
        log("  → PIR DATA = 0.00V [LOW]");
        log("  → MillimeterWaveRadar = 0.05V [NO MOTION]");
        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
    }

    public void triggerActiveScenarioEvent(String reason) {
        switch (currentScenario) {
            case DEPARTURE:
                injectDepartureStimulus(reason);
                break;
            case SLEEPING:
                injectSleepModeStimulus(reason);
                break;
            case ENTERING:
                injectEntryStimulus(reason);
                break;
            case PIR_ACTIVATED:
                injectPirMotionDetected(reason);
                break;
        }
    }

    public void triggerSecondaryEvent(String reason) {
        if (currentScenario == Scenario.PIR_ACTIVATED) {
            log("💤 [EVENT] PIR MOTION CEASED: " + reason);
            log("  → PIR DATA = 0.00V [LOW]");
            log("  → Radar = 0.05V [IDLE]");
            sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
            sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
        }
    }

    public void injectDepartureStimulus(String reason) {
        log("🚁 [EVENT] NOBITA BAMBOO-COPTER DEPARTURE: " + reason);
        log("  → PIR DATA = 0.0V (Nobita has flown out the window)");
        log("  → Radar = 0.05V (Micro-motion lost)");
        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
    }

    public void injectSleepModeStimulus(String reason) {
        log("🌙 [EVENT] NOBITA SLEEP & DIMMING MODE: " + reason);
        log("  → Nobita asleep (t = 4.0s) -> Room lights dim down");
        log("  → PIR DATA = 0.0V (No gross body movement)");
        log("  → MillimeterWaveRadar = 1.95V (Vital respiration micro-movement)");
        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 1.95);
    }

    public void injectEntryStimulus(String reason) {
        log("🚶 [EVENT] NOBITA ROOM ENTRY: " + reason);
        log("  → Nobita opens door and crosses threshold at t = 6.6s!");
        log("  → PIR DATA = 3.30V [HIGH MOTION]");
        log("  → MillimeterWaveRadar = 2.85V [PRESENCE ACTIVE]");
        log("  → ESP32 triggers Smart Lighting ON!");
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.85);
    }

    public void injectPirMotionDetected(String reason) {
        log("🚨 [EVENT] PIR MOTION SENSOR TRIGGERED: " + reason);
        log("  → Motion detected in optical Fresnel zone at t = 2.3s!");
        log("  → PIR DATA = 3.30V [HIGH]");
        log("  → MillimeterWaveRadar = 2.90V [RADAR CONFIRMED]");
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.90);
    }

    private void sendSensorPin(String board, String pin, double voltage) {
        if (sensorOut != null && sensorConnected) {
            String cmd = String.format(Locale.US, "SET %s %s %.3f", board, pin, voltage);
            sensorOut.println(cmd);
        } else {
            log("[WARN] Sensor socket not connected. Unable to send SET " + board + " " + pin);
        }
    }

    // ─── JSON Utility Helpers ────────────────────────────────────────────────
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

    // ─── Launcher Entry Point ────────────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        String host = "localhost";
        int sPort = -1;
        int tPort = -1;

        if (args.length >= 3) {
            host = args[0];
            sPort = Integer.parseInt(args[1]);
            tPort = Integer.parseInt(args[2]);
        } else if (args.length == 2) {
            sPort = Integer.parseInt(args[0]);
            tPort = Integer.parseInt(args[1]);
        }

        if (sPort <= 0 || tPort <= 0 || (args.length > 0 && args[0].equalsIgnoreCase("--prompt"))) {
            JPanel panel = new JPanel(new GridLayout(4, 1, 6, 6));
            JLabel info = new JLabel("<html><b>Workspace Dynamic Ports:</b><br>Enter the port numbers displayed in your backend terminal.<br><i>(Hosted values are random and are not reused)</i></html>");
            info.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
            panel.add(info);

            JPanel fGroup = new JPanel(new GridLayout(3, 2, 8, 8));
            JTextField hostFld = new JTextField("localhost");
            JTextField sPortFld = new JTextField(""); // Blank: user enters value noted from terminal
            JTextField tPortFld = new JTextField(""); // Blank: user enters value noted from terminal

            fGroup.add(new JLabel("Host / IP:"));
            fGroup.add(hostFld);
            fGroup.add(new JLabel("⚡ Sensors Bus Port (from terminal):"));
            fGroup.add(sPortFld);
            fGroup.add(new JLabel("📡 Telemetry Stream Port (from terminal):"));
            fGroup.add(tPortFld);

            panel.add(fGroup);

            Object[] options = {"⚡ Connect to Ports", "🚀 Standalone Sim Mode", "Exit"};
            int choice = JOptionPane.showOptionDialog(
                null,
                panel,
                "Nobita Video Sync — Enter Ports Noted From Terminal",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
            );

            if (choice == 0) {
                String sText = sPortFld.getText().trim();
                String tText = tPortFld.getText().trim();
                if (sText.isEmpty() || tText.isEmpty()) {
                    JOptionPane.showMessageDialog(null,
                        "Ports cannot be empty. Please enter the port numbers you noted down from the terminal.\nSwitching to Standalone Sim Mode.",
                        "No Port Specified", JOptionPane.WARNING_MESSAGE);
                    sPort = 0;
                    tPort = 0;
                } else {
                    try {
                        host = hostFld.getText().trim();
                        sPort = Integer.parseInt(sText);
                        tPort = Integer.parseInt(tText);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(null,
                            "Invalid port numbers entered. Switching to Standalone Sim Mode.",
                            "Port Format Error", JOptionPane.ERROR_MESSAGE);
                        sPort = 0;
                        tPort = 0;
                    }
                }
            } else if (choice == 1) {
                sPort = 0;
                tPort = 0;
            } else {
                return;
            }
        }

        final String finalHost = host;
        final int finalSPort = sPort;
        final int finalTPort = tPort;
        SwingUtilities.invokeLater(() -> new NobitaDepartureSyncApp(finalHost, finalSPort, finalTPort));
    }
}
