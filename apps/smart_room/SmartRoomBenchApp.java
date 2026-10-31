import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * SmartRoomBenchApp
 * ==============================================================================
 * Cyber-Physical Realistic Smart Room & Test-Bench Architectural Simulator.
 *
 * Visual & Physical Model:
 * 1. Realistic Human Character (Anatomical proportions & realistic actions):
 *    - Entering through doorway with bag/stride animation.
 *    - Seated at modern study desk typing on laptop (sedentary studying).
 *    - Resting in bed with blanket (breathing chest-wall micro-motion).
 *    - Departing room through doorway.
 *
 * 2. High-Fidelity Architectural Room (Isometric cutaway bench rig):
 *    - Warm hardwood plank flooring with perspective joinery.
 *    - Study desk with metallic legs, ergonomic chair, laptop with screen glow.
 *    - Modern platform bed with headboard, mattress, pillow, and quilted blanket.
 *    - Framed panoramic window with outside daylight/night transition.
 *    - Architectural door frame with magnetic reed contact switch.
 *
 * 3. Realistic Overhead LED Luminaire:
 *    - Multi-diode COB LED ceiling luminaire with metallic bezel.
 *    - Dynamic ambient room illumination: Light cones, surface highlights,
 *      smooth dimming (100% active study -> 20% warm night lamp -> 0% vacant dark).
 *
 * 4. Realistic Wall-Mounted Split Air Conditioner (HVAC Unit):
 *    - Slender curved indoor chassis with intake grille.
 *    - Motorized swing louver flap that opens when cooling is active.
 *    - Cascading animated airflow particle streamlines and cooling breeze waves.
 *    - Digital LED temperature display (e.g. 23°C COOL) and status LEDs.
 *
 * 5. Cyber-Physical Bus Integration:
 *    - Port 9091: Transmits physical pin stimuli (PIR, mmWave Radar, Door Reed).
 *    - Port 9090: Receives real-time MCU telemetry JSON stream.
 *    - Seamless internal simulation fallback if socket is offline.
 * ==============================================================================
 */
public class SmartRoomBenchApp extends JFrame {

    // Typography & Color Palette
    private static final String FONT_FAMILY   = "Segoe UI Emoji";
    private static final Color BG_DARK        = new Color(8, 11, 18);
    private static final Color CARD_BG        = new Color(16, 22, 34);
    private static final Color BORDER_COLOR   = new Color(45, 55, 75);
    private static final Color ACCENT_CYAN    = new Color(56, 189, 248);
    private static final Color ACCENT_GREEN   = new Color(34, 197, 94);
    private static final Color ACCENT_AMBER   = new Color(251, 191, 36);
    private static final Color ACCENT_RED     = new Color(244, 63, 94);
    private static final Color ACCENT_PURPLE  = new Color(192, 132, 252);
    private static final Color TEXT_MAIN      = new Color(248, 250, 252);
    private static final Color TEXT_MUTED     = new Color(148, 163, 184);

    // Network Ports (Fixed 3-Port Standard)
    private final String host;
    private final int sensorPort;     // Port 9091
    private final int telemetryPort;  // Port 9090

    private Socket sensorSocket;
    private PrintWriter sensorOut;
    private BufferedReader sensorIn;
    private volatile boolean sensorConnected = false;

    private Socket telemetrySocket;
    private BufferedReader telemetryIn;
    private volatile boolean telemetryConnected = false;

    // ─── Operational Scenarios ───────────────────────────────────────────────
    public enum Scenario {
        ENTRY(
            "🚶 1. Person Enters Room (Daylight Entry & Luminaire Activation)",
            10.0,
            "Occupant opens door and steps in; PIR trips, LED luminaire turns 100% ON."
        ),
        STUDY(
            "💻 2. Sedentary Desk Study (Micro-motion & Comfort Cooling)",
            12.0,
            "Occupant sits at desk typing. PIR is quiet; 60GHz mmWave radar detects vital presence."
        ),
        SLEEP(
            "🛏️ 3. Person Resting in Bed (Automatic Dimming & Quiet AC Mode)",
            12.0,
            "Occupant sleeps in bed. LED luminaire dims away; mmWave radar detects respiration."
        ),
        DEPARTURE(
            "🚪 4. Person Leaves Room (Door Latch & Hysteresis Countdown)",
            10.0,
            "Occupant leaves and closes door. System enters 30s grace window before full shutdown."
        );

        public final String title;
        public final double durationSec;
        public final String description;

        Scenario(String title, double durationSec, String description) {
            this.title = title;
            this.durationSec = durationSec;
            this.description = description;
        }

        @Override
        public String toString() { return title; }
    }

    private Scenario currentScenario = Scenario.ENTRY;

    // Occupant States
    public enum OccupantPose {
        ENTERING_DOOR,
        AT_DESK_STUDYING,
        IN_BED_SLEEPING,
        EXITING_DOOR,
        ABSENT
    }

    private OccupantPose currentPose = OccupantPose.ENTERING_DOOR;

    // Simulation Timeline & Animation State
    private volatile boolean isPlaying = true;
    private volatile double currentTimelineSec = 0.0;
    private double playbackSpeed = 1.0;
    private javax.swing.Timer animTimer;
    private long lastTickNano = 0;

    // Dynamic Physics & Lighting State
    private float ledBrightness = 1.0f;     // 0.0f (dark) to 1.0f (full bright)
    private float targetBrightness = 1.0f;
    private boolean acCompressorOn = true;
    private float acLouverAngle = 45f;      // 0 = closed, 45 = open cooling
    private float fanPhase = 0.0f;
    private final List<AirflowParticle> airParticles = new ArrayList<>();

    // Radar Respiration Waveform
    private final float[] radarWaveform = new float[60];
    private int waveIndex = 0;

    // Telemetry Registers
    private String roomState = "OCCUPIED";
    private boolean pirSignal = true;
    private double radarSignalVolts = 2.85;
    private double radarPercent = 86.4;
    private boolean doorOpenSignal = false;
    private boolean lightRelayState = true;
    private boolean hvacRelayState = true;
    private long hystRemainingSec = 0;

    // UI Components
    private RoomCanvasPanel roomCanvas;
    private JComboBox<Scenario> scenarioCombo;
    private JLabel statusSensorPill;
    private JLabel statusTelemPill;
    private JLabel timeDisplayLbl;
    private JSlider timelineSlider;
    private JButton playBtn;
    private JButton restartBtn;
    private JButton speedBtn;

    // Telemetry HUD Cards
    private JLabel roomStateCard;
    private JLabel pirCard;
    private JLabel radarCard;
    private JLabel acCard;
    private JLabel ledCard;
    private JLabel hystCard;
    private JTextArea logArea;

