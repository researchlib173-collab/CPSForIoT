import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

// ============================================================================
// REGULATED DC POWER SUPPLY (RPS-2403) & 24V DC MOTOR FRONT-PANEL APPLET
// ============================================================================
// Realistic Laboratory Instrument Front-Panel:
//   - Regulated DC Power Supply supplying power to a 24V DC Motor
//   - Shared Coarse & Fine Knobs set with a Mode Toggle Switch (VOLTAGE / CURRENT)
//   - Animated 24V DC Motor running proportionally to voltage & current load
//   - Dynamic Thermal Dissipation & Temperature Sensor Simulation
//   - Connects to the CPS Workbench Sensor Bus on localhost:9091
// ============================================================================
public class ThermalBenchInputApplet extends JFrame {

    public static final String FONT_FAMILY   = "Space Mono";
    private static final Color BG_CHASSIS    = new Color(20, 24, 34);
    private static final Color PANEL_DARK    = new Color(14, 17, 24);
    private static final Color PANEL_BEZEL   = new Color(30, 36, 52);
    private static final Color ACCENT_CYAN   = new Color(0, 230, 255);
    private static final Color ACCENT_AMBER  = new Color(255, 175, 40);
    private static final Color ACCENT_GREEN  = new Color(45, 220, 115);
    private static final Color ACCENT_RED    = new Color(245, 70, 70);
    private static final Color TEXT_MAIN     = new Color(235, 240, 250);
    private static final Color TEXT_MUTED    = new Color(130, 140, 165);

    public enum KnobControlMode {
        VOLTAGE,
        CURRENT
    }

    // ─── Network Settings ────────────────────────────────────────────────────
    private final String host;
    private final int port;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean connected = false;

    // ─── Instrument State ────────────────────────────────────────────────────
    private KnobControlMode controlMode = KnobControlMode.VOLTAGE;
    private boolean outputEnabled = true;

    private double targetVoltage = 24.0; // 0.0V to 26.0V DC (INA219 target bus)
    private double targetCurrent = 2.50; // 0.00A to 3.20A DC (0.1Ω shunt)
    private double ambientTemp   = 25.0; // Baseline room temperature °C

    // Computed Electrical & Mechanical Properties
    private double actualVoltage = 24.0;
    private double actualCurrent = 2.50;
    private double powerWatts    = 60.0;
    private double motorRpm      = 2850.0;
    private double motorTorqueNm = 0.18;
    private double simulatedTemp = 25.0;
    private double heatLossWatts = 9.8;

    // ─── UI Display Components ───────────────────────────────────────────────
    private final JLabel statusLabel       = new JLabel("● DISCONNECTED", SwingConstants.CENTER);
    private final JLabel voltDisplayLbl    = new JLabel("24.00 V", SwingConstants.CENTER);
    private final JLabel currDisplayLbl    = new JLabel(" 2.50 A", SwingConstants.CENTER);
    private final JLabel powerDisplayLbl   = new JLabel("60.00 W", SwingConstants.CENTER);
    private final JLabel modeStatusBadge   = new JLabel("● ACTIVE MODE: VOLTAGE CONTROL (Coarse: ±1.0V | Fine: ±0.05V)", SwingConstants.CENTER);

    private final JButton modeSwitchBtn    = new JButton("⇄  SWITCH TO CURRENT CONTROL");
    private final JButton outputToggleBtn  = new JButton("⏻  OUTPUT: ON");

    private RotaryKnob coarseKnob;
    private RotaryKnob fineKnob;
    private MotorSimulationPanel motorPanel;

    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    private final JList<String> logList = new JList<>(logModel);

    // Animation & Physics Loop (30 FPS)
    private Timer animationTimer;

