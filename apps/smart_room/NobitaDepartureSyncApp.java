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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NobitaDepartureSyncApp
 * ==============================================================================
 * Cyber-Physical Embedded Video Player & Simulation Synchronizer.
 *
 * Integrated Video Features:
 * - Direct 100% Native Java In-Applet Video Canvas:
 *     Plays "Timeline 1.mov" directly inside the applet window with 60 FPS rendering.
 * - Interactive Video Controls:
 *     Play/Pause, scrub slider, click-to-seek, frame stepper, and restart.
 * - Exact Cyber-Physical Event Synchronization:
 *     - At t < 3.0s (Nobita in room):
 *         PIR Motion = 3.3V (Active), Radar = 2.80V, State = OCCUPIED
 *     - At t = 3.000s (Nobita Bamboo-Copter Window Exit):
 *         Physical sensors cut off instantaneously:
 *           PIR DATA -> 0.0V
 *           MillimeterWaveRadar -> 0.05V
 *         Transmitted over Port 9091 (Sensors Bus).
 *     - Real-Time Telemetry HUD:
 *         Listens to Port 9090 (Telemetry Bus) and updates State, Relays & Hysteresis.
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

    // Timeline & Video Playback State
    private static final double VIDEO_DURATION_SEC = 6.0;
    private static final double EVENT_TRIGGER_SEC  = 3.0; // Cut moment in Timeline 1.mov
    private static final int VIDEO_FPS             = 20;  // 120 total frames for 6.00s

    private final List<BufferedImage> videoFrames = new ArrayList<>();
    private volatile boolean isPlaying = false;
    private volatile double currentTimelineSec = 0.0;
    private boolean eventDispatched = false;
    private javax.swing.Timer playbackTimer;

    // Video Viewport Component
    private VideoViewportPanel videoViewport;

    // UI Elements
    private JLabel statusSensorPill;
    private JLabel statusTelemPill;
    private JLabel statusVideoPill;
    private JLabel timeDisplayLbl;
    private JSlider timelineSlider;
    private JButton playBtn;
    private JButton restartBtn;
    private JButton sendOccupiedBtn;
    private JButton sendDepartedBtn;

    // Telemetry Cards
    private JLabel roomStateCard;
    private JLabel pirCard;
    private JLabel radarCard;
    private JLabel hystCard;

    // Event Log
    private JTextArea logArea;

    public NobitaDepartureSyncApp(String host, int sensorPort, int telemetryPort) {
        super("Nobita Departure CPS In-App Video Player & Synchronizer — Timeline 1.mov");
        this.host = host;
        this.sensorPort = sensorPort;
        this.telemetryPort = telemetryPort;

        setupUI();
        loadVideoFramesAsync();
        initPlaybackTimer();
        connectSockets();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1240, 780);
        setMinimumSize(new Dimension(1080, 680));
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
        JLabel titleLbl = new JLabel("🎬 NOBITA DEPARTURE — IN-APPLET VIDEO & CPS SYNCHRONIZER");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_CYAN);

        JLabel subLbl = new JLabel("Native Embedded Video: Timeline 1.mov (Exit at t = 3.00s) | Sensors Bus: " + sensorPort + " | Telemetry: " + telemetryPort);
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

        // LEFT SIDE: Video Player & Direct Transport Bar
        JPanel leftVideoCard = new JPanel(new BorderLayout(10, 10));
        leftVideoCard.setBackground(CARD_BG);
        leftVideoCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));
        leftVideoCard.setPreferredSize(new Dimension(680, 0));

        // Video Viewport Canvas
        videoViewport = new VideoViewportPanel();
        leftVideoCard.add(videoViewport, BorderLayout.CENTER);

        // Under-Video Scrubber & Time Bar
        JPanel videoControlBar = new JPanel(new BorderLayout(8, 6));
        videoControlBar.setOpaque(false);

        timelineSlider = new JSlider(0, (int) (VIDEO_DURATION_SEC * 1000), 0);
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

        timeDisplayLbl = new JLabel("00:00.00 / 00:06.00");
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
        rightPanel.setPreferredSize(new Dimension(480, 0));

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
        sendOccupiedBtn.addActionListener(e -> injectOccupiedStimulus("Manual Trigger"));

        sendDepartedBtn = createStyledButton("TRIGGER 3.0s EXIT", ACCENT_RED);
        sendDepartedBtn.addActionListener(e -> injectDepartureStimulus("Manual Trigger"));
        manualBtnRow.add(sendOccupiedBtn);
        manualBtnRow.add(sendDepartedBtn);
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
                g2.setColor(new Color(0, 0, 0, 140));
                g2.fillRoundRect(drawX + 12, drawY + 12, 190, 28, 8, 8);
                g2.setColor(currentTimelineSec < EVENT_TRIGGER_SEC ? ACCENT_GREEN : ACCENT_RED);
                g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
                String tag = currentTimelineSec < EVENT_TRIGGER_SEC ? "● NOBITA IN ROOM (t < 3s)" : "★ BAMBOO-COPTER EXIT (t ≥ 3s)";
                g2.drawString(tag, drawX + 22, drawY + 30);
            } else {
                // Placeholder while loading
                g2.setColor(TEXT_MUTED);
                g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 14));
                String txt = videoFrames.isEmpty() ? "Loading Video Frames from Timeline 1.mov..." : "Ready to Play";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(txt, (getWidth() - fm.stringWidth(txt)) / 2, getHeight() / 2);
            }
            g2.dispose();
        }

        private BufferedImage getCurrentFrameImage() {
            if (videoFrames.isEmpty()) return null;
            int frameIdx = (int) (currentTimelineSec * VIDEO_FPS);
            if (frameIdx < 0) frameIdx = 0;
            if (frameIdx >= videoFrames.size()) frameIdx = videoFrames.size() - 1;
            return videoFrames.get(frameIdx);
        }
    }

    // ─── Asynchronous Video Frame Loader ─────────────────────────────────────
    private void loadVideoFramesAsync() {
        new Thread(() -> {
            try {
                File dir = new File("assets/nobita_frames");
                if (dir.exists()) {
                    File[] files = dir.listFiles((d, name) -> name.startsWith("frame_") && name.endsWith(".jpg"));
                    if (files != null && files.length > 0) {
                        java.util.Arrays.sort(files);
                        for (File f : files) {
                            BufferedImage img = ImageIO.read(f);
                            if (img != null) videoFrames.add(img);
                        }
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    if (!videoFrames.isEmpty()) {
                        statusVideoPill.setText("● VIDEO: " + videoFrames.size() + " FRAMES (20 FPS)");
                        statusVideoPill.setForeground(ACCENT_GREEN);
                        statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                        log("Successfully loaded " + videoFrames.size() + " native frames from Timeline 1.mov into in-applet memory!");
                        videoViewport.repaint();
                    } else {
                        statusVideoPill.setText("● VIDEO: NO FRAMES");
                        statusVideoPill.setForeground(ACCENT_RED);
                        statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_RED, 1, true), new EmptyBorder(3, 8, 3, 8)));
                    }
                });
            } catch (Exception ex) {
                log("[ERR] Failed to load video frames: " + ex.getMessage());
            }
        }, "VideoFrameLoader").start();
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
                if (st.equalsIgnoreCase("OCCUPIED")) roomStateCard.setForeground(ACCENT_GREEN);
                else if (st.equalsIgnoreCase("HYSTERESIS")) roomStateCard.setForeground(ACCENT_AMBER);
                else roomStateCard.setForeground(TEXT_MUTED);
            }
            pirCard.setText(pir ? "3.3 V [HIGH]" : "0.0 V [LOW]");
            pirCard.setForeground(pir ? ACCENT_GREEN : TEXT_MUTED);

            radarCard.setText(String.format(Locale.US, "%.1f%% (%.2fV)", radPct, radV));
            radarCard.setForeground(radPct > 45 ? ACCENT_GREEN : TEXT_MUTED);

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
            if (currentTimelineSec >= VIDEO_DURATION_SEC) {
                currentTimelineSec = VIDEO_DURATION_SEC;
                isPlaying = false;
                playBtn.setText("▶ PLAY VIDEO & SYNC");
                playBtn.setBackground(ACCENT_GREEN);
                log("Video playback and timeline completed (6.00s).");
            }

            // Frame-accurate event trigger at t = 3.0s
            if (currentTimelineSec >= EVENT_TRIGGER_SEC && !eventDispatched) {
                eventDispatched = true;
                injectDepartureStimulus("Bamboo-Copter Window Exit (t = 3.0s in Timeline 1.mov)");
            }

            updateTimelineDisplay();
            videoViewport.repaint();
        });
    }

    private void updateTimelineDisplay() {
        int millis = (int) (currentTimelineSec * 1000);
        timelineSlider.setValue(millis);

        int sec = (int) currentTimelineSec;
        int cs  = (int) ((currentTimelineSec - sec) * 100);
        timeDisplayLbl.setText(String.format(Locale.US, "00:%02d.%02d / 00:06.00", sec, cs));
    }

    private void seekFromSlider() {
        currentTimelineSec = timelineSlider.getValue() / 1000.0;
        if (currentTimelineSec < EVENT_TRIGGER_SEC) {
            eventDispatched = false;
        }
        updateTimelineDisplay();
        videoViewport.repaint();
    }

    private void togglePlay() {
        if (!isPlaying) {
            if (currentTimelineSec >= VIDEO_DURATION_SEC) {
                restartTimeline();
            }
            isPlaying = true;
            playBtn.setText("⏸ PAUSE VIDEO");
            playBtn.setBackground(ACCENT_AMBER);
            playbackTimer.start();
            log("Started video and CPS simulation sync playback...");

            if (currentTimelineSec < EVENT_TRIGGER_SEC) {
                injectOccupiedStimulus("Video Start (Nobita in Room)");
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

    public void injectOccupiedStimulus(String reason) {
        log("▶ [STIMULUS] OCCUPIED applied: " + reason);
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.80);
    }

    public void injectDepartureStimulus(String reason) {
        log("★ [EVENT] NOBITA BAMBOO-COPTER DEPARTURE: " + reason);
        log("  → Driving PIR DATA = 0.0V (Nobita has flown away)");
        log("  → Driving MillimeterWaveRadar = 0.05V (Micro-motion lost)");
        log("  → Sensed by ESP32 MCU on GPIO_12 and GPIO_27");
        log("  → Entering HYSTERESIS / VACANT mode!");

        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
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
