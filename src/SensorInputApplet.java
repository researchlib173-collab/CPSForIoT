import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

// ============================================================================
// SENSOR INPUT APPLET — Physical Hardware Stimulator & Terminal Bus Client
// ============================================================================
// Focuses EXCLUSIVELY on the INPUT side:
// Connects to Port 2 (9091) - Workspace Physical Terminal Bus.
// Injects physical voltages into sensor terminals:
//   - PIR_MotionSensor.DATA
//   - DoorContactSensor.STATE
//   - MillimeterWaveRadar.PRESENCE_SIG
// ============================================================================
public class SensorInputApplet extends JFrame {

    // ─── Theme Font & Colors ─────────────────────────────────────────────────
    public static final String FONT_FAMILY  = "Space Mono";
    private static final Color BG_DARK      = new Color(18, 20, 28);
    private static final Color CARD_BG      = new Color(26, 30, 42);
    private static final Color ACCENT_BLUE  = new Color(70, 140, 255);
    private static final Color ACCENT_GREEN = new Color(50, 210, 110);
    private static final Color ACCENT_AMBER = new Color(255, 180, 50);
    private static final Color ACCENT_RED   = new Color(240, 75, 75);
    private static final Color TEXT_MAIN    = new Color(230, 235, 245);
    private static final Color TEXT_MUTED   = new Color(130, 140, 165);

    // ─── Networking (Port 2: Physical Netlist Terminal Bus) ───────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── UI Controls (Sensors) ───────────────────────────────────────────────
    private final JLabel statusLabel = new JLabel("● DISCONNECTED", SwingConstants.CENTER);

    // PIR
    private final JToggleButton pirToggle = new JToggleButton("PIR: NO MOTION (0.0V)");
    private final JButton pirPulseBtn = new JButton("Pulse Motion (3s)");

    // Door
    private final JToggleButton doorToggle = new JToggleButton("DOOR: CLOSED (0.0V)");
    private final JButton doorWalkBtn = new JButton("Walk Through (Open ➔ Close)");

    // mmWave Radar
    private final JSlider radarSlider = new JSlider(0, 100, 0);
    private final JLabel radarValueLbl = new JLabel("0% (0.00 V)", SwingConstants.CENTER);
    private final JProgressBar radarBar = new JProgressBar(0, 100);

    // Terminal Command Log
    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    private final JList<String> logList = new JList<>(logModel);

    public SensorInputApplet(String host, int port) {
        super("CPS Sensor Input Applet — Physical Terminal Bus");
        this.host = host;
        this.port = port;

        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(540, 780);
        setMinimumSize(new Dimension(480, 650));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        // Auto-connect on startup
        SwingUtilities.invokeLater(this::connect);
    }