    public SmartRoomBenchApp(String host, int sensorPort, int telemetryPort) {
        super("CPS Smart Room Bench — Realistic Architectural Simulator & Synchronizer");
        this.host = host;
        this.sensorPort = sensorPort;
        this.telemetryPort = telemetryPort;

        initAirflowParticles();
        setupUI();
        initAnimationTimer();
        connectSockets();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1360, 840);
        setMinimumSize(new Dimension(1160, 720));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);
    }

    public SmartRoomBenchApp() {
        this("localhost", 9091, 9090);
    }

    // ─── UI Architecture & Layout ────────────────────────────────────────────
    private void setupUI() {
        setLayout(new BorderLayout(14, 14));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(14, 18, 14, 18));

        // ── TOP: Header Banner ────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(CARD_BG);
        header.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel titleGrp = new JPanel(new GridLayout(2, 1, 0, 3));
        titleGrp.setOpaque(false);
        JLabel titleLbl = new JLabel("CPS SMART ROOM TEST-BENCH — REALISTIC ARCHITECTURAL SIMULATOR");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_CYAN);

        JLabel subLbl = new JLabel("Dual-Sensor Fusion (PIR + 60GHz mmWave Radar) | Realistic LED Lighting & Split AC | Port 9091 Sensors | Port 9090 Telemetry");
        subLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        subLbl.setForeground(TEXT_MUTED);
        titleGrp.add(titleLbl);
        titleGrp.add(subLbl);

        JPanel pillsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        pillsPanel.setOpaque(false);
        statusSensorPill = createPill("SENSORS BUS: 9091", ACCENT_AMBER);
        statusTelemPill  = createPill("TELEMETRY: 9090", ACCENT_AMBER);
        pillsPanel.add(statusSensorPill);
        pillsPanel.add(statusTelemPill);

        header.add(titleGrp, BorderLayout.WEST);
        header.add(pillsPanel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ── CENTER: Left = Interactive Visual Room | Right = Telemetry HUD ────
        JPanel mainSplit = new JPanel(new BorderLayout(14, 14));
        mainSplit.setOpaque(false);

        // LEFT CARD: Room Canvas + Transport Bar
        JPanel leftRoomCard = new JPanel(new BorderLayout(10, 10));
        leftRoomCard.setBackground(CARD_BG);
        leftRoomCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        // Top Row: Scenario Chooser (Dropdown Box — Emojis preserved inside dropdown!)
        JPanel scenarioBar = new JPanel(new BorderLayout(10, 0));
        scenarioBar.setOpaque(false);
        JLabel scLbl = new JLabel("ACTIVE SCENARIO:");
        scLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        scLbl.setForeground(ACCENT_AMBER);

        scenarioCombo = new JComboBox<>(Scenario.values());
        scenarioCombo.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        scenarioCombo.setBackground(new Color(10, 14, 22));
        scenarioCombo.setForeground(TEXT_MAIN);
        scenarioCombo.setFocusable(false);
        scenarioCombo.setBorder(new LineBorder(BORDER_COLOR, 1, true));
        scenarioCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
                lbl.setBorder(new EmptyBorder(6, 10, 6, 10));
                if (isSelected) {
                    lbl.setBackground(new Color(37, 99, 235)); // High-contrast Royal Blue (#2563EB)
                    lbl.setForeground(Color.WHITE);
                } else {
                    lbl.setBackground(new Color(24, 32, 47));  // Slate card (#18202F)
                    lbl.setForeground(TEXT_MAIN);              // Crisp white
                }
                return lbl;
            }
        });
        scenarioCombo.addActionListener(e -> {
            Scenario selected = (Scenario) scenarioCombo.getSelectedItem();
            if (selected != null && selected != currentScenario) {
                switchScenario(selected);
            }
        });

        scenarioBar.add(scLbl, BorderLayout.WEST);
        scenarioBar.add(scenarioCombo, BorderLayout.CENTER);
        leftRoomCard.add(scenarioBar, BorderLayout.NORTH);

        // Center: Isometric Canvas
        roomCanvas = new RoomCanvasPanel();
        leftRoomCard.add(roomCanvas, BorderLayout.CENTER);

        // Bottom: Transport Bar & Slider
        JPanel transportBar = new JPanel(new BorderLayout(8, 6));
        transportBar.setOpaque(false);

        timelineSlider = new JSlider(0, (int) (currentScenario.durationSec * 1000), 0);
        timelineSlider.setBackground(CARD_BG);
        timelineSlider.setForeground(ACCENT_CYAN);
        timelineSlider.setFocusable(false);
        timelineSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { seekFromSlider(); }
        });
        timelineSlider.addChangeListener(e -> {
            if (timelineSlider.getValueIsAdjusting()) seekFromSlider();
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);

        playBtn = createStyledButton("PAUSE SIM", ACCENT_AMBER, Color.BLACK);
        playBtn.addActionListener(e -> togglePlay());

        restartBtn = createStyledButton("RESTART", new Color(55, 65, 81), TEXT_MAIN);
        restartBtn.addActionListener(e -> restartTimeline());

        speedBtn = createStyledButton("SPEED: 1.0x", new Color(30, 41, 59), ACCENT_CYAN);
        speedBtn.addActionListener(e -> cycleSpeed());

        timeDisplayLbl = new JLabel("00:00.00 / 00:10.00");
        timeDisplayLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        timeDisplayLbl.setForeground(ACCENT_AMBER);

        btnRow.add(playBtn);
        btnRow.add(restartBtn);
        btnRow.add(speedBtn);
        btnRow.add(Box.createHorizontalStrut(12));
        btnRow.add(timeDisplayLbl);

        transportBar.add(timelineSlider, BorderLayout.NORTH);
        transportBar.add(btnRow, BorderLayout.SOUTH);
        leftRoomCard.add(transportBar, BorderLayout.SOUTH);

        mainSplit.add(leftRoomCard, BorderLayout.CENTER);

        // RIGHT PANEL: CPS Telemetry HUD & Event Log
        JPanel rightPanel = new JPanel(new BorderLayout(12, 12));
        rightPanel.setOpaque(false);
        rightPanel.setPreferredSize(new Dimension(500, 0));

        // Upper: Telemetry Card
        JPanel telemetryCard = new JPanel(new BorderLayout(10, 10));
        telemetryCard.setBackground(CARD_BG);
        telemetryCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JLabel telemTitle = new JLabel("CPS REAL-TIME TELEMETRY HUD (PORT 9090)");
        telemTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        telemTitle.setForeground(ACCENT_CYAN);
        telemetryCard.add(telemTitle, BorderLayout.NORTH);

        JPanel metricsGrid = new JPanel(new GridLayout(3, 2, 8, 8));
        metricsGrid.setOpaque(false);

        roomStateCard = createMetricCard("ROOM STATE", "OCCUPIED", ACCENT_GREEN, metricsGrid);
        hystCard      = createMetricCard("HYSTERESIS REMAIN", "0 s", ACCENT_CYAN, metricsGrid);
        pirCard       = createMetricCard("PIR MOTION (GPIO 12)", "3.30 V [HIGH]", ACCENT_GREEN, metricsGrid);
        radarCard     = createMetricCard("60GHz RADAR (GPIO 27)", "2.85 V [86%]", ACCENT_GREEN, metricsGrid);
        ledCard       = createMetricCard("LED LUMINAIRE (GPIO 21)", "100% BRIGHT", ACCENT_AMBER, metricsGrid);
        acCard        = createMetricCard("HVAC COOLING (GPIO 22)", "23°C ACTIVE", ACCENT_CYAN, metricsGrid);

        telemetryCard.add(metricsGrid, BorderLayout.CENTER);

        // Manual Injection Override Buttons
        JPanel manualBtnRow = new JPanel(new GridLayout(1, 2, 8, 0));
        manualBtnRow.setOpaque(false);
        JButton btnForceOccupied = createStyledButton("FORCE OCCUPIED", new Color(14, 165, 233), Color.WHITE);
        btnForceOccupied.addActionListener(e -> applyManualOccupied());

        JButton btnForceVacant = createStyledButton("FORCE VACANT", ACCENT_RED, Color.WHITE);
        btnForceVacant.addActionListener(e -> applyManualVacant());

        manualBtnRow.add(btnForceOccupied);
        manualBtnRow.add(btnForceVacant);
        telemetryCard.add(manualBtnRow, BorderLayout.SOUTH);

        rightPanel.add(telemetryCard, BorderLayout.NORTH);

        // Lower: Real-time Event Log
        JPanel logCard = new JPanel(new BorderLayout(8, 8));
        logCard.setBackground(CARD_BG);
        logCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel logHeader = new JLabel("SENSOR BUS & ACTUATOR LOG");
        logHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        logHeader.setForeground(TEXT_MAIN);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(8, 12, 20));
        logArea.setForeground(new Color(220, 252, 231));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        logArea.setCaretColor(Color.WHITE);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new LineBorder(BORDER_COLOR, 1));

        logCard.add(logHeader, BorderLayout.NORTH);
        logCard.add(scroll, BorderLayout.CENTER);
        rightPanel.add(logCard, BorderLayout.CENTER);

        mainSplit.add(rightPanel, BorderLayout.EAST);
        add(mainSplit, BorderLayout.CENTER);
    }

    // ─── Scenario Switching & Transport Controls ─────────────────────────────
    private void switchScenario(Scenario s) {
        currentScenario = s;
        currentTimelineSec = 0.0;
        timelineSlider.setMaximum((int) (s.durationSec * 1000));
        timelineSlider.setValue(0);
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("Switched Scenario: " + s.title);
        log(s.description);
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        updateScenarioState(0.0);
        roomCanvas.repaint();
    }

    private void togglePlay() {
        isPlaying = !isPlaying;
        if (isPlaying) {
            updateButtonAppearance(playBtn, "PAUSE SIM", ACCENT_AMBER);
            lastTickNano = System.nanoTime();
        } else {
            updateButtonAppearance(playBtn, "RESUME SIM", ACCENT_GREEN);
        }
    }

    private void restartTimeline() {
        currentTimelineSec = 0.0;
        timelineSlider.setValue(0);
        updateScenarioState(0.0);
        roomCanvas.repaint();
        log("Reset simulation timeline to 00:00.00.");
    }

    private void cycleSpeed() {
        if (playbackSpeed == 1.0) playbackSpeed = 2.0;
        else if (playbackSpeed == 2.0) playbackSpeed = 0.5;
        else playbackSpeed = 1.0;
        speedBtn.setText(String.format(Locale.US, "SPEED: %.1fx", playbackSpeed));
    }

    private void seekFromSlider() {
        currentTimelineSec = timelineSlider.getValue() / 1000.0;
        updateScenarioState(currentTimelineSec);
        roomCanvas.repaint();
    }

    // ─── Particle Dynamics & Animation Loop ──────────────────────────────────
    private static class AirflowParticle {
        float x, y;
        float vx, vy;
        float life;
        float maxLife;
    }

    private void initAirflowParticles() {
        airParticles.clear();
        for (int i = 0; i < 35; i++) {
            AirflowParticle p = new AirflowParticle();
            resetParticle(p);
            p.life = (float) (Math.random() * p.maxLife);
            airParticles.add(p);
        }
    }

    private void resetParticle(AirflowParticle p) {
        // Starts near AC blower outlet (x: 480-570, y: 130-145)
        p.x = 480f + (float) (Math.random() * 90f);
        p.y = 138f + (float) (Math.random() * 8f);
        p.vx = -1.5f - (float) (Math.random() * 1.8f);
        p.vy = 1.6f + (float) (Math.random() * 2.2f);
        p.maxLife = 40f + (float) (Math.random() * 30f);
        p.life = p.maxLife;
    }

    private void initAnimationTimer() {
        lastTickNano = System.nanoTime();
        animTimer = new javax.swing.Timer(33, e -> {
            long now = System.nanoTime();
            double dt = (now - lastTickNano) / 1_000_000_000.0;
            lastTickNano = now;
            if (dt > 0.1) dt = 0.033; // Clamp hiccup

            if (isPlaying) {
                currentTimelineSec += dt * playbackSpeed;
                if (currentTimelineSec >= currentScenario.durationSec) {
                    currentTimelineSec = currentScenario.durationSec;
                    isPlaying = false;
                    playBtn.setText("▶ RESTART SIM");
                    playBtn.setBackground(ACCENT_GREEN);
                    playBtn.setForeground(Color.BLACK);
                }
                timelineSlider.setValue((int) (currentTimelineSec * 1000));
                updateScenarioState(currentTimelineSec);
            }

            // Smooth lighting interpolation
            ledBrightness += (targetBrightness - ledBrightness) * 0.12f;

            // AC Fan & Airflow Dynamics
            if (acCompressorOn) {
                acLouverAngle += (45f - acLouverAngle) * 0.1f;
                fanPhase += 0.25f;
                for (AirflowParticle p : airParticles) {
                    p.x += p.vx;
                    p.y += p.vy;
                    p.life -= 1.0f;
                    if (p.life <= 0 || p.y > 420 || p.x < 180) {
                        resetParticle(p);
                    }
                }
            } else {
                acLouverAngle += (0f - acLouverAngle) * 0.08f;
                for (AirflowParticle p : airParticles) {
                    p.life -= 2.0f;
                }
            }

            // Radar Vital Breathing Waveform update
            double freq = (currentPose == OccupantPose.IN_BED_SLEEPING) ? 0.22 : 0.40;
            double amp = (currentPose == OccupantPose.ABSENT) ? 0.04 : (currentPose == OccupantPose.IN_BED_SLEEPING ? 0.45 : 0.85);
            radarWaveform[waveIndex] = (float) (amp * Math.sin(System.currentTimeMillis() * 0.006 * freq));
            waveIndex = (waveIndex + 1) % radarWaveform.length;

            updateTimeLabel();
            roomCanvas.repaint();
        });
        animTimer.start();
    }

    private void updateTimeLabel() {
        int curSec = (int) currentTimelineSec;
        int curCs  = (int) ((currentTimelineSec - curSec) * 100);
        int totSec = (int) currentScenario.durationSec;
        timeDisplayLbl.setText(String.format(Locale.US, "00:%02d.%02d / 00:%02d.00", curSec, curCs, totSec));
    }

    // ─── Scenario-Driven Physical State Evolution ─────────────────────────────
    private void updateScenarioState(double t) {
        switch (currentScenario) {
            case ENTRY -> {
                if (t < 2.5) {
                    currentPose = OccupantPose.ENTERING_DOOR;
                    doorOpenSignal = true;
                    pirSignal = true;
                    radarSignalVolts = 1.8;
                    radarPercent = 55.0;
                    targetBrightness = 0.95f;
                    acCompressorOn = true;
                    roomState = "OCCUPIED";
                } else if (t < 6.0) {
                    currentPose = OccupantPose.ENTERING_DOOR;
                    doorOpenSignal = false;
                    pirSignal = true;
                    radarSignalVolts = 2.90;
                    radarPercent = 88.0;
                    targetBrightness = 1.0f;
                    acCompressorOn = true;
                    roomState = "OCCUPIED";
                } else {
                    currentPose = OccupantPose.AT_DESK_STUDYING;
                    doorOpenSignal = false;
                    pirSignal = false; // Gross motion stops
                    radarSignalVolts = 2.80; // High radar confidence
                    radarPercent = 85.0;
                    targetBrightness = 1.0f;
                    acCompressorOn = true;
                    roomState = "OCCUPIED";
                }
            }
            case STUDY -> {
                currentPose = OccupantPose.AT_DESK_STUDYING;
                doorOpenSignal = false;
                pirSignal = (t > 4.0 && t < 5.5); // Occasional typing/reaching gesture
                radarSignalVolts = 2.75;
                radarPercent = 83.5;
                targetBrightness = 1.0f;
                acCompressorOn = true;
                roomState = "OCCUPIED";
            }
            case SLEEP -> {
                if (t < 3.5) {
                    currentPose = OccupantPose.IN_BED_SLEEPING;
                    doorOpenSignal = false;
                    pirSignal = true; // Still moving into bed
                    radarSignalVolts = 2.60;
                    radarPercent = 78.0;
                    targetBrightness = 0.85f;
                    acCompressorOn = true;
                    roomState = "OCCUPIED";
                } else {
                    currentPose = OccupantPose.IN_BED_SLEEPING;
                    doorOpenSignal = false;
                    pirSignal = false; // Motion ceases completely
                    radarSignalVolts = 1.95; // 60GHz Radar detects chest respiration
                    radarPercent = 59.2;
                    targetBrightness = 0.18f; // Main lights dim down to night ambiance
                    acCompressorOn = true; // Quiet AC mode
                    roomState = "SLEEP";
                }
            }
            case DEPARTURE -> {
                if (t < 3.0) {
                    currentPose = OccupantPose.EXITING_DOOR;
                    doorOpenSignal = true;
                    pirSignal = true;
                    radarSignalVolts = 2.20;
                    radarPercent = 66.0;
                    targetBrightness = 1.0f;
                    acCompressorOn = true;
                    roomState = "OCCUPIED";
                } else if (t < 8.0) {
                    currentPose = OccupantPose.ABSENT;
                    doorOpenSignal = false;
                    pirSignal = false;
                    radarSignalVolts = 0.05;
                    radarPercent = 1.5;
                    targetBrightness = 0.0f;
                    acCompressorOn = false;
                    roomState = "HYSTERESIS";
                    hystRemainingSec = (long) Math.max(0, 30 - (t - 3.0) * 6);
                } else {
                    currentPose = OccupantPose.ABSENT;
                    doorOpenSignal = false;
                    pirSignal = false;
                    radarSignalVolts = 0.02;
                    radarPercent = 0.0;
                    targetBrightness = 0.0f;
                    acCompressorOn = false;
                    roomState = "VACANT";
                    hystRemainingSec = 0;
                }
            }
        }

        // Push pin stimuli across socket
        sendSensorPin("PIR_MotionSensor", "DATA", pirSignal ? 3.3 : 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", radarSignalVolts);
        sendSensorPin("MagneticDoorSwitch", "CONTACT", doorOpenSignal ? 3.3 : 0.0);

        updateHUD();
    }

    private void updateHUD() {
        roomStateCard.setText(roomState);
        if ("OCCUPIED".equalsIgnoreCase(roomState)) roomStateCard.setForeground(ACCENT_GREEN);
        else if ("SLEEP".equalsIgnoreCase(roomState)) roomStateCard.setForeground(ACCENT_PURPLE);
        else if ("HYSTERESIS".equalsIgnoreCase(roomState)) roomStateCard.setForeground(ACCENT_AMBER);
        else roomStateCard.setForeground(TEXT_MUTED);

        pirCard.setText(pirSignal ? "3.30 V [HIGH]" : "0.00 V [LOW]");
        pirCard.setForeground(pirSignal ? ACCENT_GREEN : TEXT_MUTED);

        radarCard.setText(String.format(Locale.US, "%.2f V [%.0f%%]", radarSignalVolts, radarPercent));
        radarCard.setForeground(radarPercent > 35 ? (roomState.equals("SLEEP") ? ACCENT_PURPLE : ACCENT_GREEN) : TEXT_MUTED);

        hystCard.setText(hystRemainingSec + " s");
        hystCard.setForeground(hystRemainingSec > 0 ? ACCENT_AMBER : TEXT_MUTED);

        int ledPct = (int) (ledBrightness * 100);
        ledCard.setText(ledPct + "% BRIGHT");
        ledCard.setForeground(ledPct > 50 ? ACCENT_AMBER : (ledPct > 0 ? new Color(254, 215, 170) : TEXT_MUTED));

        acCard.setText(acCompressorOn ? "23°C ACTIVE" : "STANDBY (OFF)");
        acCard.setForeground(acCompressorOn ? ACCENT_CYAN : TEXT_MUTED);
    }

    private void applyManualOccupied() {
        log("[MANUAL] Injected Occupancy Override");
        pirSignal = true;
        radarSignalVolts = 2.85;
        targetBrightness = 1.0f;
        acCompressorOn = true;
        roomState = "OCCUPIED";
        sendSensorPin("PIR_MotionSensor", "DATA", 3.3);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 2.85);
        updateHUD();
        roomCanvas.repaint();
    }

    private void applyManualVacant() {
        log("[MANUAL] Injected Vacancy Override");
        pirSignal = false;
        radarSignalVolts = 0.05;
        targetBrightness = 0.0f;
        acCompressorOn = false;
        roomState = "VACANT";
        sendSensorPin("PIR_MotionSensor", "DATA", 0.0);
        sendSensorPin("MillimeterWaveRadar", "PRESENCE_SIG", 0.05);
        updateHUD();
        roomCanvas.repaint();
    }

    // ─── Network Socket Communications ───────────────────────────────────────
    private void connectSockets() {
        // Port 9091 (Physical Sensor Bus)
        new Thread(() -> {
            try {
                sensorSocket = new Socket(host, sensorPort);
                sensorOut = new PrintWriter(new OutputStreamWriter(sensorSocket.getOutputStream()), true);
                sensorIn  = new BufferedReader(new InputStreamReader(sensorSocket.getInputStream()));
                sensorConnected = true;

                SwingUtilities.invokeLater(() -> {
                    statusSensorPill.setText("SENSORS: 9091");
                    statusSensorPill.setForeground(ACCENT_GREEN);
                    statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                });
                log("Connected to Physical Sensor Bus on " + host + ":" + sensorPort);
            } catch (Exception ex) {
                sensorConnected = false;
                SwingUtilities.invokeLater(() -> {
                    statusSensorPill.setText("SENSORS: SIM ONLY");
                    statusSensorPill.setForeground(ACCENT_AMBER);
                    statusSensorPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_AMBER, 1, true), new EmptyBorder(3, 8, 3, 8)));
                });
                log("[INFO] Sensors Bus socket offline. Operating in self-contained realistic simulation mode.");
            }
        }, "SensorsBusThread").start();

        // Port 9090 (Telemetry Stream)
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (!telemetryConnected) {
                    try {
                        telemetrySocket = new Socket(host, telemetryPort);
                        telemetryIn = new BufferedReader(new InputStreamReader(telemetrySocket.getInputStream()));
                        telemetryConnected = true;
                        SwingUtilities.invokeLater(() -> {
                            statusTelemPill.setText("TELEMETRY: 9090");
                            statusTelemPill.setForeground(ACCENT_GREEN);
                            statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_GREEN, 1, true), new EmptyBorder(3, 8, 3, 8)));
                        });
                        log("Connected to MCU Wi-Fi Telemetry on " + host + ":" + telemetryPort);
                    } catch (Exception e) {
                        telemetryConnected = false;
                        SwingUtilities.invokeLater(() -> {
                            statusTelemPill.setText("TELEMETRY: SIM");
                            statusTelemPill.setForeground(ACCENT_AMBER);
                            statusTelemPill.setBorder(new CompoundBorder(new LineBorder(ACCENT_AMBER, 1, true), new EmptyBorder(3, 8, 3, 8)));
                        });
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
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
        }, "TelemetryBusThread").start();
    }

    private void parseTelemetry(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) return;
        String st = extractJsonVal(json, "state");
        if (!st.isEmpty()) roomState = st;
        lightRelayState = extractJsonBool(json, "light_relay", lightRelayState);
        hvacRelayState  = extractJsonBool(json, "hvac_relay", hvacRelayState);
        SwingUtilities.invokeLater(this::updateHUD);
    }

    private void sendSensorPin(String board, String pin, double voltage) {
        if (sensorOut != null && sensorConnected) {
            String cmd = String.format(Locale.US, "SET %s %s %.3f", board, pin, voltage);
            sensorOut.println(cmd);
        }
    }

    // ─── High-Craft Architectural Canvas (The Room Visualizer) ───────────────
    private class RoomCanvasPanel extends JPanel {
        public RoomCanvasPanel() {
            setBackground(new Color(10, 14, 22));
            setBorder(new LineBorder(BORDER_COLOR, 1, true));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();

            // Coordinate offsets to center the room
            int ox = w / 2 - 340;
            int oy = h / 2 - 230;

            // 1. Outside Sky Background (Seen through window)
            drawWindowView(g2, ox + 180, oy + 70, 140, 110);

            // 2. Room Walls & Perspective Geometry
            drawRoomShell(g2, ox, oy);

            // 3. Furniture (Bed, Desk, Chair)
            drawPlatformBed(g2, ox + 140, oy + 260);
            drawStudyDeskAndLaptop(g2, ox + 430, oy + 270);

            // 4. Doorway (Back Left)
            drawDoorway(g2, ox + 40, oy + 120, doorOpenSignal);

            // 5. Realistic Human Occupant (Based on current pose)
            drawHumanCharacter(g2, ox, oy, currentPose);

            // 6. Split Air Conditioner (HVAC unit on right wall)
            drawSplitAirConditioner(g2, ox + 500, oy + 110, acCompressorOn, acLouverAngle);

            // 7. Dynamic AC Airflow Particles
            if (acCompressorOn) {
                drawAirflowParticles(g2, ox, oy);
            }

            // 8. Ceiling LED Luminaire & Dynamic Ambient Illumination
            drawCeilingLedLuminaire(g2, ox + 340, oy + 25, ledBrightness);

            // 9. Overlay HUD Banner & Radar Waveform
            drawCanvasHUD(g2, w, h);

            g2.dispose();
        }

        // ── Room Shell (Walls, Hardwood Floor Grid) ──
        private void drawRoomShell(Graphics2D g2, int ox, int oy) {
            // Back Wall
            Polygon backWall = new Polygon();
            backWall.addPoint(ox + 120, oy + 40);
            backWall.addPoint(ox + 580, oy + 40);
            backWall.addPoint(ox + 580, oy + 250);
            backWall.addPoint(ox + 120, oy + 250);
            Color wallBase = blend(new Color(28, 36, 52), new Color(254, 243, 199), ledBrightness * 0.25f);
            g2.setColor(wallBase);
            g2.fill(backWall);
            g2.setColor(new Color(40, 50, 70));
            g2.draw(backWall);

            // Left Wall
            Polygon leftWall = new Polygon();
            leftWall.addPoint(ox + 30, oy + 90);
            leftWall.addPoint(ox + 120, oy + 40);
            leftWall.addPoint(ox + 120, oy + 250);
            leftWall.addPoint(ox + 30, oy + 320);
            g2.setColor(blend(new Color(22, 28, 42), new Color(253, 230, 138), ledBrightness * 0.15f));
            g2.fill(leftWall);
            g2.setColor(new Color(36, 44, 62));
            g2.draw(leftWall);

            // Hardwood Floor (Perspective quadrilateral)
            Polygon floor = new Polygon();
            floor.addPoint(ox + 30, oy + 320);
            floor.addPoint(ox + 120, oy + 250);
            floor.addPoint(ox + 580, oy + 250);
            floor.addPoint(ox + 670, oy + 360);
            floor.addPoint(ox + 100, oy + 440);

            Color floorBase = blend(new Color(38, 26, 20), new Color(120, 80, 50), ledBrightness * 0.35f);
            g2.setColor(floorBase);
            g2.fill(floor);

            // Planks Lines
            g2.setColor(new Color(0, 0, 0, 55));
            for (int i = 0; i < 7; i++) {
                int px1 = ox + 40 + i * 85;
                int py1 = oy + 310 + (int) (i * 18);
                int px2 = ox + 140 + i * 65;
                int py2 = oy + 250;
                g2.drawLine(px1, py1, px2, py2);
            }

            // Floor border
            g2.setColor(new Color(50, 60, 80));
            g2.draw(floor);
        }

        // ── Window View (Exterior Skylight) ──
        private void drawWindowView(Graphics2D g2, int wx, int wy, int ww, int wh) {
            Color skyTop = (ledBrightness > 0.5f) ? new Color(74, 144, 226) : new Color(15, 23, 42);
            Color skyBottom = (ledBrightness > 0.5f) ? new Color(199, 230, 255) : new Color(30, 41, 59);
            GradientPaint gp = new GradientPaint(wx, wy, skyTop, wx, wy + wh, skyBottom);
            g2.setPaint(gp);
            g2.fillRect(wx, wy, ww, wh);

            // Sun/Moon
            if (ledBrightness > 0.5f) {
                g2.setColor(new Color(255, 245, 180, 220));
                g2.fillOval(wx + ww - 35, wy + 15, 22, 22);
            } else {
                g2.setColor(new Color(241, 245, 249, 190));
                g2.fillOval(wx + ww - 35, wy + 15, 16, 16);
            }

            // Window Frame
            g2.setColor(new Color(51, 65, 85));
            g2.setStroke(new BasicStroke(4f));
            g2.drawRect(wx, wy, ww, wh);
            g2.drawLine(wx + ww / 2, wy, wx + ww / 2, wy + wh);
            g2.drawLine(wx, wy + wh / 2, wx + ww, wy + wh / 2);
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Platform Bed & Nightstand ──
        private void drawPlatformBed(Graphics2D g2, int bx, int by) {
            // Bed frame
            g2.setColor(new Color(30, 41, 59));
            g2.fillRoundRect(bx, by, 160, 95, 8, 8);
            g2.setColor(new Color(51, 65, 85));
            g2.drawRoundRect(bx, by, 160, 95, 8, 8);

            // Headboard
            g2.setColor(new Color(15, 23, 42));
            g2.fillRoundRect(bx - 12, by - 22, 24, 115, 6, 6);

            // Mattress
            g2.setColor(new Color(241, 245, 249));
            g2.fillRoundRect(bx + 10, by + 6, 140, 80, 6, 6);

            // Pillow
            g2.setColor(new Color(226, 232, 240));
            g2.fillRoundRect(bx + 14, by + 12, 32, 45, 8, 8);

            // Quilted Blanket
            Color blanketCol = blend(new Color(59, 130, 246), new Color(14, 165, 233), 0.3f);
            g2.setColor(blanketCol);
            g2.fillRoundRect(bx + 52, by + 6, 98, 80, 6, 6);

            // Bedside Night Lamp
            int lx = bx + 6;
            int ly = by - 48;
            g2.setColor(new Color(71, 85, 105));
            g2.fillRect(lx + 8, ly + 14, 4, 16); // Lamp post
            g2.setColor(currentPose == OccupantPose.IN_BED_SLEEPING ? new Color(254, 215, 170) : new Color(148, 163, 184));
            g2.fillPolygon(new int[]{lx, lx + 20, lx + 16, lx + 4}, new int[]{ly + 14, ly + 14, ly, ly}, 4);

            if (currentPose == OccupantPose.IN_BED_SLEEPING) {
                // Soft bedside night lamp glow
                RadialGradientPaint lampGlow = new RadialGradientPaint(
                    lx + 10, ly + 10, 45,
                    new float[]{0.0f, 1.0f},
                    new Color[]{new Color(254, 215, 170, 110), new Color(254, 215, 170, 0)}
                );
                g2.setPaint(lampGlow);
                g2.fillOval(lx - 35, ly - 35, 90, 90);
            }
        }

        // ── Study Desk, Ergonomic Chair & Laptop ──
        private void drawStudyDeskAndLaptop(Graphics2D g2, int dx, int dy) {
            // Desk surface (wood finish)
            g2.setColor(new Color(92, 64, 51));
            g2.fillRoundRect(dx, dy, 130, 65, 6, 6);
            g2.setColor(new Color(45, 30, 20));
            g2.drawRoundRect(dx, dy, 130, 65, 6, 6);

            // Steel Legs
            g2.setColor(new Color(100, 116, 139));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(dx + 6, dy + 65, dx + 6, dy + 105);
            g2.drawLine(dx + 124, dy + 65, dx + 124, dy + 105);
            g2.setStroke(new BasicStroke(1f));

            // Ergonomic Chair (Backrest & Seat)
            g2.setColor(new Color(30, 41, 59));
            g2.fillRoundRect(dx + 42, dy + 55, 46, 38, 8, 8);
            g2.setColor(new Color(51, 65, 85));
            g2.drawRoundRect(dx + 42, dy + 55, 46, 38, 8, 8);

            // Laptop on desk
            int lx = dx + 45;
            int ly = dy + 16;
            g2.setColor(new Color(148, 163, 184));
            g2.fillRect(lx, ly + 14, 38, 20); // Keyboard base

            // Screen
            g2.setColor(new Color(30, 41, 59));
            g2.fillRect(lx + 2, ly, 34, 15);
            g2.setColor(new Color(56, 189, 248, (int) (180 * Math.min(1.0f, ledBrightness + 0.2f))));
            g2.fillRect(lx + 4, ly + 2, 30, 11); // Active display glow
        }

        // ── Doorway & Magnetic Reed Sensor ──
        private void drawDoorway(Graphics2D g2, int drx, int dry, boolean open) {
            // Door Frame
            g2.setColor(new Color(30, 41, 59));
            g2.setStroke(new BasicStroke(4f));
            g2.drawRect(drx, dry, 50, 140);
            g2.setStroke(new BasicStroke(1f));

            // Door Leaf
            if (!open) {
                g2.setColor(new Color(71, 85, 105));
                g2.fillRect(drx + 2, dry + 2, 46, 136);
                g2.setColor(new Color(203, 213, 225));
                g2.fillOval(drx + 38, dry + 70, 5, 5); // Brass handle
            } else {
                // Open door leaf perspective
                Polygon openLeaf = new Polygon();
                openLeaf.addPoint(drx + 2, dry + 2);
                openLeaf.addPoint(drx - 22, dry + 20);
                openLeaf.addPoint(drx - 22, dry + 140);
                openLeaf.addPoint(drx + 2, dry + 138);
                g2.setColor(new Color(51, 65, 85));
                g2.fill(openLeaf);
            }

            // Magnetic Reed Contact Sensor at top door jamb
            g2.setColor(open ? ACCENT_RED : ACCENT_GREEN);
            g2.fillRect(drx + 20, dry - 4, 12, 5);
        }

        // ── Realistic Human Character ──
        private void drawHumanCharacter(Graphics2D g2, int ox, int oy, OccupantPose pose) {
            switch (pose) {
                case ENTERING_DOOR -> {
                    // Person stepping through door
                    int px = ox + 72;
                    int py = oy + 175;
                    drawWalkingPerson(g2, px, py, true);
                }
                case AT_DESK_STUDYING -> {
                    // Person sitting at desk
                    int px = ox + 490;
                    int py = oy + 285;
                    drawSeatedPerson(g2, px, py);
                }
                case IN_BED_SLEEPING -> {
                    // Person lying down under blanket
                    int px = ox + 175;
                    int py = oy + 288;
                    drawSleepingPerson(g2, px, py);
                }
                case EXITING_DOOR -> {
                    // Person walking out
                    int px = ox + 68;
                    int py = oy + 178;
                    drawWalkingPerson(g2, px, py, false);
                }
                case ABSENT -> {
                    // Empty room
                }
            }
        }

        private void drawWalkingPerson(Graphics2D g2, int px, int py, boolean entering) {
            // Head
            g2.setColor(new Color(254, 215, 170));
            g2.fillOval(px, py - 46, 16, 18);
            // Hair
            g2.setColor(new Color(44, 24, 16));
            g2.fillArc(px - 1, py - 48, 18, 14, 0, 180);

            // Torso (Casual shirt)
            g2.setColor(new Color(37, 99, 235));
            g2.fillRoundRect(px - 4, py - 26, 24, 32, 6, 6);

            // Legs & Shoes (Walking stride)
            g2.setColor(new Color(30, 41, 59));
            if (entering) {
                g2.drawLine(px + 4, py + 6, px + 12, py + 34); // Right leg forward
                g2.drawLine(px + 12, py + 6, px - 2, py + 30);  // Left leg back
            } else {
                g2.drawLine(px + 4, py + 6, px - 6, py + 32);
                g2.drawLine(px + 12, py + 6, px + 8, py + 32);
            }

            // Shoulder Bag / Backpack
            g2.setColor(new Color(217, 119, 6));
            g2.fillRoundRect(px - 8, py - 22, 8, 18, 4, 4);
        }

        private void drawSeatedPerson(Graphics2D g2, int px, int py) {
            // Head (Tilted slightly forward towards laptop)
            g2.setColor(new Color(254, 215, 170));
            g2.fillOval(px - 28, py - 38, 16, 16);
            // Hair
            g2.setColor(new Color(44, 24, 16));
            g2.fillArc(px - 29, py - 40, 18, 12, 0, 180);

            // Upper Body (Seated leaning in)
            g2.setColor(new Color(16, 185, 129));
            g2.fillRoundRect(px - 32, py - 20, 22, 28, 6, 6);

            // Arms extending to laptop keyboard
            g2.setColor(new Color(254, 215, 170));
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(px - 22, py - 10, px - 4, py - 2);
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawSleepingPerson(Graphics2D g2, int px, int py) {
            // Head on pillow
            g2.setColor(new Color(254, 215, 170));
            g2.fillOval(px - 25, py - 18, 16, 15);
            // Hair
            g2.setColor(new Color(44, 24, 16));
            g2.fillOval(px - 28, py - 20, 12, 16);

            // Subtle chest respiration movement indicator
            float breathOffset = (float) (1.5 * Math.sin(System.currentTimeMillis() * 0.003));
            g2.setColor(new Color(255, 255, 255, 140));
            g2.drawOval((int) (px + 10), (int) (py - 12 + breathOffset), 28, 14);

            // Zzz text floating
            g2.setColor(new Color(192, 132, 252, 200));
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            g2.drawString("z Z", px + 4, py - 24);
        }

        // ── Wall-Mounted Split Air Conditioner (HVAC Unit) ──
        private void drawSplitAirConditioner(Graphics2D g2, int ax, int ay, boolean active, float louverDeg) {
            int aw = 110;
            int ah = 44;

            // AC Main Chassis (Sleek Matte White with bevel)
            GradientPaint acGrad = new GradientPaint(ax, ay, new Color(248, 250, 252), ax, ay + ah, new Color(226, 232, 240));
            g2.setPaint(acGrad);
            g2.fillRoundRect(ax, ay, aw, ah, 10, 10);
            g2.setColor(new Color(148, 163, 184));
            g2.drawRoundRect(ax, ay, aw, ah, 10, 10);

            // Top Intake Air Grille
            g2.setColor(new Color(100, 116, 139, 120));
            for (int i = 0; i < 4; i++) {
                g2.drawLine(ax + 10, ay + 6 + i * 3, ax + aw - 10, ay + 6 + i * 3);
            }

            // Digital LED Display Panel on AC Face
            g2.setColor(new Color(15, 23, 42));
            g2.fillRoundRect(ax + aw - 38, ay + 18, 28, 14, 4, 4);
            if (active) {
                g2.setColor(ACCENT_CYAN);
                g2.setFont(new Font("Consolas", Font.BOLD, 9));
                g2.drawString("23°C", ax + aw - 36, ay + 29);
            } else {
                g2.setColor(new Color(203, 213, 225)); // High-contrast light slate (> 8:1 on #0F172A)
                g2.setFont(new Font("Consolas", Font.BOLD, 8));
                g2.drawString("--", ax + aw - 30, ay + 28);
            }

            // Status LED Dot (Green Power / Blue Cooling)
            g2.setColor(active ? ACCENT_CYAN : ACCENT_AMBER);
            g2.fillOval(ax + 14, ay + 26, 5, 5);

            // Motorized Swing Louver Flap
            g2.setColor(new Color(203, 213, 225));
            int louverH = (int) (6 + (louverDeg / 45f) * 8);
            g2.fillRoundRect(ax + 8, ay + ah - 6, aw - 16, louverH, 4, 4);
            g2.setColor(new Color(100, 116, 139));
            g2.drawRoundRect(ax + 8, ay + ah - 6, aw - 16, louverH, 4, 4);
        }

        // ── Animated Cooling Breeze Streamlines ──
        private void drawAirflowParticles(Graphics2D g2, int ox, int oy) {
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (AirflowParticle p : airParticles) {
                float alpha = (p.life / p.maxLife) * 0.7f;
                if (alpha < 0f) alpha = 0f;
                g2.setColor(new Color(56, 189, 248, (int) (alpha * 255)));
                g2.drawLine((int) (ox + p.x), (int) (oy + p.y),
                            (int) (ox + p.x + p.vx * 3f), (int) (oy + p.y + p.vy * 3f));
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Ceiling LED Luminaire & Realistic Ambient Light Cone ──
        private void drawCeilingLedLuminaire(Graphics2D g2, int lx, int ly, float brightness) {
            int lw = 130;
            int lh = 22;

            // Ambient Light Cone (Illuminates room space)
            if (brightness > 0.05f) {
                Polygon lightCone = new Polygon();
                lightCone.addPoint(lx + 10, ly + lh);
                lightCone.addPoint(lx + lw - 10, ly + lh);
                lightCone.addPoint(lx + lw + 190, ly + 400);
                lightCone.addPoint(lx - 190, ly + 400);

                int alpha = (int) (brightness * 75);
                GradientPaint coneGrad = new GradientPaint(
                    lx + lw / 2, ly + lh, new Color(254, 243, 199, alpha),
                    lx + lw / 2, ly + 400, new Color(254, 243, 199, 0)
                );
                g2.setPaint(coneGrad);
                g2.fill(lightCone);
            }

            // Luminaire Metal Enclosure
            g2.setColor(new Color(71, 85, 105));
            g2.fillRoundRect(lx - 4, ly, lw + 8, lh, 6, 6);

            // COB LED Panel Surface
            int colAlpha = (int) (brightness * 255);
            Color ledEmitterColor = new Color(255, 255, 230, Math.max(30, colAlpha));
            g2.setColor(ledEmitterColor);
            g2.fillRoundRect(lx, ly + 4, lw, lh - 8, 4, 4);

            // Multi-diode array dots
            g2.setColor(new Color(254, 215, 170, (int) (brightness * 200)));
            for (int i = 0; i < 9; i++) {
                g2.fillOval(lx + 10 + i * 13, ly + 7, 5, 5);
            }
        }

        // ── On-Canvas HUD Overlay (Pose Banner + mmWave Radar Waveform) ──
        private void drawCanvasHUD(Graphics2D g2, int w, int h) {
            // Top Left Scenario/Pose Pill (No emojis)
            String poseLabel = switch (currentPose) {
                case ENTERING_DOOR    -> "OCCUPANT ENTERING ROOM (DOOR OPEN)";
                case AT_DESK_STUDYING -> "DESK STUDY (SEDENTARY PRESENCE CONFIRMED)";
                case IN_BED_SLEEPING  -> "SLEEPING IN BED (LUMINAIRE DIMMED - VITAL BREATHING)";
                case EXITING_DOOR     -> "OCCUPANT DEPARTING (ENTER HYSTERESIS)";
                case ABSENT           -> "ROOM VACANT (STANDBY POWER SAVE)";
            };

            g2.setColor(new Color(0, 0, 0, 200));
            g2.fillRoundRect(14, 14, 410, 32, 8, 8);
            g2.setColor(roomState.equals("OCCUPIED") ? ACCENT_GREEN : (roomState.equals("SLEEP") ? ACCENT_PURPLE : ACCENT_AMBER));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(14, 14, 410, 32, 8, 8);

            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            g2.drawString(poseLabel, 26, 35);

            // Bottom Left: 60GHz mmWave Radar Respiration Oscilloscope
            int ox = 14;
            int oy = h - 68;
            g2.setColor(new Color(0, 0, 0, 210));
            g2.fillRoundRect(ox, oy, 230, 52, 8, 8);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(ox, oy, 230, 52, 8, 8);

            g2.setColor(TEXT_MUTED);
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
            g2.drawString("60GHz mmWAVE RADAR MICRO-MOTION", ox + 10, oy + 14);

            // Waveform trace
            g2.setColor(currentPose == OccupantPose.IN_BED_SLEEPING ? ACCENT_PURPLE : ACCENT_GREEN);
            g2.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < radarWaveform.length - 1; i++) {
                int x1 = ox + 10 + i * 3;
                int y1 = oy + 34 + (int) (radarWaveform[i] * 12);
                int x2 = ox + 10 + (i + 1) * 3;
                int y2 = oy + 34 + (int) (radarWaveform[i + 1] * 12);
                g2.drawLine(x1, y1, x2, y2);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private Color blend(Color c1, Color c2, float ratio) {
            float r = c1.getRed() * (1 - ratio) + c2.getRed() * ratio;
            float g = c1.getGreen() * (1 - ratio) + c2.getGreen() * ratio;
            float b = c1.getBlue() * (1 - ratio) + c2.getBlue() * ratio;
            return new Color((int) Math.min(255, Math.max(0, r)),
                             (int) Math.min(255, Math.max(0, g)),
                             (int) Math.min(255, Math.max(0, b)));
        }
    }

    // ─── UI Helper Methods & Styled Components ────────────────────────────────
    private JLabel createMetricCard(String title, String val, Color valColor, JPanel container) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 3));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));

        JLabel tLbl = new JLabel(title);
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        tLbl.setForeground(TEXT_MUTED);

        JLabel vLbl = new JLabel(val);
        vLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 14));
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
        l.setOpaque(true);
        l.setBackground(new Color(6, 10, 18)); // Solid pitch obsidian background
        l.setBorder(new CompoundBorder(
            new LineBorder(color, 1, true),
            new EmptyBorder(3, 8, 3, 8)
        ));
        return l;
    }

    private JButton createStyledButton(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        b.setBackground(bg);
        b.setContentAreaFilled(true);
        b.setOpaque(true);
        // Automated luminance contrast calculation: Guarantees font and background are NEVER same or similar!
        double lum = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
        b.setForeground((lum > 0.50) ? new Color(10, 15, 26) : Color.WHITE);
        b.setFocusPainted(false);
        b.setBorder(new CompoundBorder(new LineBorder(bg.brighter(), 1, true), new EmptyBorder(8, 14, 8, 14)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void updateButtonAppearance(JButton b, String text, Color bg) {
        b.setText(text);
        b.setBackground(bg);
        b.setContentAreaFilled(true);
        b.setOpaque(true);
        double lum = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
        b.setForeground((lum > 0.50) ? new Color(10, 15, 26) : Color.WHITE);
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(String.format(Locale.US, "[%s] %s%n",
                new SimpleDateFormat("HH:mm:ss").format(new Date()), msg));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

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

    // ─── Main Entry Point ────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int sPort   = args.length > 1 ? Integer.parseInt(args[1]) : 9091;
        int tPort   = args.length > 2 ? Integer.parseInt(args[2]) : 9090;

        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SmartRoomBenchApp(host, sPort, tPort));
    }
}
