import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// ============================================================================
// THERMAL BENCH INPUT APPLET — Physical Load & Environmental Stimulator
// ============================================================================
// Focuses EXCLUSIVELY on the INPUT side:
// Connects to Port 2 (9093) - Terminal Bus Tap.
// Injects physical voltages into:
//   - Prog_DC_Load.CURRENT_CMD       (Iout, 0 to 20A)
//   - ThermalChamber.AMBIENT_TEMP    (Tamb, 25 to 85 degC)
//   - BenchPowerSource.VIN_REGULATOR (Vin, 18 to 36V)
//   - Thermocouple Hotspots (Tmosfet, Tcore, Tdiode)
// ============================================================================
public class ThermalBenchInputApplet extends JFrame {

    // ─── Theme Font & Colors ─────────────────────────────────────────────────
    public static final String FONT_FAMILY  = "Space Mono";
    private static final Color BG_DARK      = new Color(16, 20, 28);
    private static final Color CARD_BG      = new Color(24, 30, 44);
    private static final Color ACCENT_ORANGE= new Color(255, 130, 45);
    private static final Color ACCENT_CYAN  = new Color(45, 200, 240);
    private static final Color ACCENT_GREEN = new Color(50, 215, 120);
    private static final Color ACCENT_RED   = new Color(245, 75, 75);
    private static final Color TEXT_MAIN    = new Color(230, 235, 245);
    private static final Color TEXT_MUTED   = new Color(135, 145, 170);

    // ─── Networking (Port 2: Physical Terminal Bus) ──────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Controls ────────────────────────────────────────────────────────────
    private final JLabel statusLabel = new JLabel("● DISCONNECTED", SwingConstants.CENTER);

    // 1. Electronic DC Load (0 - 20 A)
    private final JSlider loadSlider = new JSlider(0, 200, 0); // 0.0 to 20.0 A (step 0.1A)
    private final JLabel loadValueLbl = new JLabel("0.0 A (0.00 V)", SwingConstants.CENTER);

    // 2. Chamber Ambient Temperature (25 - 85 degC)
    private final JSlider chamberSlider = new JSlider(25, 85, 25);
    private final JLabel chamberValueLbl = new JLabel("25.0 °C (0.83 V)", SwingConstants.CENTER);

    // 3. Input Bench Voltage (18 - 36 V)
    private final JSlider vinSlider = new JSlider(180, 360, 240); // 18.0 to 36.0 V (step 0.1V)
    private final JLabel vinValueLbl = new JLabel("24.0 V (2.20 V)", SwingConstants.CENTER);

    // Terminal Command Log
    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    private final JList<String> logList = new JList<>(logModel);

    // Physics Simulation state
    private double currentIout = 0.0;
    private double currentTamb = 25.0;
    private double currentVin  = 24.0;

    public ThermalBenchInputApplet(String host, int port) {
        super("Thermal Benchmark Input Stimulator — Physical Terminal Bus");
        this.host = host;
        this.port = port;

        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(540, 800);
        setMinimumSize(new Dimension(480, 680));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setVisible(true);

        SwingUtilities.invokeLater(this::connect);
    }

    public ThermalBenchInputApplet() {
        this("localhost", 9093);
    }

    private void buildUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