    public SensorInputApplet() {
        this("localhost", 9091);
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

        // ── TOP: Header & Connection Bar ─────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout(8, 6));
        topPanel.setBackground(CARD_BG);
        topPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 46, 64), 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));

        JLabel titleLbl = new JLabel("HARDWARE SENSOR STIMULATOR");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_BLUE);

        JLabel subLbl = new JLabel("Direct Terminal Access • Port " + port + " • Latency: PIR 180ms | mmWave 140ms | Relay 16ms");
        subLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        subLbl.setForeground(TEXT_MUTED);

        JPanel titleBox = new JPanel(new GridLayout(2, 1, 0, 2));
        titleBox.setOpaque(false);
        titleBox.add(titleLbl);
        titleBox.add(subLbl);

        statusLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        statusLabel.setForeground(ACCENT_RED);

        JButton reconnectBtn = createStyledButton("Connect", ACCENT_GREEN);
        reconnectBtn.addActionListener(e -> {
            if (!connected) connect();
            else disconnect();
        });

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBox.setOpaque(false);
        rightBox.add(statusLabel);
        rightBox.add(reconnectBtn);

        topPanel.add(titleBox, BorderLayout.WEST);
        topPanel.add(rightBox, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // ── CENTER: Sensor Control Panels ────────────────────────────────────
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        centerPanel.add(buildPirPanel());
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(buildDoorPanel());
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(buildRadarPanel());
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(buildScenarioPanel());

        add(centerPanel, BorderLayout.CENTER);

        // ── BOTTOM: Activity Log ─────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.setBackground(CARD_BG);
        bottomPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 46, 64), 1, true),
            new EmptyBorder(8, 10, 8, 10)
        ));
        bottomPanel.setPreferredSize(new Dimension(100, 150));

        JLabel logHeader = new JLabel("Terminal Bus Log (Port " + port + "):");
        logHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        logHeader.setForeground(TEXT_MUTED);

        logList.setBackground(new Color(14, 16, 22));
        logList.setForeground(new Color(130, 220, 150));
        logList.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        bottomPanel.add(logHeader, BorderLayout.NORTH);
        bottomPanel.add(scroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ─── PIR Control Panel ───────────────────────────────────────────────────
    private JPanel buildPirPanel() {
        JPanel card = createCard("👁  PIR Motion Detector (Terminal: PIR_MotionSensor.DATA)");

        pirToggle.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        pirToggle.setFocusPainted(false);
        pirToggle.setBackground(new Color(45, 50, 70));
        pirToggle.setForeground(TEXT_MAIN);
        pirToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        pirToggle.addItemListener(e -> {
            boolean active = pirToggle.isSelected();
            pirToggle.setText(active ? "PIR: MOTION DETECTED (3.3V)" : "PIR: NO MOTION (0.0V)");
            pirToggle.setBackground(active ? ACCENT_GREEN : new Color(45, 50, 70));
            pirToggle.setForeground(active ? Color.BLACK : TEXT_MAIN);
            sendTerminalSet("PIR_MotionSensor", "DATA", active ? 3.3 : 0.0);
        });

        pirPulseBtn.setBackground(new Color(40, 48, 68));
        pirPulseBtn.setForeground(TEXT_MAIN);
        pirPulseBtn.setFocusPainted(false);
        pirPulseBtn.addActionListener(e -> {
            new Thread(() -> {
                SwingUtilities.invokeLater(() -> pirToggle.setSelected(true));
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                SwingUtilities.invokeLater(() -> pirToggle.setSelected(false));
            }).start();
        });

        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setOpaque(false);
        row.add(pirToggle);
        row.add(pirPulseBtn);

        card.add(row, BorderLayout.CENTER);
        return card;
    }

    // ─── Door Contact Panel ──────────────────────────────────────────────────
    private JPanel buildDoorPanel() {
        JPanel card = createCard("🚪  Magnetic Reed Door Switch (Terminal: DoorContactSensor.STATE)");

        doorToggle.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        doorToggle.setFocusPainted(false);
        doorToggle.setBackground(new Color(45, 50, 70));
        doorToggle.setForeground(TEXT_MAIN);
        doorToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        doorToggle.addItemListener(e -> {
            boolean open = doorToggle.isSelected();
            doorToggle.setText(open ? "DOOR: OPEN (3.3V)" : "DOOR: CLOSED (0.0V)");
            doorToggle.setBackground(open ? ACCENT_AMBER : new Color(45, 50, 70));
            doorToggle.setForeground(open ? Color.BLACK : TEXT_MAIN);
            sendTerminalSet("DoorContactSensor", "STATE", open ? 3.3 : 0.0);
        });

        doorWalkBtn.setBackground(new Color(40, 48, 68));
        doorWalkBtn.setForeground(TEXT_MAIN);
        doorWalkBtn.setFocusPainted(false);
        doorWalkBtn.addActionListener(e -> {
            new Thread(() -> {
                // Open door
                SwingUtilities.invokeLater(() -> doorToggle.setSelected(true));
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                // Close door
                SwingUtilities.invokeLater(() -> doorToggle.setSelected(false));
            }).start();
        });

        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setOpaque(false);
        row.add(doorToggle);
        row.add(doorWalkBtn);

        card.add(row, BorderLayout.CENTER);
        return card;
    }

    // ─── mmWave Radar Panel ──────────────────────────────────────────────────
    private JPanel buildRadarPanel() {
        JPanel card = createCard("📡  60GHz mmWave Presence Radar (Terminal: MillimeterWaveRadar.PRESENCE_SIG)");

        radarSlider.setOpaque(false);
        radarSlider.setValue(0);
        radarBar.setStringPainted(true);
        radarBar.setString("0% (0.00 V)");
        radarBar.setForeground(ACCENT_BLUE);
        radarBar.setBackground(new Color(20, 22, 32));

        radarValueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        radarValueLbl.setForeground(ACCENT_BLUE);

        radarSlider.addChangeListener(e -> {
            int pct = radarSlider.getValue();
            double voltage = (pct / 100.0) * 3.3;
            radarBar.setValue(pct);
            radarBar.setString(String.format("%d%% (%.2f V)", pct, voltage));
            radarValueLbl.setText(String.format("%d%% (%.2f V)", pct, voltage));
            sendTerminalSet("MillimeterWaveRadar", "PRESENCE_SIG", voltage);
        });

        JPanel presetRow = new JPanel(new GridLayout(1, 4, 6, 0));
        presetRow.setOpaque(false);

        JButton offBtn = createPresetBtn("0% (Empty)", 0);
        JButton sleepBtn = createPresetBtn("55% (Sleep)", 55);
        JButton deskBtn = createPresetBtn("80% (Desk)", 80);
        JButton maxBtn = createPresetBtn("100% (High)", 100);

        presetRow.add(offBtn);
        presetRow.add(sleepBtn);
        presetRow.add(deskBtn);
        presetRow.add(maxBtn);

        JPanel radarBox = new JPanel(new GridLayout(3, 1, 0, 4));
        radarBox.setOpaque(false);
        radarBox.add(radarSlider);
        radarBox.add(radarBar);
        radarBox.add(presetRow);

        card.add(radarBox, BorderLayout.CENTER);
        return card;
    }

    private JButton createPresetBtn(String label, int val) {
        JButton btn = new JButton(label);
        btn.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        btn.setBackground(new Color(36, 42, 58));
        btn.setForeground(TEXT_MAIN);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> radarSlider.setValue(val));
        return btn;
    }

    // ─── Scenario Presets Panel ──────────────────────────────────────────────
    private JPanel buildScenarioPanel() {
        JPanel card = createCard("⚡  One-Click Simulation Scenarios");

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);

        JButton enterSitBtn = createStyledButton("👤 Enter & Sit at Desk", ACCENT_BLUE);
        enterSitBtn.addActionListener(e -> runScenario("ENTER_DESK"));

        JButton sleepingBtn = createStyledButton("🛌 Sleeping in Bed", new Color(140, 100, 230));
        sleepingBtn.addActionListener(e -> runScenario("SLEEPING"));

        JButton leaveBtn = createStyledButton("🚪 Leave the Room", ACCENT_AMBER);
        leaveBtn.addActionListener(e -> runScenario("LEAVING"));

        JButton resetBtn = createStyledButton("❌ Vacate / Clear All", ACCENT_RED);
        resetBtn.addActionListener(e -> runScenario("CLEAR"));

        grid.add(enterSitBtn);
        grid.add(sleepingBtn);
        grid.add(leaveBtn);
        grid.add(resetBtn);

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private void runScenario(String scenario) {
        new Thread(() -> {
            switch (scenario) {
                case "ENTER_DESK" -> {
                    log("Scenario: Entering room and sitting still at desk...");
                    // 1. Door opens & brief motion
                    SwingUtilities.invokeLater(() -> {
                        doorToggle.setSelected(true);
                        pirToggle.setSelected(true);
                    });
                    sleep(2000);
                    // 2. Door closes
                    SwingUtilities.invokeLater(() -> doorToggle.setSelected(false));
                    sleep(1500);
                    // 3. User sits still: PIR stops, but radar senses 80% chest/desk movement
                    SwingUtilities.invokeLater(() -> {
                        pirToggle.setSelected(false);
                        radarSlider.setValue(80);
                    });
                }
                case "SLEEPING" -> {
                    log("Scenario: Sleeping in bed (Zero PIR motion, microscopic radar breath)...");
                    SwingUtilities.invokeLater(() -> {
                        doorToggle.setSelected(false);
                        pirToggle.setSelected(false);
                        radarSlider.setValue(60); // 60% breathing radar detection
                    });
                }
                case "LEAVING" -> {
                    log("Scenario: Occupant leaves the room...");
                    // 1. Walks to door
                    SwingUtilities.invokeLater(() -> pirToggle.setSelected(true));
                    sleep(1500);
                    // 2. Door opens
                    SwingUtilities.invokeLater(() -> doorToggle.setSelected(true));
                    sleep(1500);
                    // 3. Door closes, everything goes silent
                    SwingUtilities.invokeLater(() -> {
                        doorToggle.setSelected(false);
                        pirToggle.setSelected(false);
                        radarSlider.setValue(0);
                    });
                }
                case "CLEAR" -> {
                    log("Scenario: Room cleared to VACANT (All sensors 0.0V).");
                    SwingUtilities.invokeLater(() -> {
                        doorToggle.setSelected(false);
                        pirToggle.setSelected(false);
                        radarSlider.setValue(0);
                    });
                }
            }
        }).start();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ─── Card Builder ────────────────────────────────────────────────────────
    private JPanel createCard(String title) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(new Color(36, 42, 60), 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        titleLbl.setForeground(new Color(180, 195, 220));

        card.add(titleLbl, BorderLayout.NORTH);
        return card;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        btn.setBackground(bg);
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ─── Terminal Bus Networking ─────────────────────────────────────────────
    private void connect() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to " + host + ":" + port + "...", ACCENT_AMBER);
                socket = new Socket(host, port);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                updateStatus("● CONNECTED (Port " + port + ")", ACCENT_GREEN);
                log("Tapped into Physical Terminal Bus at " + host + ":" + port);

                // Background reader
                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String msg = line;
                    SwingUtilities.invokeLater(() -> log("← " + msg));
                }
            } catch (IOException e) {
                updateStatus("● DISCONNECTED", ACCENT_RED);
                log("[ERROR] " + e.getMessage());
                connected = false;
            }
        }, "TerminalBusClient").start();
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        updateStatus("● DISCONNECTED", ACCENT_RED);
        log("Disconnected from Terminal Bus.");
    }

    private void sendTerminalSet(String board, String pin, double voltage) {
        if (out != null && connected) {
            String cmd = String.format("SET %s %s %.3f", board, pin, voltage);
            out.println(cmd);
            log(String.format("→ SET %s.%s = %.2f V", board, pin, voltage));
        } else {
            log("[WARN] Not connected to Terminal Bus (Port " + port + ")");
        }
    }

    private void updateStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            logModel.add(0, "[" + time + "] " + msg);
            if (logModel.size() > 80) logModel.removeElementAt(logModel.size() - 1);
        });
    }

    // ─── Entry Point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9091;
        SwingUtilities.invokeLater(() -> new SensorInputApplet(host, port));
    }
}