    public ThermalBenchInputApplet(String host, int port) {
        super("Regulated DC Power Supply (RPS-2403) & 24V DC Motor Simulator");
        this.host = host;
        this.port = port;

        buildInstrumentUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 780);
        setMinimumSize(new Dimension(880, 680));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_CHASSIS);
        setVisible(true);

        // Start Physics and Motor Rotor Animation Loop
        animationTimer = new Timer(33, e -> {
            updatePhysicsTick();
            if (motorPanel != null) motorPanel.repaint();
        });
        animationTimer.start();

        SwingUtilities.invokeLater(this::connect);
    }

    public ThermalBenchInputApplet() {
        this("localhost", CpsDomain.PORT_SENSORS);
    }

    private void buildInstrumentUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

        // ── TOP: Chassis Instrument Banner ───────────────────────────────────
        JPanel topHeader = new JPanel(new BorderLayout(10, 0));
        topHeader.setBackground(PANEL_BEZEL);
        topHeader.setBorder(new CompoundBorder(
            new LineBorder(new Color(50, 60, 85), 1, true),
            new EmptyBorder(10, 16, 10, 16)
        ));

        JLabel brandLbl = new JLabel("★ PRECISION REGULATED DC POWER SUPPLY — MODEL RPS-2403");
        brandLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        brandLbl.setForeground(ACCENT_CYAN);

        JLabel subLbl = new JLabel("24V DC MOTOR SIMULATOR • DYNAMIC THERMAL INJECTION • PORT " + port);
        subLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 11));
        subLbl.setForeground(TEXT_MUTED);

        JPanel brandBox = new JPanel(new GridLayout(2, 1, 0, 2));
        brandBox.setOpaque(false);
        brandBox.add(brandLbl);
        brandBox.add(subLbl);

        statusLabel.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        statusLabel.setForeground(ACCENT_RED);

        topHeader.add(brandBox, BorderLayout.CENTER);
        topHeader.add(statusLabel, BorderLayout.EAST);
        add(topHeader, BorderLayout.NORTH);

        // ── CENTER: Main Instrument Faceplate (Left: Supply / Right: Motor) ──
        JPanel mainFaceplate = new JPanel(new GridLayout(1, 2, 12, 0));
        mainFaceplate.setOpaque(false);

        mainFaceplate.add(buildPowerSupplyChassis());
        mainFaceplate.add(buildMotorStationChassis());

        add(mainFaceplate, BorderLayout.CENTER);

        // ── BOTTOM: Netlist Bus Command Log ──────────────────────────────────
        JPanel bottomLog = new JPanel(new BorderLayout(4, 4));
        bottomLog.setBackground(PANEL_BEZEL);
        bottomLog.setBorder(new CompoundBorder(
            new LineBorder(new Color(45, 55, 78), 1, true),
            new EmptyBorder(8, 12, 8, 12)
        ));
        bottomLog.setPreferredSize(new Dimension(100, 130));

        JLabel logTitle = new JLabel("Terminal Bus Outgoing Stimulus Commands (localhost:" + port + "):");
        logTitle.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        logTitle.setForeground(TEXT_MUTED);

        logList.setBackground(new Color(10, 14, 20));
        logList.setForeground(new Color(120, 225, 160));
        logList.setFont(new Font(FONT_FAMILY, Font.PLAIN, 10));
        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        bottomLog.add(logTitle, BorderLayout.NORTH);
        bottomLog.add(scroll, BorderLayout.CENTER);
        add(bottomLog, BorderLayout.SOUTH);
    }

    // =========================================================================
    // 1. LEFT PANEL: REGULATED POWER SUPPLY INSTRUMENT FACEPLATE
    // =========================================================================
    private JPanel buildPowerSupplyChassis() {
        JPanel chassis = new JPanel();
        chassis.setLayout(new BoxLayout(chassis, BoxLayout.Y_AXIS));
        chassis.setBackground(PANEL_DARK);
        chassis.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 50, 72), 2, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        // 1. Digital LED Dual Meter Display Panel
        JPanel metersCard = new JPanel(new GridLayout(1, 3, 8, 0));
        metersCard.setBackground(new Color(8, 10, 15));
        metersCard.setBorder(new CompoundBorder(
            new LineBorder(new Color(30, 40, 60), 2, true),
            new EmptyBorder(8, 10, 8, 10)
        ));

        metersCard.add(createMeterBox("VOLTAGE (V)", voltDisplayLbl, ACCENT_CYAN));
        metersCard.add(createMeterBox("CURRENT (A)", currDisplayLbl, ACCENT_AMBER));
        metersCard.add(createMeterBox("POWER (W)", powerDisplayLbl, ACCENT_GREEN));

        // 2. Active Mode Status Strip
        modeStatusBadge.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        modeStatusBadge.setForeground(ACCENT_CYAN);
        modeStatusBadge.setBorder(new EmptyBorder(6, 4, 6, 4));

        // 3. Mode Toggle Switch & Output Enable
        JPanel switchBar = new JPanel(new GridLayout(1, 2, 8, 0));
        switchBar.setOpaque(false);

        modeSwitchBtn.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        modeSwitchBtn.setForeground(TEXT_MAIN);
        modeSwitchBtn.setBackground(new Color(32, 42, 62));
        modeSwitchBtn.setFocusPainted(false);
        modeSwitchBtn.setBorder(new LineBorder(ACCENT_CYAN, 1, true));
        modeSwitchBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        modeSwitchBtn.addActionListener(e -> toggleControlMode());

        outputToggleBtn.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        outputToggleBtn.setForeground(ACCENT_GREEN);
        outputToggleBtn.setBackground(new Color(24, 38, 30));
        outputToggleBtn.setFocusPainted(false);
        outputToggleBtn.setBorder(new LineBorder(ACCENT_GREEN, 1, true));
        outputToggleBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        outputToggleBtn.addActionListener(e -> toggleOutput());

        switchBar.add(modeSwitchBtn);
        switchBar.add(outputToggleBtn);

        // 4. Coarse & Fine Rotary Knobs Section
        JPanel knobsContainer = new JPanel(new GridLayout(1, 2, 12, 0));
        knobsContainer.setBackground(new Color(16, 20, 28));
        knobsContainer.setBorder(new CompoundBorder(
            new LineBorder(new Color(35, 45, 65), 1, true),
            new EmptyBorder(8, 10, 8, 10)
        ));

        coarseKnob = new RotaryKnob("COARSE", this::onCoarseKnobStep);
        fineKnob   = new RotaryKnob("FINE", this::onFineKnobStep);

        knobsContainer.add(createKnobWrapper(coarseKnob, "COARSE", () -> onCoarseKnobStep(-1), () -> onCoarseKnobStep(1)));
        knobsContainer.add(createKnobWrapper(fineKnob, "FINE", () -> onFineKnobStep(-1), () -> onFineKnobStep(1)));

        // 5. Direct Quick Presets for Rapid Bench Testing
        JPanel presetRow = new JPanel(new GridLayout(1, 5, 4, 0));
        presetRow.setOpaque(false);
        presetRow.add(createPresetBtn("0V Idle",  0.0,  0.0));
        presetRow.add(createPresetBtn("5V Logic", 5.0,  0.50));
        presetRow.add(createPresetBtn("12V Half", 12.0, 1.25));
        presetRow.add(createPresetBtn("24V Motor",24.0, 2.50));
        presetRow.add(createPresetBtn("26V Max",  26.0, 3.20));

        // 6. Realistic Binding Posts & Ground Terminals
        JPanel terminalsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 4));
        terminalsRow.setOpaque(false);
        terminalsRow.add(createBindingPost("+ [RED]", new Color(240, 60, 60)));
        terminalsRow.add(createBindingPost("GND [GRN]", new Color(50, 200, 100)));
        terminalsRow.add(createBindingPost("- [BLK]", new Color(50, 55, 65)));

        // Assemble Chassis
        chassis.add(metersCard);
        chassis.add(Box.createVerticalStrut(6));
        chassis.add(modeStatusBadge);
        chassis.add(Box.createVerticalStrut(4));
        chassis.add(switchBar);
        chassis.add(Box.createVerticalStrut(8));
        chassis.add(knobsContainer);
        chassis.add(Box.createVerticalStrut(8));
        chassis.add(presetRow);
        chassis.add(Box.createVerticalStrut(4));
        chassis.add(terminalsRow);

        return chassis;
    }

    private JPanel createMeterBox(String title, JLabel valueLbl, Color accent) {
        JPanel box = new JPanel(new BorderLayout(0, 4));
        box.setOpaque(false);

        JLabel tLbl = new JLabel(title, SwingConstants.CENTER);
        tLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        tLbl.setForeground(TEXT_MUTED);

        valueLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 22));
        valueLbl.setForeground(accent);

        box.add(tLbl, BorderLayout.NORTH);
        box.add(valueLbl, BorderLayout.CENTER);
        return box;
    }

    private JPanel createKnobWrapper(RotaryKnob knob, String title, Runnable onDec, Runnable onInc) {
        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.setOpaque(false);

        wrap.add(knob, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new GridLayout(1, 2, 4, 0));
        btnRow.setOpaque(false);

        JButton decBtn = new JButton("◀ -");
        decBtn.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        decBtn.setForeground(TEXT_MAIN);
        decBtn.setBackground(new Color(28, 34, 48));
        decBtn.setFocusPainted(false);
        decBtn.setBorder(new LineBorder(new Color(50, 62, 86), 1, true));
        decBtn.addActionListener(e -> onDec.run());

        JButton incBtn = new JButton("+ ▶");
        incBtn.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
        incBtn.setForeground(TEXT_MAIN);
        incBtn.setBackground(new Color(28, 34, 48));
        incBtn.setFocusPainted(false);
        incBtn.setBorder(new LineBorder(new Color(50, 62, 86), 1, true));
        incBtn.addActionListener(e -> onInc.run());

        btnRow.add(decBtn);
        btnRow.add(incBtn);
        wrap.add(btnRow, BorderLayout.SOUTH);

        return wrap;
    }

    private JPanel createBindingPost(String label, Color color) {
        JPanel post = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        post.setOpaque(false);

        JLabel dot = new JLabel("●");
        dot.setFont(new Font(FONT_FAMILY, Font.BOLD, 14));
        dot.setForeground(color);

        JLabel txt = new JLabel(label);
        txt.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        txt.setForeground(TEXT_MUTED);

        post.add(dot);
        post.add(txt);
        return post;
    }

    private JButton createPresetBtn(String label, double v, double i) {
        JButton btn = new JButton(label);
        btn.setFont(new Font(FONT_FAMILY, Font.BOLD, 9));
        btn.setForeground(TEXT_MAIN);
        btn.setBackground(new Color(26, 32, 46));
        btn.setFocusPainted(false);
        btn.setBorder(new LineBorder(new Color(45, 55, 78), 1, true));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> {
            targetVoltage = v;
            targetCurrent = i;
            updateDisplayValues();
            applyPhysicalStimulus();
            log(String.format(Locale.US, "Preset Applied: %.1fV, %.2fA", v, i));
        });
        return btn;
    }

    // =========================================================================
    // 2. RIGHT PANEL: 24V DC MOTOR SIMULATION & DYNAMIC THERMAL STATION
    // =========================================================================
    private JPanel buildMotorStationChassis() {
        JPanel station = new JPanel(new BorderLayout(0, 8));
        station.setBackground(PANEL_DARK);
        station.setBorder(new CompoundBorder(
            new LineBorder(new Color(40, 50, 72), 2, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        // Station Header
        JLabel stHeader = new JLabel("⚡ 24V HIGH-TORQUE DC MOTOR & THERMAL LOAD", SwingConstants.CENTER);
        stHeader.setFont(new Font(FONT_FAMILY, Font.BOLD, 11));
        stHeader.setForeground(ACCENT_AMBER);
        station.add(stHeader, BorderLayout.NORTH);

        // Animated Graphical Motor Viewport
        motorPanel = new MotorSimulationPanel();
        station.add(motorPanel, BorderLayout.CENTER);

        // Thermal & Electrical Telemetry Cards at Bottom
        JPanel statsCard = new JPanel(new GridLayout(2, 2, 6, 6));
        statsCard.setOpaque(false);

        statsCard.add(createTelemetryBox("MOTOR SPEED (RPM)", () -> String.format(Locale.US, "%.0f RPM", motorRpm), ACCENT_CYAN));
        statsCard.add(createTelemetryBox("TORQUE (N·m)", () -> String.format(Locale.US, "%.2f N·m", motorTorqueNm), ACCENT_GREEN));
        statsCard.add(createTelemetryBox("DISSIPATED HEAT", () -> String.format(Locale.US, "%.1f W", heatLossWatts), ACCENT_AMBER));
        statsCard.add(createTelemetryBox("SENSOR TEMP (°C)", () -> String.format(Locale.US, "%.1f °C", simulatedTemp), ACCENT_RED));

        station.add(statsCard, BorderLayout.SOUTH);
        return station;
    }

    private JPanel createTelemetryBox(String label, java.util.function.Supplier<String> supplier, Color color) {
        JPanel box = new JPanel(new BorderLayout(0, 2));
        box.setBackground(new Color(16, 20, 30));
        box.setBorder(new CompoundBorder(
            new LineBorder(new Color(32, 40, 58), 1, true),
            new EmptyBorder(4, 8, 4, 8)
        ));

        JLabel titleLbl = new JLabel(label);
        titleLbl.setFont(new Font(FONT_FAMILY, Font.PLAIN, 9));
        titleLbl.setForeground(TEXT_MUTED);

        JLabel valLbl = new JLabel(supplier.get());
        valLbl.setFont(new Font(FONT_FAMILY, Font.BOLD, 13));
        valLbl.setForeground(color);

        // Dynamic updater
        Timer t = new Timer(100, e -> valLbl.setText(supplier.get()));
        t.start();

        box.add(titleLbl, BorderLayout.NORTH);
        box.add(valLbl, BorderLayout.CENTER);
        return box;
    }

    // =========================================================================
    // 3. KNOB CONTROL LOGIC & MODE TOGGLE SWITCH
    // =========================================================================
    private void toggleControlMode() {
        if (controlMode == KnobControlMode.VOLTAGE) {
            controlMode = KnobControlMode.CURRENT;
            modeSwitchBtn.setText("⇄  SWITCH TO VOLTAGE CONTROL");
            modeSwitchBtn.setBorder(new LineBorder(ACCENT_AMBER, 1, true));
            modeStatusBadge.setText("● ACTIVE MODE: CURRENT CONTROL (Coarse: ±0.20A | Fine: ±0.01A)");
            modeStatusBadge.setForeground(ACCENT_AMBER);
            coarseKnob.setValueRatio(targetCurrent / 3.20);
            fineKnob.setValueRatio((targetCurrent % 0.20) / 0.20);
            log("Switched to CURRENT CONTROL Mode.");
        } else {
            controlMode = KnobControlMode.VOLTAGE;
            modeSwitchBtn.setText("⇄  SWITCH TO CURRENT CONTROL");
            modeSwitchBtn.setBorder(new LineBorder(ACCENT_CYAN, 1, true));
            modeStatusBadge.setText("● ACTIVE MODE: VOLTAGE CONTROL (Coarse: ±1.0V | Fine: ±0.05V)");
            modeStatusBadge.setForeground(ACCENT_CYAN);
            coarseKnob.setValueRatio(targetVoltage / 26.0);
            fineKnob.setValueRatio((targetVoltage % 1.0) / 1.0);
            log("Switched to VOLTAGE CONTROL Mode.");
        }
    }

    private void toggleOutput() {
        outputEnabled = !outputEnabled;
        if (outputEnabled) {
            outputToggleBtn.setText("⏻  OUTPUT: ON");
            outputToggleBtn.setForeground(ACCENT_GREEN);
            outputToggleBtn.setBackground(new Color(24, 38, 30));
            outputToggleBtn.setBorder(new LineBorder(ACCENT_GREEN, 1, true));
            log("Output Enabled: DC Power delivered to motor.");
        } else {
            outputToggleBtn.setText("⏻  OUTPUT: OFF");
            outputToggleBtn.setForeground(TEXT_MUTED);
            outputToggleBtn.setBackground(new Color(30, 32, 40));
            outputToggleBtn.setBorder(new LineBorder(new Color(60, 65, 80), 1, true));
            log("Output Disabled: 0V, 0A delivered.");
        }
        updateDisplayValues();
        applyPhysicalStimulus();
    }

    private void onCoarseKnobStep(double direction) {
        if (controlMode == KnobControlMode.VOLTAGE) {
            targetVoltage = Math.min(26.0, Math.max(0.0, targetVoltage + (direction * 1.0)));
            coarseKnob.setValueRatio(targetVoltage / 26.0);
        } else {
            targetCurrent = Math.min(3.20, Math.max(0.0, targetCurrent + (direction * 0.20)));
            coarseKnob.setValueRatio(targetCurrent / 3.20);
        }
        updateDisplayValues();
        applyPhysicalStimulus();
    }

    private void onFineKnobStep(double direction) {
        if (controlMode == KnobControlMode.VOLTAGE) {
            targetVoltage = Math.min(26.0, Math.max(0.0, targetVoltage + (direction * 0.05)));
            fineKnob.setValueRatio((targetVoltage % 1.0) / 1.0);
        } else {
            targetCurrent = Math.min(3.20, Math.max(0.0, targetCurrent + (direction * 0.01)));
            fineKnob.setValueRatio((targetCurrent % 0.20) / 0.20);
        }
        updateDisplayValues();
        applyPhysicalStimulus();
    }

    private void updateDisplayValues() {
        if (outputEnabled) {
            actualVoltage = targetVoltage;
            actualCurrent = targetCurrent;
        } else {
            actualVoltage = 0.0;
            actualCurrent = 0.0;
        }

        powerWatts = actualVoltage * actualCurrent;
        voltDisplayLbl.setText(String.format(Locale.US, "%5.2f V", actualVoltage));
        currDisplayLbl.setText(String.format(Locale.US, "%5.2f A", actualCurrent));
        powerDisplayLbl.setText(String.format(Locale.US, "%5.2f W", powerWatts));
    }

    // =========================================================================
    // 4. REAL-TIME MATHEMATICAL PHYSICS & THERMAL ENGINE
    // =========================================================================
    // Dynamic relations for 24V DC Motor:
    //   Back-EMF: E_b = V_bus - I * R_armature
    //   Motor Speed: RPM = E_b / k_e (max ~3200 RPM at 24V)
    //   Torque: T = k_t * I_load
    //   Heat Dissipation: P_loss = (I^2 * R_armature) + (0.06 * P_elec)
    //   Thermal Rise: ΔT = P_loss * R_th (1.75 °C/W)
    //   Sensor Temperature: T_sensor = T_ambient + ΔT
    private int syncTickCounter = 0;

    private void updatePhysicsTick() {
        if (outputEnabled && actualVoltage > 0.5 && actualCurrent > 0.05) {
            double rArmature = 1.1; // 1.1 ohm armature resistance
            double ke = 0.0075;     // 0.0075 V/RPM
            double eb = Math.max(0.0, actualVoltage - (actualCurrent * rArmature));
            double targetRpm = Math.min(3200.0, eb / ke);

            // Smooth inertia acceleration toward target RPM
            motorRpm += (targetRpm - motorRpm) * 0.15;
            motorTorqueNm = 0.072 * actualCurrent;

            double pCopper = (actualCurrent * actualCurrent) * rArmature;
            double pCore = powerWatts * 0.06;
            heatLossWatts = pCopper + pCore;

            double rTh = 2.4; // °C per Watt thermal resistance
            double deltaT = heatLossWatts * rTh;
            double targetTemp = ambientTemp + deltaT;

            // Authentic physical thermal mass integration (tau ~ 16 seconds):
            // Smooth realistic heating curve (degree-by-degree instead of rapid jumping)
            double rateHeating = 0.0030;
            simulatedTemp += (targetTemp - simulatedTemp) * rateHeating;
        } else {
            // Spin down when power is off
            motorRpm = Math.max(0.0, motorRpm * 0.92);
            motorTorqueNm = Math.max(0.0, motorTorqueNm * 0.90);
            heatLossWatts = Math.max(0.0, heatLossWatts * 0.90);
            // Natural convective thermal cooling (tau ~ 25 seconds)
            double rateCooling = 0.0018;
            simulatedTemp += (ambientTemp - simulatedTemp) * rateCooling;
        }

        // Continuous physical stimulus streaming to CPS Workbench Netlist Bus (~500ms heartbeat)
        syncTickCounter++;
        if (syncTickCounter >= 15) { // 15 ticks * 33ms ~ 500ms (2 Hz)
            syncTickCounter = 0;
            streamPhysicalStimulus();
        }
    }

    // =========================================================================
    // 5. TERMINAL NETLIST BUS INJECTION (localhost:9093)
    // =========================================================================
    /**
     * Streams continuous physical state (Voltage, Current, Temperature) to the CPS Netlist Bus silently.
     */
    private void streamPhysicalStimulus() {
        if (!connected || out == null) return;
        try {
            // 1. INA219 Bus Voltage scaled: 0V - 26V DC -> 0 - 3.3V ADC
            double vPinBus = Math.min(3.3, (actualVoltage / 26.0) * 3.3);
            // 2. INA219 Load Current scaled: 0A - 3.2A DC -> 0 - 3.3V ADC
            double vPinCurrent = Math.min(3.3, (actualCurrent / 3.20) * 3.3);
            // 3. Dynamic Temperature Sensor scaled: 0°C - 100°C -> 0 - 3.3V ADC
            double vPinTemp = Math.min(3.3, (simulatedTemp / 100.0) * 3.3);

            out.printf(Locale.US, "SET INA219_Sensor VIN_PLUS %.3f%n", vPinBus);
            out.printf(Locale.US, "SET INA219_Sensor VIN_MINUS %.3f%n", vPinCurrent);
            out.printf(Locale.US, "SET TempSensor TEMP_OUT %.3f%n", vPinTemp);
        } catch (Exception ignored) {}
    }

    private void applyPhysicalStimulus() {
        streamPhysicalStimulus();
        log(String.format(Locale.US, "Applied: %.1fV, %.2fA | T_sensor=%.1f°C",
            actualVoltage, actualCurrent, simulatedTemp));
    }

    private void connect() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to " + host + ":" + port + "...", ACCENT_AMBER);
                socket = new Socket(host, port);
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                connected = true;
                updateStatus("● CONNECTED (Port " + port + ")", ACCENT_GREEN);
                log("Connected to CPS Workbench Terminal Netlist Bus on " + host + ":" + port);

                // Send initial state
                updateDisplayValues();
                applyPhysicalStimulus();

                String line;
                while (connected && (line = in.readLine()) != null) {
                    final String msg = line;
                    // Only log error alerts or initial connection events in UI to prevent log flooding
                    if (msg.contains("ERR") || msg.contains("CONNECTED")) {
                        SwingUtilities.invokeLater(() -> log("← " + msg));
                    }
                }
            } catch (IOException e) {
                updateStatus("● DISCONNECTED", ACCENT_RED);
                log("[WARN] Connection failed: " + e.getMessage());
                connected = false;
            }
        }, "ThermalBenchSupplyClient").start();
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

    // =========================================================================
    // 6. CUSTOM WIDGET: ROTARY KNOB PANEL
    // =========================================================================
    public static class RotaryKnob extends JPanel {
        private double valueRatio = 0.5; // 0.0 to 1.0
        private final String label;
        private final Consumer<Double> onDelta;
        private int lastMouseY;
        private int lastMouseX;

        public RotaryKnob(String label, Consumer<Double> onDelta) {
            this.label = label;
            this.onDelta = onDelta;
            setPreferredSize(new Dimension(100, 110));
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastMouseX = e.getX();
                    lastMouseY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int dy = lastMouseY - e.getY(); // Dragging up = positive
                    int dx = e.getX() - lastMouseX;
                    int delta = (Math.abs(dy) > Math.abs(dx)) ? dy : dx;
                    if (Math.abs(delta) >= 3) {
                        double step = (delta > 0 ? 1.0 : -1.0);
                        if (onDelta != null) onDelta.accept(step);
                        lastMouseX = e.getX();
                        lastMouseY = e.getY();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double step = -e.getWheelRotation();
                    if (onDelta != null) onDelta.accept(step);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        public void setValueRatio(double ratio) {
            this.valueRatio = Math.min(1.0, Math.max(0.0, ratio));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int cx = getWidth() / 2;
            int cy = 46;
            int r = 32;

            // 1. Tick Marks around 270-degree arc (-135° to +135°)
            int numTicks = 11;
            g2.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < numTicks; i++) {
                double frac = (double) i / (numTicks - 1);
                double angleDeg = -135.0 + (frac * 270.0);
                double rad = Math.toRadians(angleDeg - 90.0);

                int x1 = (int) (cx + Math.cos(rad) * (r + 4));
                int y1 = (int) (cy + Math.sin(rad) * (r + 4));
                int x2 = (int) (cx + Math.cos(rad) * (r + 8));
                int y2 = (int) (cy + Math.sin(rad) * (r + 8));

                g2.setColor(frac <= valueRatio ? ACCENT_CYAN : new Color(55, 65, 85));
                g2.drawLine(x1, y1, x2, y2);
            }

            // 2. Outer Bezel Ring
            g2.setColor(new Color(25, 30, 42));
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.setColor(new Color(60, 75, 100));
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawOval(cx - r, cy - r, r * 2, r * 2);

            // 3. Fluted Metallic Knob Body
            GradientPaint gp = new GradientPaint(cx - r, cy - r, new Color(48, 56, 75), cx + r, cy + r, new Color(22, 26, 36));
            g2.setPaint(gp);
            g2.fillOval(cx - r + 3, cy - r + 3, (r - 3) * 2, (r - 3) * 2);

            // 4. Center Cap
            g2.setColor(new Color(15, 18, 25));
            g2.fillOval(cx - 14, cy - 14, 28, 28);
            g2.setColor(new Color(80, 95, 125));
            g2.drawOval(cx - 14, cy - 14, 28, 28);

            // 5. Knob Pointer Notch
            double pointerDeg = -135.0 + (valueRatio * 270.0);
            double pointerRad = Math.toRadians(pointerDeg - 90.0);
            int px1 = (int) (cx + Math.cos(pointerRad) * 12);
            int py1 = (int) (cy + Math.sin(pointerRad) * 12);
            int px2 = (int) (cx + Math.cos(pointerRad) * (r - 4));
            int py2 = (int) (cy + Math.sin(pointerRad) * (r - 4));

            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(px1, py1, px2, py2);

            // 6. Label Under Knob
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 10));
            g2.setColor(TEXT_MAIN);
            int tw = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, cx - (tw / 2), cy + r + 18);

            g2.dispose();
        }
    }

    // =========================================================================
    // 7. CUSTOM WIDGET: ANIMATED 24V DC MOTOR SIMULATION
    // =========================================================================
    public class MotorSimulationPanel extends JPanel {
        private double rotorAngleDeg = 0.0;

        public MotorSimulationPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2 - 10;
            int radius = Math.min(w, h) / 3;

            // Increment rotor rotation angle
            rotorAngleDeg = (rotorAngleDeg + (motorRpm / 60.0) * (360.0 / 30.0)) % 360.0;

            // 1. Dynamic Thermal Heat Aura behind motor
            float heatFrac = (float) Math.min(1.0, Math.max(0.0, (simulatedTemp - 25.0) / 45.0));
            Color auraCore = new Color(255, 60 + (int)(140 * (1.0f - heatFrac)), 40, (int)(40 + 130 * heatFrac));
            RadialGradientPaint aura = new RadialGradientPaint(
                new Point2D.Float(cx, cy), radius + 40,
                new float[]{0.0f, 0.6f, 1.0f},
                new Color[]{auraCore, new Color(auraCore.getRed(), auraCore.getGreen(), 40, 30), new Color(0, 0, 0, 0)}
            );
            g2.setPaint(aura);
            g2.fillOval(cx - radius - 40, cy - radius - 40, (radius + 40) * 2, (radius + 40) * 2);

            // 2. Heavy Industrial Motor Stator Housing
            g2.setColor(new Color(24, 30, 42));
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            g2.setColor(new Color(50, 65, 90));
            g2.setStroke(new BasicStroke(3.0f));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // 3. Stator Mounting Feet & Heat Dissipation Fins
            g2.setColor(new Color(36, 45, 65));
            for (int i = 0; i < 12; i++) {
                double rad = Math.toRadians(i * 30.0);
                int fx1 = (int) (cx + Math.cos(rad) * (radius - 2));
                int fy1 = (int) (cy + Math.sin(rad) * (radius - 2));
                int fx2 = (int) (cx + Math.cos(rad) * (radius + 10));
                int fy2 = (int) (cy + Math.sin(rad) * (radius + 10));
                g2.drawLine(fx1, fy1, fx2, fy2);
            }

            // 4. Stator Magnetic Poles (North Red, South Blue)
            g2.setColor(new Color(200, 50, 50, 180));
            g2.fillArc(cx - radius + 8, cy - radius + 8, (radius - 8) * 2, (radius - 8) * 2, 45, 90);
            g2.setColor(new Color(40, 100, 200, 180));
            g2.fillArc(cx - radius + 8, cy - radius + 8, (radius - 8) * 2, (radius - 8) * 2, 225, 90);

            // 5. Armature Core
            int rArm = radius - 26;
            g2.setColor(new Color(15, 18, 25));
            g2.fillOval(cx - rArm, cy - rArm, rArm * 2, rArm * 2);

            // 6. Rotating Rotor & 4-Blade Cooling Impeller Fan
            AffineTransform oldTx = g2.getTransform();
            g2.rotate(Math.toRadians(rotorAngleDeg), cx, cy);

            // Fan Blades
            g2.setColor(new Color(160, 175, 200, 220));
            for (int b = 0; b < 4; b++) {
                g2.rotate(Math.toRadians(90.0), cx, cy);
                Path2D blade = new Path2D.Double();
                blade.moveTo(cx, cy);
                blade.curveTo(cx + 8, cy - (rArm * 0.4), cx + 18, cy - (rArm * 0.8), cx, cy - rArm + 4);
                blade.curveTo(cx - 12, cy - (rArm * 0.8), cx - 6, cy - (rArm * 0.4), cx, cy);
                g2.fill(blade);
            }
            g2.setTransform(oldTx);

            // 7. Shaft Bearing & Brass Hub
            g2.setColor(new Color(212, 175, 55)); // Brass bushing
            g2.fillOval(cx - 12, cy - 12, 24, 24);
            g2.setColor(new Color(30, 35, 45));
            g2.fillOval(cx - 5, cy - 5, 10, 10); // Center steel shaft

            // 8. Motor Label & Status Banner
            g2.setFont(new Font(FONT_FAMILY, Font.BOLD, 12));
            g2.setColor(motorRpm > 50 ? ACCENT_GREEN : TEXT_MUTED);
            String stateStr = motorRpm > 50 ? String.format(Locale.US, "RUNNING: %.0f RPM", motorRpm) : "MOTOR STOPPED";
            int tw = g2.getFontMetrics().stringWidth(stateStr);
            g2.drawString(stateStr, cx - (tw / 2), cy + radius + 32);

            g2.dispose();
        }
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = -1;
        if (args.length >= 2) {
            host = args[0];
            port = Integer.parseInt(args[1]);
        } else if (args.length == 1 && !args[0].equalsIgnoreCase("--prompt")) {
            port = Integer.parseInt(args[0]);
        }

        if (port <= 0) {
            String pStr = JOptionPane.showInputDialog(null,
                "Enter Physical Sensors Port noted down from the backend terminal:\n(Leave blank to exit)",
                "Thermal Bench Input — Port Input",
                JOptionPane.QUESTION_MESSAGE);
            if (pStr == null || pStr.trim().isEmpty()) return;
            try { port = Integer.parseInt(pStr.trim()); } catch (Exception e) { return; }
        }

        final String fHost = host;
        final int fPort = port;
        SwingUtilities.invokeLater(() -> new ThermalBenchInputApplet(fHost, fPort));
    }
}
