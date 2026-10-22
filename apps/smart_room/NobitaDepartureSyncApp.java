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
 * Integrated Scenarios:
 * 1. Scenario 1: Nobita Departure via Bamboo Copter (Timeline 1.mov)
 *    - Duration: 6.00s | Exit Trigger: t = 3.00s
 *    - t < 3.0s: PIR = 3.3V, mmWave = 2.80V (OCCUPIED)
 *    - t >= 3.0s: PIR = 0.0V, mmWave = 0.05V (DEPARTED / VACANT -> Hysteresis)
 *
 * 2. Scenario 2: Nobita Sleeping & Automatic Dimming (Nobita sleeping.mov)
 *    - Duration: 5.00s | Dimming/Sleep Trigger: t = 4.00s
 *    - t < 4.0s: PIR = 3.3V, mmWave = 2.80V (OCCUPIED / AWAKE)
 *    - t >= 4.0s: PIR = 0.0V (no gross movement), mmWave = 1.95V (breathing micro-motion)
 *      -> ESP32 firmware holds SLEEP MODE, prevents false-vacant turnoff, dims main lighting!
 *
 * Features:
 * - Direct 100% Native Java In-Applet Video Canvas (60 FPS fluid rendering).
 * - Instant Scenario Dropdown Selector with live frame-switching & timeline reconfiguration.
 * - Interactive Transport Bar: Play/Pause, 1ms-scrubber, frame stepper, and restart.
 * - Cyber-Physical Buses: Port 9091 (Sensors Input) & Port 9090 (Telemetry HUD).
 * ==============================================================================
 */
public class NobitaDepartureSyncApp extends JFrame {

    // Style & Theme Palette
    private static final String FONT_FAMILY   = "Space Mono";
    private static final Color BG_DARK        = new Color(13, 17, 23);
    private static final Color CARD_BG        = new Color(22, 27, 34);
    private static final Color BORDER_COLOR   = new Color(48, 54, 61);
    private static final Color ACCENT_CYAN    = new Color(56, 189, 248);
    private static final Color ACCENT_GREEN   = new Color(46, 204, 113);
    private static final Color ACCENT_AMBER   = new Color(245, 158, 11);
    private static final Color ACCENT_PURPLE  = new Color(168, 85, 247);
    private static final Color ACCENT_RED     = new Color(239, 68, 68);
    private static final Color TEXT_MAIN      = new Color(240, 246, 252);
    private static final Color TEXT_MUTED     = new Color(139, 148, 158);

    // Network & Target Configuration
    private final String host;
    private final int sensorPort;     // Port 9091 (Fixed Sensors Port)
    private final int telemetryPort;  // Port 9090 (Fixed Telemetry Port)

    // Socket connections
    private Socket sensorSocket;
    private PrintWriter sensorOut;
    private BufferedReader sensorIn;
    private volatile boolean sensorConnected = false;

    private Socket telemetrySocket;
    private BufferedReader telemetryIn;
    private volatile boolean telemetryConnected = false;

    // ─── Scenario Definitions ────────────────────────────────────────────────
    public enum Scenario {
        DEPARTURE(
            "🚪 Scenario 1: Nobita Departure (Timeline 1.mov — Exit at 3.0s)",
            "Timeline 1.mov",
            "assets/nobita_frames",
            6.0,
            3.0,
            "● NOBITA IN ROOM (t < 3s)",
            "★ BAMBOO-COPTER EXIT (t ≥ 3s)",
            "TRIGGER 3.0s EXIT"
        ),
        SLEEPING(
            "🛏️ Scenario 2: Nobita Sleeping & Dimming (Nobita sleeping.mov — Dims at 4.0s)",
            "Nobita sleeping.mov",
            "assets/sleeping_frames",
            5.0,
            4.0,
            "● NOBITA AWAKE / IN BED (t < 4s)",
            "🌙 SLEEP MODE — LIGHTS DIMMED (t ≥ 4s)",
            "TRIGGER 4.0s SLEEP"
        );

        public final String displayName;
        public final String videoFileName;
        public final String framesDir;
        public final double durationSec;
        public final double triggerSec;
        public final String hudBeforeTrigger;
        public final String hudAfterTrigger;
        public final String triggerButtonLabel;