        // ── TOP: Header ──────────────────────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout(8, 6));
        topPanel.setBackground(CARD_BG);
        topPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 50, 72), 1, true),
            new EmptyBorder(10, 14, 10, 14)
        ));

        JLabel titleLbl = new JLabel("⚡ POWER & THERMAL BENCH STIMULATOR");
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 15));
        titleLbl.setForeground(ACCENT_ORANGE);

        JLabel subLbl = new JLabel("Physical Load & Environmental Chamber Controls  •  Port " + port);
        subLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 12));
        subLbl.setForeground(TEXT_MUTED);

        statusLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        statusLabel.setForeground(ACCENT_RED);

        JPanel headerLeft = new JPanel(new GridLayout(2, 1, 0, 2));
        headerLeft.setOpaque(false);
        headerLeft.add(titleLbl);
        headerLeft.add(subLbl);

        topPanel.add(headerLeft, BorderLayout.CENTER);
        topPanel.add(statusLabel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // ── CENTER: Sliders & Scenarios ───────────────────────────────────────
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        centerPanel.add(buildLoadCard());
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(buildChamberCard());
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(buildVinCard());
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(buildScenarioCard());

        add(centerPanel, BorderLayout.CENTER);

        // ── BOTTOM: Terminal Bus Log ─────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.setBackground(CARD_BG);
        bottomPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 50, 72), 1, true),
            new EmptyBorder(8, 10, 8, 10)
        ));
        bottomPanel.setPreferredSize(new Dimension(100, 160));

        JLabel logHeader = new JLabel("Terminal Bus Outgoing Command Log (Port " + port + "):");
        logHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        logHeader.setForeground(TEXT_MUTED);

        logList.setBackground(new Color(12, 16, 24));
        logList.setForeground(new Color(130, 220, 160));
        logList.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        bottomPanel.add(logHeader, BorderLayout.NORTH);
        bottomPanel.add(scroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // ─── 1. Programmable Electronic DC Load ──────────────────────────────────
    private JPanel buildLoadCard() {
        JPanel card = createCard("🔋  Electronic DC Load Generator (0.0 A — 20.0 A)");

        loadSlider.setOpaque(false);
        loadSlider.setValue(0);

        loadValueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        loadValueLbl.setForeground(ACCENT_CYAN);

        loadSlider.addChangeListener(e -> {
            currentIout = loadSlider.getValue() / 10.0;
            double vPin = (currentIout / 20.0) * 3.3;
            loadValueLbl.setText(String.format(Locale.US, "%.1f A  (%.2f V)", currentIout, vPin));
            applyPhysicalStimulus();
        });

        JPanel presetRow = new JPanel(new GridLayout(1, 5, 4, 0));
        presetRow.setOpaque(false);
        presetRow.add(createPresetBtn("0A Idle", () -> loadSlider.setValue(0)));
        presetRow.add(createPresetBtn("5A Light", () -> loadSlider.setValue(50)));
        presetRow.add(createPresetBtn("10A Half", () -> loadSlider.setValue(100)));
        presetRow.add(createPresetBtn("15A Rated", () -> loadSlider.setValue(150)));
        presetRow.add(createPresetBtn("20A Max", () -> loadSlider.setValue(200)));

        JPanel mid = new JPanel(new BorderLayout(6, 0));
        mid.setOpaque(false);
        mid.add(loadSlider, BorderLayout.CENTER);
        mid.add(loadValueLbl, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.add(mid, BorderLayout.CENTER);
        content.add(presetRow, BorderLayout.SOUTH);

        card.add(content, BorderLayout.CENTER);
        return card;
    }

    // ─── 2. Environmental Thermal Chamber ────────────────────────────────────
    private JPanel buildChamberCard() {
        JPanel card = createCard("🌡  Thermal Environmental Chamber (25.0 °C — 85.0 °C)");

        chamberSlider.setOpaque(false);
        chamberSlider.setValue(25);

        chamberValueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        chamberValueLbl.setForeground(ACCENT_ORANGE);

        chamberSlider.addChangeListener(e -> {
            currentTamb = chamberSlider.getValue();
            double vPin = (currentTamb / 100.0) * 3.3;
            chamberValueLbl.setText(String.format(Locale.US, "%.1f °C  (%.2f V)", currentTamb, vPin));
            applyPhysicalStimulus();
        });

        JPanel presetRow = new JPanel(new GridLayout(1, 4, 4, 0));
        presetRow.setOpaque(false);
        presetRow.add(createPresetBtn("25°C Lab", () -> chamberSlider.setValue(25)));
        presetRow.add(createPresetBtn("45°C Warm", () -> chamberSlider.setValue(45)));
        presetRow.add(createPresetBtn("65°C Ind", () -> chamberSlider.setValue(65)));
        presetRow.add(createPresetBtn("85°C Oven", () -> chamberSlider.setValue(85)));

        JPanel mid = new JPanel(new BorderLayout(6, 0));
        mid.setOpaque(false);
        mid.add(chamberSlider, BorderLayout.CENTER);
        mid.add(chamberValueLbl, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.add(mid, BorderLayout.CENTER);
        content.add(presetRow, BorderLayout.SOUTH);

        card.add(content, BorderLayout.CENTER);
        return card;
    }

    // ─── 3. Input Bench DC Power Supply ──────────────────────────────────────
    private JPanel buildVinCard() {
        JPanel card = createCard("🔌  Benchtop DC Power Supply (18.0 V — 36.0 V)");

        vinSlider.setOpaque(false);
        vinSlider.setValue(240);

        vinValueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        vinValueLbl.setForeground(ACCENT_GREEN);

        vinSlider.addChangeListener(e -> {
            currentVin = vinSlider.getValue() / 10.0;
            double vPin = (currentVin / 36.0) * 3.3;
            vinValueLbl.setText(String.format(Locale.US, "%.1f V  (%.2f V)", currentVin, vPin));
            applyPhysicalStimulus();
        });

        JPanel presetRow = new JPanel(new GridLayout(1, 4, 4, 0));
        presetRow.setOpaque(false);
        presetRow.add(createPresetBtn("18V Low", () -> vinSlider.setValue(180)));
        presetRow.add(createPresetBtn("24V Nom", () -> vinSlider.setValue(240)));
        presetRow.add(createPresetBtn("30V High", () -> vinSlider.setValue(300)));
        presetRow.add(createPresetBtn("36V Max", () -> vinSlider.setValue(360)));

        JPanel mid = new JPanel(new BorderLayout(6, 0));
        mid.setOpaque(false);
        mid.add(vinSlider, BorderLayout.CENTER);
        mid.add(vinValueLbl, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.add(mid, BorderLayout.CENTER);
        content.add(presetRow, BorderLayout.SOUTH);

        card.add(content, BorderLayout.CENTER);
        return card;
    }

    // ─── 4. Automated Benchmark Scenarios ───────────────────────────────────
    private JPanel buildScenarioCard() {
        JPanel card = createCard("⚡  One-Click Benchmark Test Scenarios");

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);

        JButton idleBtn = createStyledButton("💤 1. Idle Baseline (0A, 25°C)", ACCENT_CYAN);
        idleBtn.addActionListener(e -> runScenario("IDLE"));

        JButton halfBtn = createStyledButton("📊 2. Nominal Load (10A, 35°C)", ACCENT_GREEN);
        halfBtn.addActionListener(e -> runScenario("NOMINAL"));

        JButton soakBtn = createStyledButton("🔥 3. Chamber Soak (15A, 65°C)", ACCENT_ORANGE);
        soakBtn.addActionListener(e -> runScenario("SOAK"));

        JButton tripBtn = createStyledButton("💥 4. Overload/OTP Trip (20A, 85°C)", ACCENT_RED);
        tripBtn.addActionListener(e -> runScenario("TRIP"));

        grid.add(idleBtn);
        grid.add(halfBtn);
        grid.add(soakBtn);
        grid.add(tripBtn);

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private void runScenario(String mode) {
        new Thread(() -> {
            switch (mode) {
                case "IDLE" -> {
                    log("Scenario: Idle Baseline (0A load, 25°C ambient)");
                    SwingUtilities.invokeLater(() -> {
                        loadSlider.setValue(0);
                        chamberSlider.setValue(25);
                        vinSlider.setValue(240);
                    });
                }
                case "NOMINAL" -> {
                    log("Scenario: Nominal 10A Load at 35°C ambient (Efficiency ~92%)");
                    SwingUtilities.invokeLater(() -> {
                        vinSlider.setValue(240);
                        chamberSlider.setValue(35);
                        loadSlider.setValue(100);
                    });
                }
                case "SOAK" -> {
                    log("Scenario: Thermal Chamber Soak (15A continuous, 65°C hot box)");
                    SwingUtilities.invokeLater(() -> {
                        vinSlider.setValue(240);
                        chamberSlider.setValue(65);
                        loadSlider.setValue(150);
                    });
                }
                case "TRIP" -> {
                    log("Scenario: Emergency Over-Temperature Trip Test (20A load @ 85°C)");
                    SwingUtilities.invokeLater(() -> {
                        vinSlider.setValue(240);
                        chamberSlider.setValue(85);
                        loadSlider.setValue(200);
                    });
                }
            }
        }).start();
    }

    // ─── Physics Simulation Engine & Voltage Injection ───────────────────────
    // Calculates the real-world thermal & electrical behavior of the PSU
    // and drives the terminal netlist pins.
    private void applyPhysicalStimulus() {
        if (!connected || out == null) return;

        // 1. Electrical relations:
        // Nominal Vout = 12.0V with small load droop (droop = 0.02V per Amp)
        double vout = Math.max(0.0, 12.0 - (currentIout * 0.025));
        double pout = vout * currentIout;

        // Switching converter efficiency curve
        double eff = (currentIout < 0.2) ? 0.0 : (0.86 + 0.08 * (currentIout / 20.0));
        double pin  = (eff > 0.01) ? (pout / eff) : 0.5;
        double iin  = (currentVin > 1.0) ? (pin / currentVin) : 0.0;
        double ploss = Math.max(0.0, pin - pout);

        // 2. Thermal dissipation physics:
        // Hotspots rise with Ploss and ambient temperature
        double tMosfet = currentTamb + (ploss * 2.8);
        double tCore   = currentTamb + (ploss * 2.2);
        double tDiode  = currentTamb + (ploss * 1.9);

        // 3. Scale to 0-3.3V ADC voltages
        double vPinVin    = Math.min(3.3, (currentVin / 36.0) * 3.3);
        double vPinIin    = Math.min(3.3, (iin / 12.0) * 3.3);
        double vPinVout   = Math.min(3.3, (vout / 15.0) * 3.3);
        double vPinIout   = Math.min(3.3, (currentIout / 20.0) * 3.3);

        double vPinTmosfet = Math.min(3.3, (tMosfet / 125.0) * 3.3);
        double vPinTcore   = Math.min(3.3, (tCore / 125.0) * 3.3);
        double vPinTdiode  = Math.min(3.3, (tDiode / 125.0) * 3.3);
        double vPinTamb    = Math.min(3.3, (currentTamb / 100.0) * 3.3);

        // Send SET commands to the boards on Terminal Bus (Port 2)
        sendTerminalSet("BenchPowerSource", "VIN_OUT", vPinVin);
        sendTerminalSet("BenchPowerSource", "IIN_SHUNT", vPinIin);

        sendTerminalSet("PowerSupplyUnderTest", "VOUT", vPinVout);
        sendTerminalSet("Prog_DC_Load", "IOUT_SENSE", vPinIout);

        sendTerminalSet("ThermocoupleArray", "T_MOSFET", vPinTmosfet);
        sendTerminalSet("ThermocoupleArray", "T_CORE", vPinTcore);
        sendTerminalSet("ThermocoupleArray", "T_DIODE", vPinTdiode);
        sendTerminalSet("ThermalChamber", "T_AMBIENT", vPinTamb);
    }

    // ─── Terminal Bus Networking ─────────────────────────────────────────────
    private void connect() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to " + host + ":" + port + "...", ACCENT_ORANGE);
                socket = new Socket(host, port);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                updateStatus("● CONNECTED (Port " + port + ")", ACCENT_GREEN);
                log("Connected to Terminal Bus at " + host + ":" + port);

                // Send initial values
                applyPhysicalStimulus();

                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String msg = line;
                    SwingUtilities.invokeLater(() -> log("← " + msg));
                }
            } catch (IOException e) {
                updateStatus("● DISCONNECTED", ACCENT_RED);
                log("[WARN] Connection failed: " + e.getMessage());
                connected = false;
            }
        }, "ThermalBenchTerminalClient").start();
    }

    private void sendTerminalSet(String board, String pin, double voltage) {
        if (out != null && connected) {
            String cmd = String.format(Locale.US, "SET %s %s %.3f", board, pin, voltage);
            out.println(cmd);
            log(String.format(Locale.US, "→ %s.%s = %.2f V", board, pin, voltage));
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
            if (logModel.size() > 40) logModel.removeElementAt(logModel.size() - 1);
        });
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────────
    private JPanel createCard(String title) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(CARD_BG);
        card.setBorder(new CompoundBorder(
            new LineBorder(new Color(36, 44, 64), 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
        titleLbl.setForeground(new Color(185, 200, 230));

        card.add(titleLbl, BorderLayout.NORTH);
        return card;
    }

    private JButton createPresetBtn(String label, Runnable action) {
        JButton btn = new JButton(label);
        btn.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        btn.setBackground(new Color(34, 42, 60));
        btn.setForeground(TEXT_MAIN);
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
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

    // ─── Entry Point ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9093;
        SwingUtilities.invokeLater(() -> new ThermalBenchInputApplet(host, port));
    }
}
