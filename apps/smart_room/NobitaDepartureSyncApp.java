import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.Socket;
import java.util.Locale;

/**
 * NobitaDepartureSyncApp
 * ==============================================================================
 * Cyber-Physical Video Synchronization & Embedded Player Application.
 *
 * Integrated Media Features:
 * - Plays "Timeline 1.mov" directly synchronized with CPS simulation clock!
 * - Video viewport docked right beside the CPS Telemetry HUD & Sensors.
 * - Frame-accurate event trigger at t = 3.000s:
 *     Nobita Bamboo-Copter exit cuts PIR Motion and mmWave Radar.
 * - Streams sensor stimuli to Port 9091 (Sensors Bus).
 * - Real-time ESP32 MCU telemetry received from Port 9090 (Telemetry Bus).
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

    // Timeline & Playback State
    private static final double VIDEO_DURATION_SEC = 6.0;
    private static final double EVENT_TRIGGER_SEC  = 3.0; // Cut moment in Timeline 1.mov

    private volatile boolean isPlaying = false;
    private volatile double currentTimelineSec = 0.0;
    private boolean eventDispatched = false;
    private javax.swing.Timer playbackTimer;

    // Video Subprocess & IPC Controller
    private Process videoProcess;
    private PrintWriter videoOut;
    private BufferedReader videoIn;
    private volatile boolean videoReady = false;

    // UI Elements
    private JLabel statusSensorPill;
    private JLabel statusTelemPill;
    private JLabel statusVideoPill;
    private JLabel timeDisplayLbl;
    private JProgressBar timelineProgressBar;
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
        super("Nobita Departure CPS Video Player & Sync — Timeline 1.mov");
        this.host = host;
        this.sensorPort = sensorPort;
        this.telemetryPort = telemetryPort;

        setupUI();
        initPlaybackTimer();
        connectSockets();
        launchVideoHostPlayer();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 720);
        setMinimumSize(new Dimension(940, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);

        // Window lifecycle handlers
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownVideoPlayer();
            }
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                repositionVideoWindow();
            }
        });

        setVisible(true);
        SwingUtilities.invokeLater(this::repositionVideoWindow);
    }

    public NobitaDepartureSyncApp() {
        this("localhost", CpsDomain.PORT_SENSORS, CpsDomain.PORT_TELEMETRY);
    }

    private void setupUI() {
        setLayout(new BorderLayout(12, 12));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(16, 18, 16, 18));

        // ── TOP: Header Banner ────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(CARD_BG);
        header.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel titleGrp = new JPanel(new GridLayout(2, 1, 0, 3));
        titleGrp.setOpaque(false);
        JLabel titleLbl = new JLabel("🎬 NOBITA DEPARTURE — LIVE VIDEO & CPS SYNCHRONIZER");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_CYAN);

        JLabel subLbl = new JLabel("Synchronized Media: Timeline 1.mov | Departure at t = 3.00s | Sensors: 9091 | Telemetry: 9090");
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

        // ── CENTER: Timeline Controller & Status HUD ─────────────────────────
        JPanel centerPanel = new JPanel(new BorderLayout(14, 14));
        centerPanel.setOpaque(false);

        // Section A: Video Timeline Synchronizer Card
        JPanel timelineCard = new JPanel(new BorderLayout(12, 12));
        timelineCard.setBackground(CARD_BG);
        timelineCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(14, 18, 16, 18)
        ));

        JPanel tlTopRow = new JPanel(new BorderLayout(10, 0));
        tlTopRow.setOpaque(false);

        JLabel tlHeader = new JLabel("TIMELINE 1.MOV REPLAY & SENSOR TRIGGER");
        tlHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        tlHeader.setForeground(TEXT_MAIN);

        timeDisplayLbl = new JLabel("00:00.00 / 00:06.00");
        timeDisplayLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 20));
        timeDisplayLbl.setForeground(ACCENT_AMBER);

        tlTopRow.add(tlHeader, BorderLayout.WEST);
        tlTopRow.add(timeDisplayLbl, BorderLayout.EAST);

        // Progress Bar
        timelineProgressBar = new JProgressBar(0, (int) (VIDEO_DURATION_SEC * 1000));
        timelineProgressBar.setValue(0);
        timelineProgressBar.setPreferredSize(new Dimension(0, 20));
        timelineProgressBar.setBackground(new Color(30, 36, 46));
        timelineProgressBar.setForeground(ACCENT_CYAN);
        timelineProgressBar.setBorder(new LineBorder(BORDER_COLOR, 1, true));

        // Event markers note
        JPanel markerNoteRow = new JPanel(new BorderLayout());
        markerNoteRow.setOpaque(false);
        JLabel markerLbl = new JLabel("▲ 0.0s: Nobita in Room (Presence Active)           ★ 3.0s: Bamboo-Copter Exit! (Cut Sensors)           ▲ 6.0s: Video End");
        markerLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        markerLbl.setForeground(new Color(110, 120, 135));
        markerNoteRow.add(markerLbl, BorderLayout.WEST);

        // Action Buttons Row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btnRow.setOpaque(false);

        playBtn = createStyledButton("▶ PLAY VIDEO & SYNC", ACCENT_GREEN);
        playBtn.addActionListener(e -> togglePlay());

        restartBtn = createStyledButton("↺ RESTART (t=0s)", new Color(75, 85, 99));
        restartBtn.addActionListener(e -> restartTimeline());

        sendOccupiedBtn = createStyledButton("FORCE OCCUPIED", new Color(37, 99, 235));
        sendOccupiedBtn.addActionListener(e -> injectOccupiedStimulus("Manual Trigger"));

        sendDepartedBtn = createStyledButton("TRIGGER 3.0s EXIT", ACCENT_RED);
        sendDepartedBtn.addActionListener(e -> injectDepartureStimulus("Manual Trigger"));

        btnRow.add(playBtn);
        btnRow.add(restartBtn);
        btnRow.add(sendOccupiedBtn);
        btnRow.add(sendDepartedBtn);

        JPanel tlContent = new JPanel();
        tlContent.setLayout(new BoxLayout(tlContent, BoxLayout.Y_AXIS));
        tlContent.setOpaque(false);
        tlContent.add(tlTopRow);
        tlContent.add(Box.createVerticalStrut(10));
        tlContent.add(timelineProgressBar);
        tlContent.add(Box.createVerticalStrut(4));
        tlContent.add(markerNoteRow);
        tlContent.add(Box.createVerticalStrut(12));
        tlContent.add(btnRow);

        timelineCard.add(tlContent, BorderLayout.CENTER);

        // Section B: Live Telemetry Status Cards (From Port 9090)
        JPanel telemGrid = new JPanel(new GridLayout(1, 4, 10, 0));
        telemGrid.setOpaque(false);

        roomStateCard = createMetricCard("ROOM STATE", "VACANT", ACCENT_AMBER, telemGrid);
        pirCard       = createMetricCard("PIR SENSOR (GPIO_12)", "0.0 V [LOW]", TEXT_MUTED, telemGrid);
        radarCard     = createMetricCard("RADAR CONF (GPIO_27)", "0.0% (0.00V)", TEXT_MUTED, telemGrid);
        hystCard      = createMetricCard("HYSTERESIS REMAIN", "0 s", ACCENT_CYAN, telemGrid);

        centerPanel.add(timelineCard, BorderLayout.NORTH);
        centerPanel.add(telemGrid, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // ── BOTTOM: Real-time Event Log ───────────────────────────────────────
        JPanel logCard = new JPanel(new BorderLayout(8, 8));
        logCard.setBackground(CARD_BG);
        logCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));
        logCard.setPreferredSize(new Dimension(0, 170));

        JLabel logHeader = new JLabel("SYNCHRONIZATION & SENSOR EVENT LOG");
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
        add(logCard, BorderLayout.SOUTH);
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

    // ─── Video Host Subprocess Launcher ──────────────────────────────────────
    private void launchVideoHostPlayer() {
        new Thread(() -> {
            try {
                File exe = new File("VideoHost.exe");
                File video = new File("Timeline 1.mov");
                if (!video.exists()) {
                    log("[ERR] Timeline 1.mov not found in project directory!");
                    return;
                }

                Point pt = getLocationOnScreen();
                int vx = Math.max(20, pt.x + getWidth() + 12);
                int vy = Math.max(20, pt.y);

                ProcessBuilder pb = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    video.getAbsolutePath(),
                    String.valueOf(vx),
                    String.valueOf(vy),
                    "640",
                    "360"
                );
                videoProcess = pb.start();
                videoOut = new PrintWriter(new OutputStreamWriter(videoProcess.getOutputStream()), true);
                videoIn  = new BufferedReader(new InputStreamReader(videoProcess.getInputStream()));

                String line;
                while ((line = videoIn.readLine()) != null) {
                    final String msg = line;
                    if (msg.startsWith("MEDIA_OPENED")) {
                        videoReady = true;
                        SwingUtilities.invokeLater(() -> {
                            statusVideoPill.setText("● VIDEO READY");
                            statusVideoPill.setForeground(ACCENT_GREEN);
                            statusVideoPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                        });
                        log("Video player loaded 'Timeline 1.mov' (1080p, 6.00s)");
                    } else if (msg.startsWith("MEDIA_FAILED")) {
                        log("[ERR] Video player error: " + msg);
                    }
                }
            } catch (Exception ex) {
                log("[WARN] Unable to launch VideoHost: " + ex.getMessage());
            }
        }, "VideoHostController").start();
    }

    private void repositionVideoWindow() {
        if (videoOut != null && videoReady && isShowing()) {
            try {
                Point pt = getLocationOnScreen();
                int vx = pt.x + getWidth() + 10;
                int vy = pt.y;
                videoOut.println("MOVE:" + vx + "," + vy + ",640,360");
            } catch (Exception ignored) {}
        }
    }

    private void sendVideoCommand(String cmd) {
        if (videoOut != null && videoReady) {
            videoOut.println(cmd);
        }
    }

    private void shutdownVideoPlayer() {
        sendVideoCommand("QUIT");
        try {
            if (videoProcess != null) videoProcess.destroy();
        } catch (Exception ignored) {}
    }

    // ─── Network Communications ──────────────────────────────────────────────
    private void connectSockets() {
        // Thread 1: Port 9091 (Sensors Bus Client)
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

        // Thread 2: Port 9090 (Telemetry Receiver)
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

    // ─── Playback & Stimulus Logic ───────────────────────────────────────────
    private void initPlaybackTimer() {
        // Runs at 20 Hz (50 ms tick) for smooth video sync and progress bar
        playbackTimer = new javax.swing.Timer(50, e -> {
            if (!isPlaying) return;

            currentTimelineSec += 0.050;
            if (currentTimelineSec >= VIDEO_DURATION_SEC) {
                currentTimelineSec = VIDEO_DURATION_SEC;
                isPlaying = false;
                playBtn.setText("▶ PLAY VIDEO & SYNC");
                playBtn.setBackground(ACCENT_GREEN);
                sendVideoCommand("PAUSE");
                log("Video playback and timeline completed (6.00s).");
            }

            // Check for event trigger at exactly 3.0s (Bamboo-copter departure)
            if (currentTimelineSec >= EVENT_TRIGGER_SEC && !eventDispatched) {
                eventDispatched = true;
                injectDepartureStimulus("Bamboo-Copter Exit (t = 3.0s in Timeline 1.mov)");
            }

            updateTimelineDisplay();
        });
    }

    private void updateTimelineDisplay() {
        int millis = (int) (currentTimelineSec * 1000);
        timelineProgressBar.setValue(millis);

        int sec = (int) currentTimelineSec;
        int cs  = (int) ((currentTimelineSec - sec) * 100);
        timeDisplayLbl.setText(String.format(Locale.US, "00:%02d.%02d / 00:06.00", sec, cs));
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

            sendVideoCommand("SEEK:" + String.format(Locale.US, "%.2f", currentTimelineSec));
            sendVideoCommand("PLAY");
            log("Started video and CPS simulation sync playback...");

            if (currentTimelineSec < EVENT_TRIGGER_SEC) {
                injectOccupiedStimulus("Video Start (Nobita in Room)");
            }
        } else {
            isPlaying = false;
            playBtn.setText("▶ RESUME VIDEO");
            playBtn.setBackground(ACCENT_GREEN);
            playbackTimer.stop();
            sendVideoCommand("PAUSE");
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

        sendVideoCommand("STOP");
        sendVideoCommand("SEEK:0");
        updateTimelineDisplay();
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