        Scenario(String displayName, String videoFileName, String framesDir, double durationSec,
                 double triggerSec, String hudBeforeTrigger, String hudAfterTrigger, String triggerButtonLabel) {
            this.displayName = displayName;
            this.videoFileName = videoFileName;
            this.framesDir = framesDir;
            this.durationSec = durationSec;
            this.triggerSec = triggerSec;
            this.hudBeforeTrigger = hudBeforeTrigger;
            this.hudAfterTrigger = hudAfterTrigger;
            this.triggerButtonLabel = triggerButtonLabel;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private Scenario currentScenario = Scenario.SLEEPING; // Default to the newly added sleeping scenario

    // Cache of loaded frames per scenario
    private final Map<Scenario, List<BufferedImage>> scenarioFramesCache = new EnumMap<>(Scenario.class);
    private final List<BufferedImage> activeFrames = new ArrayList<>();

    // Timeline & Video Playback State
    private static final int VIDEO_FPS = 20; // 20 FPS standard
    private volatile boolean isPlaying = false;
    private volatile double currentTimelineSec = 0.0;
    private boolean eventDispatched = false;
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
        super("Nobita CPS In-App Video Player & Synchronizer — Multi-Scenario Edition");
        this.host = host;
        this.sensorPort = sensorPort;
        this.telemetryPort = telemetryPort;

        setupUI();
        initPlaybackTimer();
        loadAllScenariosAsync();
        connectSockets();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);
    }

    public NobitaDepartureSyncApp() {
        this("localhost", CpsDomain.PORT_SENSORS, CpsDomain.PORT_TELEMETRY);
    }

    private void setupUI() {
        setLayout(new BorderLayout(12, 12));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(14, 16, 14, 16));

