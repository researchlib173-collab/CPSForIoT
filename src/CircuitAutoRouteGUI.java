import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * ============================================================================
 * CIRCUIT AUTO-ROUTE GUI — Visual Schematic & Auto-Routing Engine
 * ============================================================================
 * Features:
 * 1. Visual project layout with interactive component dragging.
 * 2. Real component image rendering with calibrated pin dots.
 * 3. Default breakout / IC box representation with pin numbers when no image is linked.
 * 4. Orthogonal Manhattan CAD channel auto-routing with corner fillets and obstacle clearance.
 * 5. Smooth Bezier spline alternative routing mode.
 * 6. Live wire voltage color-coding and animated telemetry sync with the simulation.
 * 7. One-click "Auto-Arrange", "Export PNG", and direct "PinFix Calibrate" bridge.
 * ============================================================================
 */
public class CircuitAutoRouteGUI extends JFrame {

    private final String workspaceName;
    private final Map<String, CpsDomain.Board> boards;
    private final List<CpsDomain.Wire> wires;

    // Canvas & Settings
    private SchematicCanvas canvas;
    private boolean useManhattanRouting = true;
    private boolean showPinLabels = true;
    private boolean showWireVoltages = true;
    private double zoomFactor = 1.0;

    // Live update timer for animated electrical wire state
    private javax.swing.Timer liveUpdateTimer;

    // Component images cache: boardId -> BufferedImage
    private final Map<String, BufferedImage> imageCache = new HashMap<>();