        // ── TOP: Header Banner ────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(CARD_BG);
        header.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel titleGrp = new JPanel(new GridLayout(2, 1, 0, 3));
        titleGrp.setOpaque(false);
        JLabel titleLbl = new JLabel("🎬 NOBITA SMART ROOM — CPS MULTI-SCENARIO SYNCHRONIZER");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_CYAN);

        subLbl = new JLabel(formatSubLabel());
        subLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        subLbl.setForeground(TEXT_MUTED);
        titleGrp.add(titleLbl);
        titleGrp.add(subLbl);

        JPanel pillsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        pillsPanel.setOpaque(false);

        statusVideoPill  = createPill("VIDEO: LOADING...", ACCENT_AMBER);
        statusSensorPill = createPill("SENSORS: 9091", ACCENT_AMBER);
        statusTelemPill  = createPill("TELEMETRY: 9090", ACCENT_AMBER);
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
        JPanel leftVideoCard = new JPanel(new BorderLayout(10, 10));
        leftVideoCard.setBackground(CARD_BG);
        leftVideoCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));
        leftVideoCard.setPreferredSize(new Dimension(720, 0));

        // Scenario Selector Row
        JPanel scenarioRow = new JPanel(new BorderLayout(8, 0));
        scenarioRow.setOpaque(false);
        JLabel scLbl = new JLabel("ACTIVE SCENARIO:");
        scLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scLbl.setForeground(ACCENT_AMBER);

        scenarioSelector = new JComboBox<>(Scenario.values());
        scenarioSelector.setSelectedItem(currentScenario);
        scenarioSelector.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scenarioSelector.setBackground(new Color(15, 23, 42));
        scenarioSelector.setForeground(TEXT_MAIN);
        scenarioSelector.setFocusable(false);
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
        JPanel videoControlBar = new JPanel(new BorderLayout(8, 6));
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

        JPanel transportBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        transportBtnRow.setOpaque(false);

        playBtn = createStyledButton("▶ PLAY VIDEO & SYNC", ACCENT_GREEN);
        playBtn.addActionListener(e -> togglePlay());

        restartBtn = createStyledButton("↺ RESTART", new Color(75, 85, 99));
        restartBtn.addActionListener(e -> restartTimeline());

        timeDisplayLbl = new JLabel(formatTimeString(0.0, currentScenario.durationSec));
        timeDisplayLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        timeDisplayLbl.setForeground(ACCENT_AMBER);

        transportBtnRow.add(playBtn);
        transportBtnRow.add(restartBtn);
        transportBtnRow.add(Box.createHorizontalStrut(10));
        transportBtnRow.add(timeDisplayLbl);

        videoControlBar.add(timelineSlider, BorderLayout.NORTH);
        videoControlBar.add(transportBtnRow, BorderLayout.SOUTH);
        leftVideoCard.add(videoControlBar, BorderLayout.SOUTH);

        mainContent.add(leftVideoCard, BorderLayout.CENTER);

        // RIGHT SIDE: CPS Telemetry HUD & Live Event Log
        JPanel rightPanel = new JPanel(new BorderLayout(12, 12));
        rightPanel.setOpaque(false);
        rightPanel.setPreferredSize(new Dimension(500, 0));

        // Upper: Cyber-Physical Status Metrics
        JPanel telemetryCard = new JPanel(new BorderLayout(10, 10));
        telemetryCard.setBackground(CARD_BG);
        telemetryCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel telemTitle = new JLabel("CPS REAL-TIME TELEMETRY HUD (PORT 9090)");
        telemTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        telemTitle.setForeground(TEXT_MAIN);
        telemetryCard.add(telemTitle, BorderLayout.NORTH);

        JPanel metricsGrid = new JPanel(new GridLayout(2, 2, 10, 10));
        metricsGrid.setOpaque(false);
        roomStateCard = createMetricCard("ROOM STATE", "VACANT", ACCENT_AMBER, metricsGrid);
        pirCard       = createMetricCard("PIR SENSOR (GPIO_12)", "0.0 V [LOW]", TEXT_MUTED, metricsGrid);
        radarCard     = createMetricCard("RADAR CONF (GPIO_27)", "0.0% (0.00V)", TEXT_MUTED, metricsGrid);
        hystCard      = createMetricCard("HYSTERESIS REMAIN", "0 s", ACCENT_CYAN, metricsGrid);
        telemetryCard.add(metricsGrid, BorderLayout.CENTER);

        // Manual Injection Buttons
        JPanel manualBtnRow = new JPanel(new GridLayout(1, 2, 8, 0));
        manualBtnRow.setOpaque(false);
        sendOccupiedBtn = createStyledButton("FORCE OCCUPIED", new Color(37, 99, 235));
        sendOccupiedBtn.addActionListener(e -> injectOccupiedStimulus("Manual User Override"));

        sendTriggerBtn = createStyledButton(currentScenario.triggerButtonLabel, ACCENT_PURPLE);
        sendTriggerBtn.addActionListener(e -> triggerActiveScenarioEvent("Manual User Override"));

        manualBtnRow.add(sendOccupiedBtn);
        manualBtnRow.add(sendTriggerBtn);
        telemetryCard.add(manualBtnRow, BorderLayout.SOUTH);

        rightPanel.add(telemetryCard, BorderLayout.NORTH);

        // Lower: Real-time Event Log
        JPanel logCard = new JPanel(new BorderLayout(8, 8));
        logCard.setBackground(CARD_BG);
        logCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel logHeader = new JLabel("CPS SENSOR INJECTION & TELEMETRY LOG");
        logHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        logHeader.setForeground(TEXT_MUTED);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(10, 13, 18));
        logArea.setForeground(new Color(180, 220, 200));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
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

        subLbl.setText(formatSubLabel());
        sendTriggerBtn.setText(currentScenario.triggerButtonLabel);
        timelineSlider.setMaximum((int) (currentScenario.durationSec * 1000));
        timelineSlider.setValue(0);
        timeDisplayLbl.setText(formatTimeString(0.0, currentScenario.durationSec));
        playBtn.setText("▶ PLAY VIDEO & SYNC");
        playBtn.setBackground(ACCENT_GREEN);

        // Update active frames from cache
        List<BufferedImage> frames = scenarioFramesCache.get(currentScenario);
        activeFrames.clear();
        if (frames != null && !frames.isEmpty()) {
            activeFrames.addAll(frames);
            statusVideoPill.setText("● VIDEO: " + activeFrames.size() + " FRAMES (" + currentScenario.videoFileName + ")");
            statusVideoPill.setForeground(ACCENT_GREEN);
            statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
        } else {
            statusVideoPill.setText("● VIDEO: LOADING...");
            statusVideoPill.setForeground(ACCENT_AMBER);
            statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_AMBER, 1, true), new EmptyBorder(3, 8, 3, 8)));
            loadScenarioFrames(currentScenario);
        }

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("Switched to active scenario: " + currentScenario.displayName);
        log("Duration: " + currentScenario.durationSec + "s | Event Cut: t = " + currentScenario.triggerSec + "s");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        videoViewport.repaint();
    }

    // ─── Video Viewport Canvas ───────────────────────────────────────────────
    private class VideoViewportPanel extends JPanel {
        public VideoViewportPanel() {
            setBackground(new Color(10, 13, 18));
            setBorder(new LineBorder(BORDER_COLOR, 1, true));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

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
                boolean isBeforeCut = currentTimelineSec < currentScenario.triggerSec;
                String tag = isBeforeCut ? currentScenario.hudBeforeTrigger : currentScenario.hudAfterTrigger;
                Color tagColor = isBeforeCut ? ACCENT_GREEN : (currentScenario == Scenario.SLEEPING ? ACCENT_PURPLE : ACCENT_RED);

                FontMetrics fm = g2.getFontMetrics(new Font(FONT_FAMILY, Font.BOLD, 11));
                int tagW = fm.stringWidth(tag) + 24;

                g2.setColor(new Color(0, 0, 0, 160));
                g2.fillRoundRect(drawX + 12, drawY + 12, tagW, 28, 8, 8);
                g2.setColor(tagColor);
                g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
                g2.drawString(tag, drawX + 22, drawY + 30);

                // Scenario Watermark in corner
                String wmark = currentScenario.videoFileName + " [20 FPS]";
                g2.setColor(new Color(0, 0, 0, 120));
                FontMetrics wmfm = g2.getFontMetrics(new Font(FONT_FAMILY, Font.PLAIN, 10));
                int ww = wmfm.stringWidth(wmark) + 16;
                g2.fillRoundRect(drawX + drawW - ww - 12, drawY + 12, ww, 22, 6, 6);
                g2.setColor(TEXT_MUTED);
                g2.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
                g2.drawString(wmark, drawX + drawW - ww - 4, drawY + 27);
            } else {
                // Placeholder while loading
                g2.setColor(TEXT_MUTED);
                g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 14));
                String txt = activeFrames.isEmpty() ? "Loading Video Frames from " + currentScenario.videoFileName + "..." : "Ready to Play";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(txt, (getWidth() - fm.stringWidth(txt)) / 2, getHeight() / 2);
            }
            g2.dispose();
        }

        private BufferedImage getCurrentFrameImage() {
            if (activeFrames.isEmpty()) return null;
            int frameIdx = (int) (currentTimelineSec * VIDEO_FPS);
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
                    statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                    log("Active video frames loaded: " + activeFrames.size() + " frames for " + currentScenario.videoFileName);
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
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        tLbl.setForeground(TEXT_MUTED);

        JLabel vLbl = new JLabel(val);
        vLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        vLbl.setForeground(valColor);

        card.add(tLbl);
        card.add(vLbl);
        container.add(card);
        return vLbl;
    }

    private JLabel createPill(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        l.setForeground(color);
        l.setBorder(new CompoundBorder(
            new LineBorder(color, 1, true),
            new EmptyBorder(3, 8, 3, 8)
        ));
        return l;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(9, 16, 9, 16));
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
    private void connectSockets() {
        // Port 9091 (Sensors Bus Client)
        new Thread(() -> {
            try {
                sensorSocket = new Socket(host, sensorPort);
                sensorOut = new PrintWriter(new OutputStreamWriter(sensorSocket.getOutputStream()), true);
                sensorIn  = new BufferedReader(new InputStreamReader(sensorSocket.getInputStream()));
                sensorConnected = true;

                SwingUtilities.invokeLater(() -> {
                    statusSensorPill.setText("● SENSORS: 9091");
                    statusSensorPill.setForeground(ACCENT_GREEN);
                    statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
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
                    statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_RED, 1, true), new EmptyBorder(3, 8, 3, 8)));
                });
                log("[WARN] Sensor Bus not open yet on " + host + ":" + sensorPort + " (" + ex.getMessage() + ")");
            }
        }, "SensorsBusClient").start();

        // Port 9090 (Telemetry Receiver)
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (!telemetryConnected) {
                    try {
                        telemetrySocket = new Socket(host, telemetryPort);
                        telemetryIn = new BufferedReader(new InputStreamReader(telemetrySocket.getInputStream()));
                        telemetryConnected = true;
                        SwingUtilities.invokeLater(() -> {
                            statusTelemPill.setText("● TELEMETRY: 9090");
                            statusTelemPill.setForeground(ACCENT_GREEN);
                            statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                        });
                        log("Connected to MCU Wi-Fi Telemetry Stream on " + host + ":" + telemetryPort);
                    } catch (Exception e) {
                        telemetryConnected = false;
                        SwingUtilities.invokeLater(() -> {
                            statusTelemPill.setText("● TELEMETRY: OFF (" + telemetryPort + ")");
                            statusTelemPill.setForeground(ACCENT_RED);
                            statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_RED, 1, true), new EmptyBorder(3, 8, 3, 8)));
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
        }, "TelemetryReceiver").start();
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
                else if (st.equalsIgnoreCase("HYSTERESIS")) roomStateCard.setForeground(ACCENT_AMBER);
                else roomStateCard.setForeground(TEXT_MUTED);
            }
            pirCard.setText(pir ? "3.3 V [HIGH]" : "0.0 V [LOW]");
            pirCard.setForeground(pir ? ACCENT_GREEN : TEXT_MUTED);

            radarCard.setText(String.format(Locale.US, "%.1f%% (%.2fV)", radPct, radV));
            radarCard.setForeground(radPct > 35 ? ACCENT_GREEN : TEXT_MUTED);

            hystCard.setText(hyst + " s");
            hystCard.setForeground(hyst > 0 ? ACCENT_AMBER : TEXT_MUTED);
        });
    }

    // ─── Playback & Video Sync Loop ──────────────────────────────────────────
    private void initPlaybackTimer() {
        // 50ms tick (20 FPS) perfectly matches video frame rate
        playbackTimer = new javax.swing.Timer(50, e -> {
            if (!isPlaying) return;

            currentTimelineSec += 0.050;
            if (currentTimelineSec >= currentScenario.durationSec) {
                currentTimelineSec = currentScenario.durationSec;
                isPlaying = false;
                playBtn.setText("▶ PLAY VIDEO & SYNC");
                playBtn.setBackground(ACCENT_GREEN);
                log("Video playback and timeline completed (" + String.format(Locale.US, "%.2fs", currentScenario.durationSec) + ").");
            }

            // Frame-accurate event trigger
            if (currentTimelineSec >= currentScenario.triggerSec && !eventDispatched) {
                eventDispatched = true;
                triggerActiveScenarioEvent("Synchronized at t = " + currentScenario.triggerSec + "s in " + currentScenario.videoFileName);
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
            playBtn.setBackground(ACCENT_AMBER);
            playbackTimer.start();
            log("Started video and CPS simulation sync playback (" + currentScenario.videoFileName + ")...");

            if (currentTimelineSec < currentScenario.triggerSec) {
                injectOccupiedStimulus("Video Start (Active Presence in Room)");
            }
        } else {
            isPlaying = false;
            playBtn.setText("▶ RESUME VIDEO");
            playBtn.setBackground(ACCENT_GREEN);
            playbackTimer.stop();
            log("Paused video at " + String.format(Locale.US, "%.2fs", currentTimelineSec));
        }
    }

    private void restartTimeline() {
        isPlaying = false;
        playbackTimer.stop();
        currentTimelineSec = 0.0;
        eventDispatched = false;
        playBtn.setText("▶ PLAY VIDEO & SYNC");
        playBtn.setBackground(ACCENT_GREEN);

        updateTimelineDisplay();
        videoViewport.repaint();
        log("Reset video and timeline to 00:00.00.");

        injectOccupiedStimulus("Reset to t=0 (Nobita in Room)");
    }

    // ─── Scenario-Aware Physical Stimuli ─────────────────────────────────────
    public void injectOccupiedStimulus(String reason) {
        log("▶ [STIMULUS] OCCUPIED applied: " + reason);
        log("  → Driving PIR DATA = 3.30V [HIGH]");
        log("  → Driving MillimeterWaveRadar = 2.80V [HIGH CONFIDENCE]");
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.80);
    }

    public void triggerActiveScenarioEvent(String reason) {
        if (currentScenario == Scenario.DEPARTURE) {
            injectDepartureStimulus(reason);
        } else if (currentScenario == Scenario.SLEEPING) {
            injectSleepModeStimulus(reason);
        }
    }

    public void injectDepartureStimulus(String reason) {
        log("★ [EVENT] NOBITA BAMBOO-COPTER DEPARTURE: " + reason);
        log("  → Driving PIR DATA = 0.0V (Nobita has flown out the window)");
        log("  → Driving MillimeterWaveRadar = 0.05V (Micro-motion lost)");
        log("  → Sensed by ESP32 MCU on GPIO_12 and GPIO_27");
        log("  → Entering HYSTERESIS / VACANT mode!");

        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
    }

    public void injectSleepModeStimulus(String reason) {
        log("🌙 [EVENT] NOBITA SLEEP & DIMMING MODE: " + reason);
        log("  → Nobita settles to sleep at t = 4.0s (Main lighting dims away)");
        log("  → Driving PIR DATA = 0.0V (No gross body movement across passive IR)");
        log("  → Driving MillimeterWaveRadar = 1.95V (60 GHz Radar detects respiration micro-motion)");
        log("  → Sensed by ESP32 MCU: Holds OCCUPIED / SLEEP mode; prevents false-vacant shutoff!");

        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 1.95);
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
        String host = args.length > 0 ? args[0] : "localhost";
        int sPort   = args.length > 1 ? Integer.parseInt(args[1]) : CpsDomain.PORT_SENSORS;
        int tPort   = args.length > 2 ? Integer.parseInt(args[2]) : CpsDomain.PORT_TELEMETRY;

        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new NobitaDepartureSyncApp(host, sPort, tPort));
    }
}