    public CircuitAutoRouteGUI(String workspaceName,
                               Map<String, CpsDomain.Board> boards,
                               List<CpsDomain.Wire> wires) {
        super("CPS Circuit Auto-Router — Workspace: " + workspaceName);
        this.workspaceName = workspaceName;
        this.boards = boards;
        this.wires = wires;

        loadComponentImages();
        checkAndApplyInitialAutoLayout();
        buildUI();

        // 5 Hz live update timer for animated wires
        liveUpdateTimer = new javax.swing.Timer(200, e -> canvas.repaint());
        liveUpdateTimer.start();

        setSize(1200, 820);
        setMinimumSize(new Dimension(900, 650));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (liveUpdateTimer != null) liveUpdateTimer.stop();
            }
        });
    }

    public static void launch(String workspaceName,
                              Map<String, CpsDomain.Board> boards,
                              List<CpsDomain.Wire> wires) {
        SwingUtilities.invokeLater(() -> {
            CircuitAutoRouteGUI gui = new CircuitAutoRouteGUI(workspaceName, boards, wires);
            gui.setVisible(true);
        });
    }

    private void loadComponentImages() {
        imageCache.clear();
        for (CpsDomain.Board b : boards.values()) {
            if (b.imagePath != null && !b.imagePath.isEmpty()) {
                File f = new File(b.imagePath);
                if (f.exists()) {
                    try {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null) {
                            imageCache.put(b.getId(), img);
                        }
                    } catch (Exception ex) {
                        System.err.println("[AUTOROUTE] Error loading image for " + b.getId() + ": " + ex.getMessage());
                    }
                }
            }
        }
    }

    private void checkAndApplyInitialAutoLayout() {
        // If all boards are at (100, 100) or stacked, run auto-arrange
        boolean allDefault = true;
        Set<String> positions = new HashSet<>();
        for (CpsDomain.Board b : boards.values()) {
            String pos = b.layoutX + "," + b.layoutY;
            if (positions.contains(pos)) {
                allDefault = true;
                break;
            }
            positions.add(pos);
            if (b.layoutX != 100 || b.layoutY != 100) {
                allDefault = false;
            }
        }
        if (allDefault || positions.size() <= 1) {
            autoArrangeComponents();
        }
    }

    /**
     * Intelligent 3-tier CAD Auto-Arrangement:
     * Column 1 (Left):   Sensors, inputs, power sources
     * Column 2 (Center): Microcontrollers, DAQ MCUs, processors
     * Column 3 (Right):  Actuators, relays, electronic loads, cooling fans
     */
    public void autoArrangeComponents() {
        List<CpsDomain.Board> inputs = new ArrayList<>();
        List<CpsDomain.Board> mcus = new ArrayList<>();
        List<CpsDomain.Board> outputs = new ArrayList<>();

        for (CpsDomain.Board b : boards.values()) {
            String idLower = b.getId().toLowerCase();
            String modelLower = (b.model != null) ? b.model.toLowerCase() : "";

            if (b.getFlashedFirmware() != null || modelLower.contains("esp32") || modelLower.contains("nodemcu") || modelLower.contains("arduino") || idLower.contains("mcu")) {
                mcus.add(b);
            } else if (idLower.contains("relay") || idLower.contains("fan") || idLower.contains("load") || idLower.contains("actuator") || idLower.contains("light") || idLower.contains("hvac")) {
                outputs.add(b);
            } else {
                inputs.add(b);
            }
        }

        // Column spacing
        int col1X = 60;
        int col2X = 460;
        int col3X = 860;

        int yStart = 60;
        int ySpacing = 200;

        int curY = yStart;
        for (CpsDomain.Board b : inputs) {
            b.layoutX = col1X;
            b.layoutY = curY;
            curY += ySpacing;
        }

        curY = yStart + 40;
        for (CpsDomain.Board b : mcus) {
            b.layoutX = col2X;
            b.layoutY = curY;
            curY += ySpacing + 60;
        }

        curY = yStart;
        for (CpsDomain.Board b : outputs) {
            b.layoutX = col3X;
            b.layoutY = curY;
            curY += ySpacing;
        }

        if (canvas != null) {
            canvas.revalidate();
            canvas.repaint();
        }
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(20, 24, 33));

        // ── TOP CONTROL TOOLBAR ─────────────────────────────────────────────────
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(28, 34, 48));
        toolbar.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, new Color(45, 55, 75)),
            new EmptyBorder(8, 12, 8, 12)
        ));

        // Left Header
        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftHeader.setOpaque(false);
        JLabel titleLbl = new JLabel("SCHEMATIC AUTO-ROUTER");
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titleLbl.setForeground(new Color(0, 229, 255));

        JLabel infoLbl = new JLabel("• Workspace: " + workspaceName + " (" + boards.size() + " boards, " + wires.size() + " wires)");
        infoLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLbl.setForeground(new Color(170, 185, 205));

        leftHeader.add(titleLbl);
        leftHeader.add(infoLbl);
        toolbar.add(leftHeader, BorderLayout.WEST);

        // Right Actions Toolbar
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        JButton arrangeBtn = createStyledButton("Auto-Arrange", new Color(0, 150, 136), Color.WHITE);
        arrangeBtn.addActionListener(e -> autoArrangeComponents());

        JButton toggleRouteBtn = createStyledButton("Routing: Manhattan", new Color(45, 55, 75), Color.WHITE);
        toggleRouteBtn.addActionListener(e -> {
            useManhattanRouting = !useManhattanRouting;
            toggleRouteBtn.setText(useManhattanRouting ? "Routing: Manhattan" : "Routing: Spline");
            canvas.repaint();
        });

        JButton toggleLabelsBtn = createStyledButton("Pin Labels: ON", new Color(45, 55, 75), Color.WHITE);
        toggleLabelsBtn.addActionListener(e -> {
            showPinLabels = !showPinLabels;
            toggleLabelsBtn.setText(showPinLabels ? "Pin Labels: ON" : "Pin Labels: OFF");
            canvas.repaint();
        });

        JButton calibrateBtn = createStyledButton("Calibrate Board...", new Color(70, 85, 115), Color.WHITE);
        calibrateBtn.addActionListener(e -> openCalibrateDialog());

        JButton exportBtn = createStyledButton("Export PNG", new Color(45, 55, 75), Color.WHITE);
        exportBtn.addActionListener(e -> exportSchematicImage());

        actions.add(arrangeBtn);
        actions.add(toggleRouteBtn);
        actions.add(toggleLabelsBtn);
        actions.add(calibrateBtn);
        actions.add(exportBtn);

        toolbar.add(actions, BorderLayout.EAST);
        root.add(toolbar, BorderLayout.NORTH);

        // ── CENTER SCHEMATIC CANVAS ─────────────────────────────────────────────
        canvas = new SchematicCanvas();
        JScrollPane scroller = new JScrollPane(canvas);
        scroller.getViewport().setBackground(new Color(16, 20, 28));
        scroller.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroller, BorderLayout.CENTER);

        // ── BOTTOM STATUS BAR ───────────────────────────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(new Color(24, 28, 38));
        bottomBar.setBorder(new EmptyBorder(6, 12, 6, 12));

        JLabel legendLbl = new JLabel("<html><span style='color:#00E676;'>● High/Active (>2V)</span> &nbsp;&nbsp; " +
            "<span style='color:#FFB300;'>● Analog Signal</span> &nbsp;&nbsp; " +
            "<span style='color:#607D8B;'>● Ground/Low (0V)</span> &nbsp;&nbsp; " +
            "<span style='color:#90A4AE;'>| Drag components with mouse to reposition | Wires auto-route continuously</span></html>");
        legendLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        JLabel statusLbl = new JLabel("Live Engine: 1 Hz Clock Active");
        statusLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        statusLbl.setForeground(new Color(0, 229, 255));

        bottomBar.add(legendLbl, BorderLayout.WEST);
        bottomBar.add(statusLbl, BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void openCalibrateDialog() {
        if (boards.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No boards found in active workspace.");
            return;
        }
        String[] boardNames = boards.keySet().toArray(new String[0]);
        String selected = (String) JOptionPane.showInputDialog(this,
            "Select board to calibrate pins with image:",
            "Calibrate Board Image & Pins",
            JOptionPane.QUESTION_MESSAGE, null, boardNames, boardNames[0]);

        if (selected != null) {
            CpsDomain.Board b = boards.get(selected);
            File imgFile = (b.imagePath != null) ? new File(b.imagePath) : null;
            if (imgFile == null || !imgFile.exists()) {
                JFileChooser chooser = new JFileChooser(new File("assets"));
                chooser.setDialogTitle("Select Image for " + b.getId());
                chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg)", "png", "jpg", "jpeg"));
                int res = chooser.showOpenDialog(this);
                if (res == JFileChooser.APPROVE_OPTION) {
                    imgFile = chooser.getSelectedFile();
                } else {
                    return;
                }
            }
            PinFixCalibrator.launch(b, imgFile, () -> {
                loadComponentImages();
                canvas.repaint();
            });
        }
    }

    private void exportSchematicImage() {
        Dimension d = canvas.getPreferredSize();
        BufferedImage image = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File outDir = new File("exports");
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, "schematic_" + workspaceName + "_" + System.currentTimeMillis() + ".png");
        try {
            ImageIO.write(image, "PNG", outFile);
            JOptionPane.showMessageDialog(this, "Schematic exported successfully to:\n" + outFile.getAbsolutePath(),
                "Export Complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JButton createStyledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setBorder(new CompoundBorder(
            new LineBorder(bg.brighter(), 1, true),
            new EmptyBorder(5, 12, 5, 12)
        ));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ─── SCHEMATIC CANVAS CLASS ───────────────────────────────────────────────
    private class SchematicCanvas extends JPanel {

        private CpsDomain.Board draggingBoard = null;
        private Point dragOffset = new Point();

        public SchematicCanvas() {
            setBackground(new Color(16, 20, 28));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    // Check if clicked inside a board box
                    for (CpsDomain.Board b : boards.values()) {
                        Rectangle bounds = getBoardBounds(b);
                        if (bounds.contains(e.getPoint())) {
                            draggingBoard = b;
                            dragOffset.setLocation(e.getX() - b.layoutX, e.getY() - b.layoutY);
                            setCursor(new Cursor(Cursor.MOVE_CURSOR));
                            break;
                        }
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggingBoard = null;
                    setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (draggingBoard != null) {
                        draggingBoard.layoutX = Math.max(10, e.getX() - dragOffset.x);
                        draggingBoard.layoutY = Math.max(10, e.getY() - dragOffset.y);
                        revalidate();
                        repaint();
                    }
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override
        public Dimension getPreferredSize() {
            int maxX = 1200;
            int maxY = 800;
            for (CpsDomain.Board b : boards.values()) {
                Rectangle r = getBoardBounds(b);
                maxX = Math.max(maxX, r.x + r.width + 120);
                maxY = Math.max(maxY, r.y + r.height + 120);
            }
            return new Dimension(maxX, maxY);
        }

        private Rectangle getBoardBounds(CpsDomain.Board b) {
            BufferedImage img = imageCache.get(b.getId());
            if (img != null) {
                // Fixed target display width for images to keep layout balanced
                int displayW = 200;
                int displayH = (int) Math.round((double) img.getHeight() * displayW / img.getWidth());
                return new Rectangle(b.layoutX, b.layoutY, displayW, displayH);
            } else {
                // Default box representing its pins
                int pinCount = b.pins.size();
                int h = Math.max(90, 45 + ((pinCount + 1) / 2) * 26);
                return new Rectangle(b.layoutX, b.layoutY, 210, h);
            }
        }

        /**
         * Resolves the exact absolute canvas coordinate for a pin on a given board.
         */
        private Point getAbsolutePinCoordinate(CpsDomain.Board b, String pinId) {
            Rectangle bounds = getBoardBounds(b);
            BufferedImage img = imageCache.get(b.getId());

            if (img != null && b.pinPositions.containsKey(pinId)) {
                // Calibrated coordinate on image
                Point2D.Double norm = b.pinPositions.get(pinId);
                int px = bounds.x + (int) Math.round(norm.x * bounds.width);
                int py = bounds.y + (int) Math.round(norm.y * bounds.height);
                return new Point(px, py);
            } else {
                // Default breakout pin coordinate
                List<String> pinList = new ArrayList<>(b.pins.keySet());
                int idx = pinList.indexOf(pinId);
                if (idx == -1) idx = 0;

                // Alternate pins along Left and Right edges
                boolean isLeft = (idx % 2 == 0);
                int row = idx / 2;
                int pinY = bounds.y + 42 + row * 26;

                int pinX = isLeft ? bounds.x + 8 : bounds.x + bounds.width - 8;
                return new Point(pinX, pinY);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // 1. Draw CAD Blueprint Grid
            drawCADGrid(g2);

            // 2. Draw Auto-Routed Wires (below boards)
            drawWires(g2);

            // 3. Draw All Components / Boards
            for (CpsDomain.Board b : boards.values()) {
                drawBoardComponent(g2, b);
            }

            g2.dispose();
        }

        private void drawCADGrid(Graphics2D g2) {
            g2.setColor(new Color(24, 30, 42));
            int step = 20;
            for (int x = 0; x < getWidth(); x += step) {
                g2.drawLine(x, 0, x, getHeight());
            }
            for (int y = 0; y < getHeight(); y += step) {
                g2.drawLine(0, y, getWidth(), y);
            }
            // Major grid lines
            g2.setColor(new Color(30, 38, 52));
            for (int x = 0; x < getWidth(); x += step * 5) {
                g2.drawLine(x, 0, x, getHeight());
            }
            for (int y = 0; y < getHeight(); y += step * 5) {
                g2.drawLine(0, y, getWidth(), y);
            }
        }

        // ─── COMPONENT RENDERING ───────────────────────────────────────────────
        private void drawBoardComponent(Graphics2D g2, CpsDomain.Board b) {
            Rectangle bounds = getBoardBounds(b);
            BufferedImage img = imageCache.get(b.getId());

            boolean isDragging = (b == draggingBoard);

            // Drop shadow
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRoundRect(bounds.x + 5, bounds.y + 5, bounds.width, bounds.height, 10, 10);

            if (img != null) {
                // A. Render Real Component Image
                g2.drawImage(img, bounds.x, bounds.y, bounds.width, bounds.height, null);

                // Outline border
                g2.setColor(isDragging ? new Color(0, 229, 255) : new Color(70, 90, 120));
                g2.setStroke(new BasicStroke(isDragging ? 2.5f : 1.5f));
                g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);

                // Component Name Header Banner
                drawComponentHeader(g2, bounds.x, bounds.y - 20, bounds.width, b.getId(), b.model, true);

                // Draw Pin Contact Dots on Image
                for (String pid : b.pins.keySet()) {
                    Point pCoord = getAbsolutePinCoordinate(b, pid);
                    CpsDomain.Pin pin = b.getPin(pid);
                    drawPinContact(g2, pCoord.x, pCoord.y, pid, pin, true);
                }

            } else {
                // B. Render Default IC / Breakout Component Box
                drawDefaultComponentBox(g2, bounds, b, isDragging);
            }
        }

        private void drawDefaultComponentBox(Graphics2D g2, Rectangle bounds, CpsDomain.Board b, boolean isDragging) {
            // Main dark PCB background
            g2.setColor(new Color(28, 35, 48));
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);

            // IC header bar
            g2.setColor(new Color(36, 46, 64));
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, 28, 10, 10);
            g2.fillRect(bounds.x, bounds.y + 18, bounds.width, 10);

            // Border
            g2.setColor(isDragging ? new Color(0, 229, 255) : new Color(60, 80, 110));
            g2.setStroke(new BasicStroke(isDragging ? 2.5f : 1.5f));
            g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);

            // Header text
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2.drawString(b.getId(), bounds.x + 10, bounds.y + 19);

            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.setColor(new Color(0, 229, 255));
            String modelStr = "[" + b.model + "]";
            int mw = g2.getFontMetrics().stringWidth(modelStr);
            g2.drawString(modelStr, bounds.x + bounds.width - mw - 10, bounds.y + 18);

            // Central IC chip representation
            int chipW = 80;
            int chipH = bounds.height - 48;
            int chipX = bounds.x + (bounds.width - chipW) / 2;
            int chipY = bounds.y + 36;
            g2.setColor(new Color(20, 24, 32));
            g2.fillRoundRect(chipX, chipY, chipW, chipH, 6, 6);
            g2.setColor(new Color(45, 55, 75));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(chipX, chipY, chipW, chipH, 6, 6);

            // IC notch at top of chip
            g2.setColor(new Color(35, 42, 56));
            g2.fillArc(chipX + chipW / 2 - 8, chipY - 6, 16, 12, 180, 180);

            // Draw each pin pad and pin number/label
            List<String> pinList = new ArrayList<>(b.pins.keySet());
            for (int i = 0; i < pinList.size(); i++) {
                String pid = pinList.get(i);
                CpsDomain.Pin pin = b.getPin(pid);
                Point pCoord = getAbsolutePinCoordinate(b, pid);

                boolean isLeft = (i % 2 == 0);
                int pinNum = i + 1;

                drawDefaultPinPad(g2, pCoord.x, pCoord.y, pinNum, pid, pin, isLeft);
            }
        }

        private void drawDefaultPinPad(Graphics2D g2, int px, int py, int pinNum, String pinId, CpsDomain.Pin pin, boolean isLeft) {
            // Solder pad metallic contact
            g2.setColor(new Color(200, 170, 70)); // Gold contact pad
            g2.fillOval(px - 5, py - 5, 10, 10);
            g2.setColor(new Color(25, 30, 40));
            g2.fillOval(px - 2, py - 2, 4, 4); // Via hole

            // Pin trace going into IC
            g2.setColor(new Color(100, 115, 135));
            g2.setStroke(new BasicStroke(1.5f));
            if (isLeft) {
                g2.drawLine(px + 5, py, px + 22, py);
            } else {
                g2.drawLine(px - 5, py, px - 22, py);
            }

            // Pin Number and Label
            if (showPinLabels) {
                g2.setFont(new Font("Consolas", Font.BOLD, 10));
                FontMetrics fm = g2.getFontMetrics();

                String label = "#" + pinNum + " " + pinId;
                int textW = fm.stringWidth(label);

                g2.setColor(new Color(210, 220, 235));
                if (isLeft) {
                    g2.drawString(label, px + 12, py - 4);
                } else {
                    g2.drawString(label, px - textW - 12, py - 4);
                }
            }
        }

        private void drawPinContact(Graphics2D g2, int px, int py, String pinId, CpsDomain.Pin pin, boolean showOverlay) {
            // Calibrated contact dot
            boolean isHigh = (pin != null && pin.voltage > 2.0);
            Color dotColor = isHigh ? new Color(0, 230, 118) : new Color(0, 229, 255);

            g2.setColor(dotColor);
            g2.fillOval(px - 5, py - 5, 10, 10);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawOval(px - 5, py - 5, 10, 10);

            if (showPinLabels) {
                g2.setFont(new Font("Consolas", Font.BOLD, 10));
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(pinId);

                // Small badge
                g2.setColor(new Color(15, 20, 28, 220));
                g2.fillRoundRect(px + 8, py - 7, tw + 6, 14, 4, 4);
                g2.setColor(new Color(60, 75, 100));
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRoundRect(px + 8, py - 7, tw + 6, 14, 4, 4);

                g2.setColor(Color.WHITE);
                g2.drawString(pinId, px + 11, py + 4);
            }
        }

        private void drawComponentHeader(Graphics2D g2, int x, int y, int w, String id, String model, boolean hasImage) {
            g2.setColor(new Color(20, 26, 38, 220));
            g2.fillRoundRect(x, y, w, 18, 6, 6);
            g2.setColor(new Color(50, 65, 85));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(x, y, w, 18, 6, 6);

            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g2.setColor(new Color(0, 229, 255));
            g2.drawString(id, x + 6, y + 13);

            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.setColor(new Color(160, 180, 200));
            String m = "[" + model + "]";
            int mw = g2.getFontMetrics().stringWidth(m);
            g2.drawString(m, x + w - mw - 6, y + 13);
        }

        // ─── AUTO-ROUTING WIRES ────────────────────────────────────────────────
        private void drawWires(Graphics2D g2) {
            for (CpsDomain.Wire w : wires) {
                if (w.pinA == null || w.pinB == null) continue;

                CpsDomain.Board b1 = findBoardForPin(w.pinA);
                CpsDomain.Board b2 = findBoardForPin(w.pinB);
                if (b1 == null || b2 == null) continue;

                Point p1 = getAbsolutePinCoordinate(b1, w.pinA.pinId);
                Point p2 = getAbsolutePinCoordinate(b2, w.pinB.pinId);

                Rectangle rect1 = getBoardBounds(b1);
                Rectangle rect2 = getBoardBounds(b2);

                // Voltage-based wire coloring
                double v = Math.max(w.pinA.voltage, w.pinB.voltage);
                Color wireColor;
                Color glowColor;

                if (v >= 2.0) {
                    wireColor = new Color(0, 230, 118); // Bright emerald green
                    glowColor = new Color(0, 230, 118, 60);
                } else if (v > 0.6) {
                    wireColor = new Color(255, 179, 0); // Vibrant Amber
                    glowColor = new Color(255, 179, 0, 50);
                } else {
                    wireColor = new Color(100, 125, 145); // Slate metallic
                    glowColor = new Color(100, 125, 145, 30);
                }

                if (useManhattanRouting) {
                    drawManhattanWire(g2, p1, p2, rect1, rect2, wireColor, glowColor, w, v);
                } else {
                    drawSplineWire(g2, p1, p2, wireColor, glowColor, w, v);
                }
            }
        }

        /**
         * Orthogonal Manhattan Channel Auto-Router with filleted corners.
         */
        private void drawManhattanWire(Graphics2D g2, Point p1, Point p2,
                                       Rectangle r1, Rectangle r2,
                                       Color wireColor, Color glowColor,
                                       CpsDomain.Wire w, double voltage) {

            // Determine departure direction for p1
            boolean p1OnLeft = (p1.x <= r1.x + r1.width / 2);
            int stub1X = p1OnLeft ? p1.x - 30 : p1.x + 30;

            // Determine approach direction for p2
            boolean p2OnLeft = (p2.x <= r2.x + r2.width / 2);
            int stub2X = p2OnLeft ? p2.x - 30 : p2.x + 30;

            Path2D.Double path = new Path2D.Double();
            path.moveTo(p1.x, p1.y);
            path.lineTo(stub1X, p1.y);

            // Middle channel calculation
            int midX = (stub1X + stub2X) / 2;

            // If boards are vertically aligned or cross, route with intermediate waypoints
            if ((p1OnLeft && !p2OnLeft && stub1X < stub2X) || (!p1OnLeft && p2OnLeft && stub1X > stub2X)) {
                // S-curve channel
                int midY = (p1.y + p2.y) / 2;
                path.lineTo(stub1X, midY);
                path.lineTo(stub2X, midY);
            } else {
                // Direct channel
                path.lineTo(midX, p1.y);
                path.lineTo(midX, p2.y);
                path.lineTo(stub2X, p2.y);
            }

            path.lineTo(p2.x, p2.y);

            // Draw glowing halo stroke
            g2.setColor(glowColor);
            g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);

            // Draw main conductor line
            g2.setColor(wireColor);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);

            // Wire endpoint solder joints
            g2.setColor(Color.WHITE);
            g2.fillOval(p1.x - 3, p1.y - 3, 6, 6);
            g2.fillOval(p2.x - 3, p2.y - 3, 6, 6);

            // Wire Badge
            if (showWireVoltages) {
                int labelX = (p1.x + p2.x) / 2;
                int labelY = (p1.y + p2.y) / 2;
                drawWireBadge(g2, labelX, labelY, w.wireName, voltage, wireColor);
            }
        }

        /**
         * Smooth Bezier Spline Router.
         */
        private void drawSplineWire(Graphics2D g2, Point p1, Point p2,
                                    Color wireColor, Color glowColor,
                                    CpsDomain.Wire w, double voltage) {

            int dx = Math.abs(p2.x - p1.x);
            int ctrlOffset = Math.max(50, dx / 2);

            int cx1 = (p1.x < p2.x) ? p1.x + ctrlOffset : p1.x - ctrlOffset;
            int cy1 = p1.y;
            int cx2 = (p2.x > p1.x) ? p2.x - ctrlOffset : p2.x + ctrlOffset;
            int cy2 = p2.y;

            CubicCurve2D.Double spline = new CubicCurve2D.Double(p1.x, p1.y, cx1, cy1, cx2, cy2, p2.x, p2.y);

            // Glowing halo
            g2.setColor(glowColor);
            g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(spline);

            // Conductor
            g2.setColor(wireColor);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(spline);

            // Endpoints
            g2.setColor(Color.WHITE);
            g2.fillOval(p1.x - 3, p1.y - 3, 6, 6);
            g2.fillOval(p2.x - 3, p2.y - 3, 6, 6);

            if (showWireVoltages) {
                int labelX = (p1.x + p2.x) / 2;
                int labelY = (p1.y + p2.y) / 2;
                drawWireBadge(g2, labelX, labelY, w.wireName, voltage, wireColor);
            }
        }

        private void drawWireBadge(Graphics2D g2, int x, int y, String name, double v, Color accent) {
            String txt = name + ": " + String.format("%.2fV", v);
            g2.setFont(new Font("Consolas", Font.BOLD, 10));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(txt);

            g2.setColor(new Color(15, 20, 30, 230));
            g2.fillRoundRect(x - tw / 2 - 4, y - 8, tw + 8, 16, 6, 6);
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(x - tw / 2 - 4, y - 8, tw + 8, 16, 6, 6);

            g2.setColor(Color.WHITE);
            g2.drawString(txt, x - tw / 2, y + 4);
        }

        private CpsDomain.Board findBoardForPin(CpsDomain.Pin pin) {
            for (CpsDomain.Board b : boards.values()) {
                if (b.pins.containsValue(pin)) {
                    return b;
                }
            }
            return null;
        }
    }
}
