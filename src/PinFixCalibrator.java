import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * ============================================================================
 * PIN FIX CALIBRATOR GUI
 * ============================================================================
 * Interactive pin position calibration tool for CPS components and boards.
 *
 * Workflow:
 * 1. User selects a pre-fixed pin name fetched from the Board's pinout using
 *    a dedicated JScrollPane pin selector.
 * 2. Another prominent box represents the "Active Pin Name" and mode.
 * 3. User clicks on the component image canvas to place/adjust the calibrated dot.
 * 4. Stored as normalized coordinates [0.0, 1.0] for resolution-independent scaling.
 * 5. Supports dot dragging, zoom, coordinate readout, and "Save & Apply".
 * ============================================================================
 */
public class PinFixCalibrator extends JFrame {

    private final CpsDomain.Board board;
    private File currentImageFile;
    private BufferedImage componentImage;

    // Local copy of pin coordinates: pinId -> (u, v) normalized [0.0, 1.0]
    private final Map<String, Point2D.Double> calibratedPins = new LinkedHashMap<>();

    // UI Elements - Left Column
    private DefaultListModel<String> pinListModel;
    private JList<String> pinList;
    private JLabel activePinNameLabel;
    private JLabel activePinModeLabel;
    private JLabel activePinCoordLabel;
    private JLabel statusFeedbackLabel;
    private JLabel progressLabel;

    // UI Elements - Center Canvas
    private ImageCanvas canvas;
    private JScrollPane canvasScrollPane;
    private double zoomFactor = 1.0;

    // State
    private String selectedPinId = null;
    private String draggingPinId = null;
    private Point lastMousePos = new Point(0, 0);

    // Callback on save
    private Runnable onSaveCallback;

    public PinFixCalibrator(CpsDomain.Board board, File imageFile, Runnable onSaveCallback) {
        super("CPS PinFix Calibrator — " + board.getId() + " [" + board.model + "]");
        this.board = board;
        this.currentImageFile = imageFile;
        this.onSaveCallback = onSaveCallback;

        // Copy existing pin coordinates if any
        if (board.pinPositions != null) {
            for (Map.Entry<String, Point2D.Double> e : board.pinPositions.entrySet()) {
                calibratedPins.put(e.getKey(), new Point2D.Double(e.getValue().x, e.getValue().y));
            }
        }

        loadImage(imageFile);
        buildUI();

        setSize(1100, 780);
        setMinimumSize(new Dimension(850, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    public static void launch(CpsDomain.Board board, File imageFile, Runnable onSave) {
        SwingUtilities.invokeLater(() -> {
            PinFixCalibrator frame = new PinFixCalibrator(board, imageFile, onSave);
            frame.setVisible(true);
        });
    }

    private void loadImage(File file) {
        if (file == null || !file.exists()) {
            this.componentImage = null;
            return;
        }
        try {
            this.currentImageFile = file;
            this.componentImage = ImageIO.read(file);
        } catch (IOException e) {
            System.err.println("[PINFIX] Failed to load image: " + e.getMessage());
            this.componentImage = null;
        }
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(new Color(24, 28, 36));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── TOP TOOLBAR ─────────────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setOpaque(false);

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        titlePanel.setOpaque(false);
        JLabel title = new JLabel("PIN CALIBRATION BENCH");
        title.setFont(new Font("Segoe UI", Font.BOLD, 16));
        title.setForeground(new Color(0, 229, 255)); // Cyan glow
        JLabel subtitle = new JLabel("• Board: " + board.getId() + " (" + board.model + ")");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(new Color(180, 190, 205));
        titlePanel.add(title);
        titlePanel.add(subtitle);

        JPanel quickActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        quickActions.setOpaque(false);

        JButton browseBtn = createStyledButton("Change Image...", new Color(45, 55, 72), Color.WHITE);
        browseBtn.addActionListener(e -> chooseNewImage());

        JButton zoomInBtn = createStyledButton("+", new Color(45, 55, 72), Color.WHITE);
        zoomInBtn.addActionListener(e -> setZoom(zoomFactor * 1.25));

        JButton zoomOutBtn = createStyledButton("-", new Color(45, 55, 72), Color.WHITE);
        zoomOutBtn.addActionListener(e -> setZoom(zoomFactor / 1.25));

        JButton zoomResetBtn = createStyledButton("100%", new Color(45, 55, 72), Color.WHITE);
        zoomResetBtn.addActionListener(e -> setZoom(1.0));

        quickActions.add(browseBtn);
        quickActions.add(new JSeparator(SwingConstants.VERTICAL));
        quickActions.add(new JLabel("Zoom: "));
        quickActions.add(zoomOutBtn);
        quickActions.add(zoomResetBtn);
        quickActions.add(zoomInBtn);

        topBar.add(titlePanel, BorderLayout.WEST);
        topBar.add(quickActions, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        // ── LEFT SIDEBAR (Pin Selection & Active Pin Box) ───────────────────────
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setPreferredSize(new Dimension(300, 600));
        leftPanel.setBackground(new Color(30, 36, 48));
        leftPanel.setBorder(new CompoundBorder(
            new LineBorder(new Color(50, 60, 80), 1, true),
            new EmptyBorder(12, 12, 12, 12)
        ));

        // 1. Pre-fixed Pin Names Header
        JLabel pinListTitle = new JLabel("1. SELECT PRE-FIXED PIN");
        pinListTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        pinListTitle.setForeground(new Color(140, 160, 190));
        pinListTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(pinListTitle);
        leftPanel.add(Box.createVerticalStrut(6));

        // 2. Scroll Pane of Pin Names
        pinListModel = new DefaultListModel<>();
        refreshPinList();
        pinList = new JList<>(pinListModel);
        pinList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pinList.setBackground(new Color(20, 24, 32));
        pinList.setForeground(new Color(220, 225, 235));
        pinList.setFont(new Font("Consolas", Font.PLAIN, 13));
        pinList.setSelectionBackground(new Color(0, 120, 215));
        pinList.setSelectionForeground(Color.WHITE);
        pinList.setFixedCellHeight(26);

        pinList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String val = pinList.getSelectedValue();
                if (val != null) {
                    // Extract pin ID from format: "[✓] GPIO_12 ..."
                    String pinId = parsePinIdFromListEntry(val);
                    selectActivePin(pinId);
                }
            }
        });

        JScrollPane listScroller = new JScrollPane(pinList);
        listScroller.setPreferredSize(new Dimension(276, 230));
        listScroller.setMaximumSize(new Dimension(Short.MAX_VALUE, 260));
        listScroller.setBorder(new LineBorder(new Color(45, 55, 75), 1));
        listScroller.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(listScroller);
        leftPanel.add(Box.createVerticalStrut(14));

        // 3. The Active Pin Box
        JLabel activeBoxTitle = new JLabel("2. ACTIVE PIN TARGET");
        activeBoxTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        activeBoxTitle.setForeground(new Color(140, 160, 190));
        activeBoxTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(activeBoxTitle);
        leftPanel.add(Box.createVerticalStrut(6));

        JPanel activePinCard = new JPanel();
        activePinCard.setLayout(new BoxLayout(activePinCard, BoxLayout.Y_AXIS));
        activePinCard.setBackground(new Color(20, 26, 38));
        activePinCard.setBorder(new CompoundBorder(
            new LineBorder(new Color(0, 229, 255), 1, true),
            new EmptyBorder(10, 12, 10, 12)
        ));
        activePinCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        activePinCard.setMaximumSize(new Dimension(Short.MAX_VALUE, 120));

        activePinNameLabel = new JLabel("NO PIN SELECTED");
        activePinNameLabel.setFont(new Font("Consolas", Font.BOLD, 18));
        activePinNameLabel.setForeground(new Color(0, 229, 255));
        activePinNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        activePinModeLabel = new JLabel("Mode: —");
        activePinModeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        activePinModeLabel.setForeground(new Color(160, 175, 195));
        activePinModeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        activePinCoordLabel = new JLabel("Coords: [Click on image to place]");
        activePinCoordLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        activePinCoordLabel.setForeground(new Color(255, 204, 0));
        activePinCoordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        activePinCard.add(activePinNameLabel);
        activePinCard.add(Box.createVerticalStrut(4));
        activePinCard.add(activePinModeLabel);
        activePinCard.add(Box.createVerticalStrut(4));
        activePinCard.add(activePinCoordLabel);

        leftPanel.add(activePinCard);
        leftPanel.add(Box.createVerticalStrut(12));

        // 4. Instructions & Helpers
        JLabel tipLabel = new JLabel("<html><body style='width:240px; color:#9EABB8; font-size:11px;'>"
            + "<b>Instructions:</b><br>"
            + "• Click a pin from the scroll list above.<br>"
            + "• Click on the image to drop the pin dot.<br>"
            + "• Drag existing dots on the image to fine-tune.<br>"
            + "• Click <b>Save & Apply</b> to store layout.</body></html>");
        tipLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(tipLabel);

        leftPanel.add(Box.createVerticalGlue());

        // 5. Pin Management Buttons
        JPanel pinBtnRow = new JPanel(new GridLayout(1, 2, 6, 0));
        pinBtnRow.setOpaque(false);
        pinBtnRow.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
        pinBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton clearActiveBtn = createStyledButton("Clear Active", new Color(60, 45, 55), new Color(255, 150, 150));
        clearActiveBtn.addActionListener(e -> clearSelectedPin());

        JButton clearAllBtn = createStyledButton("Clear All", new Color(60, 45, 55), new Color(255, 150, 150));
        clearAllBtn.addActionListener(e -> clearAllPins());

        pinBtnRow.add(clearActiveBtn);
        pinBtnRow.add(clearAllBtn);
        leftPanel.add(pinBtnRow);
        leftPanel.add(Box.createVerticalStrut(10));

        // 6. Save & Apply Button
        JButton saveBtn = createStyledButton("SAVE & APPLY", new Color(0, 180, 136), Color.WHITE);
        saveBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        saveBtn.setMaximumSize(new Dimension(Short.MAX_VALUE, 38));
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.addActionListener(e -> saveAndApply());
        leftPanel.add(saveBtn);

        root.add(leftPanel, BorderLayout.WEST);

        // ── CENTER (Image Canvas with Pin Overlay) ──────────────────────────────
        canvas = new ImageCanvas();
        canvasScrollPane = new JScrollPane(canvas);
        canvasScrollPane.getViewport().setBackground(new Color(16, 20, 28));
        canvasScrollPane.setBorder(new LineBorder(new Color(40, 50, 70), 1));
        root.add(canvasScrollPane, BorderLayout.CENTER);

        // ── BOTTOM STATUS BAR ───────────────────────────────────────────────────
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(6, 4, 0, 4));

        statusFeedbackLabel = new JLabel("Ready. Select a pin from the list to begin calibration.");
        statusFeedbackLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusFeedbackLabel.setForeground(new Color(160, 180, 205));

        progressLabel = new JLabel("Calibrated: 0 / " + board.pins.size());
        progressLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        progressLabel.setForeground(new Color(0, 229, 255));

        bottomBar.add(statusFeedbackLabel, BorderLayout.WEST);
        bottomBar.add(progressLabel, BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        setContentPane(root);

        // Pre-select first pin
        if (!board.pins.isEmpty()) {
            pinList.setSelectedIndex(0);
        }
        updateProgress();
    }

    private void refreshPinList() {
        pinListModel.clear();
        for (String pid : board.pins.keySet()) {
            boolean isCalibrated = calibratedPins.containsKey(pid);
            String marker = isCalibrated ? "[✓] " : "[ ] ";
            Point2D.Double p = calibratedPins.get(pid);
            String coord = (p != null) ? String.format(" (%.0f%%, %.0f%%)", p.x * 100, p.y * 100) : "";
            pinListModel.addElement(marker + pid + coord);
        }
    }

    private String parsePinIdFromListEntry(String entry) {
        if (entry.startsWith("[✓] ") || entry.startsWith("[ ] ")) {
            String trimmed = entry.substring(4);
            int spaceIdx = trimmed.indexOf(" ");
            return (spaceIdx != -1) ? trimmed.substring(0, spaceIdx) : trimmed;
        }
        return entry;
    }

    private void selectActivePin(String pinId) {
        this.selectedPinId = pinId;
        if (pinId == null) {
            activePinNameLabel.setText("NO PIN SELECTED");
            activePinModeLabel.setText("Mode: —");
            activePinCoordLabel.setText("Coords: [Select pin from list]");
            return;
        }
        CpsDomain.Pin pin = board.getPin(pinId);
        activePinNameLabel.setText(pinId);
        activePinModeLabel.setText("Mode: " + (pin != null ? pin.mode.name() : "DIGITAL_IN"));

        Point2D.Double pt = calibratedPins.get(pinId);
        if (pt != null) {
            activePinCoordLabel.setText(String.format("Coords: u=%.3f, v=%.3f", pt.x, pt.y));
            statusFeedbackLabel.setText("Pin " + pinId + " is calibrated at (" + Math.round(pt.x * 100) + "%, " + Math.round(pt.y * 100) + "%). Click or drag to reposition.");
        } else {
            activePinCoordLabel.setText("Coords: [Click image to place dot]");
            statusFeedbackLabel.setText("Active Pin: " + pinId + ". Click anywhere on the component image to set its dot.");
        }
        canvas.repaint();
    }

    private void selectNextUncalibratedPin() {
        for (int i = 0; i < pinListModel.size(); i++) {
            String entry = pinListModel.get(i);
            if (entry.startsWith("[ ] ")) {
                pinList.setSelectedIndex(i);
                return;
            }
        }
    }

    private void clearSelectedPin() {
        if (selectedPinId != null) {
            calibratedPins.remove(selectedPinId);
            refreshPinList();
            // Re-select current pin to update UI
            for (int i = 0; i < pinListModel.size(); i++) {
                if (parsePinIdFromListEntry(pinListModel.get(i)).equals(selectedPinId)) {
                    pinList.setSelectedIndex(i);
                    break;
                }
            }
            selectActivePin(selectedPinId);
            updateProgress();
            canvas.repaint();
        }
    }

    private void clearAllPins() {
        int opt = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to clear all calibrated pin dots for " + board.getId() + "?",
            "Clear All Pins", JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) {
            calibratedPins.clear();
            refreshPinList();
            selectActivePin(selectedPinId);
            updateProgress();
            canvas.repaint();
        }
    }

    private void setZoom(double z) {
        if (z < 0.2) z = 0.2;
        if (z > 4.0) z = 4.0;
        this.zoomFactor = z;
        canvas.revalidate();
        canvas.repaint();
    }

    private void chooseNewImage() {
        JFileChooser chooser = new JFileChooser(new File("assets"));
        chooser.setDialogTitle("Select Component Image for " + board.getId());
        chooser.setFileFilter(new FileNameExtensionFilter("Image Files (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg", "bmp"));
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            loadImage(chooser.getSelectedFile());
            canvas.revalidate();
            canvas.repaint();
            statusFeedbackLabel.setText("Loaded new image: " + chooser.getSelectedFile().getName());
        }
    }

    private void saveAndApply() {
        if (currentImageFile != null) {
            board.imagePath = currentImageFile.getAbsolutePath();
        }
        board.pinPositions.clear();
        board.pinPositions.putAll(calibratedPins);

        statusFeedbackLabel.setText("✓ Successfully calibrated " + calibratedPins.size() + " pins for " + board.getId() + "!");
        JOptionPane.showMessageDialog(this,
            "Successfully saved " + calibratedPins.size() + " pin coordinates for " + board.getId() + "!\n" +
            "Image: " + (board.imagePath != null ? new File(board.imagePath).getName() : "None"),
            "Pin Calibration Complete", JOptionPane.INFORMATION_MESSAGE);

        if (onSaveCallback != null) {
            onSaveCallback.run();
        }
        dispose();
    }

    private void updateProgress() {
        int total = board.pins.size();
        int calibrated = calibratedPins.size();
        progressLabel.setText("Calibrated: " + calibrated + " / " + total);
        if (calibrated == total && total > 0) {
            progressLabel.setForeground(new Color(0, 230, 118)); // Green
        } else {
            progressLabel.setForeground(new Color(0, 229, 255)); // Cyan
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

    // ─── CUSTOM IMAGE CANVAS ──────────────────────────────────────────────────
    private class ImageCanvas extends JPanel {

        public ImageCanvas() {
            setBackground(new Color(16, 20, 28));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleMousePressed(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggingPinId = null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    handleMouseDragged(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    lastMousePos = e.getPoint();
                    repaint();
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override
        public Dimension getPreferredSize() {
            if (componentImage != null) {
                int w = (int) Math.round(componentImage.getWidth() * zoomFactor);
                int h = (int) Math.round(componentImage.getHeight() * zoomFactor);
                return new Dimension(Math.max(w + 100, 700), Math.max(h + 100, 500));
            }
            return new Dimension(700, 500);
        }

        private Point getImageOffset() {
            if (componentImage == null) return new Point(50, 50);
            int w = (int) Math.round(componentImage.getWidth() * zoomFactor);
            int h = (int) Math.round(componentImage.getHeight() * zoomFactor);
            int ox = Math.max(40, (getWidth() - w) / 2);
            int oy = Math.max(40, (getHeight() - h) / 2);
            return new Point(ox, oy);
        }

        private void handleMousePressed(MouseEvent e) {
            if (componentImage == null) return;
            Point offset = getImageOffset();
            int imgW = (int) Math.round(componentImage.getWidth() * zoomFactor);
            int imgH = (int) Math.round(componentImage.getHeight() * zoomFactor);

            // Check if user clicked an existing pin dot to drag or select
            for (Map.Entry<String, Point2D.Double> entry : calibratedPins.entrySet()) {
                int px = offset.x + (int) Math.round(entry.getValue().x * imgW);
                int py = offset.y + (int) Math.round(entry.getValue().y * imgH);
                if (e.getPoint().distance(px, py) <= 12) {
                    draggingPinId = entry.getKey();
                    // Sync with list
                    for (int i = 0; i < pinListModel.size(); i++) {
                        if (parsePinIdFromListEntry(pinListModel.get(i)).equals(draggingPinId)) {
                            pinList.setSelectedIndex(i);
                            break;
                        }
                    }
                    selectActivePin(draggingPinId);
                    return;
                }
            }

            // Otherwise, place/calibrate the active pin
            if (selectedPinId != null) {
                // Ensure click is within image boundaries
                if (e.getX() >= offset.x && e.getX() <= offset.x + imgW &&
                    e.getY() >= offset.y && e.getY() <= offset.y + imgH) {

                    double u = (double) (e.getX() - offset.x) / imgW;
                    double v = (double) (e.getY() - offset.y) / imgH;
                    // Clamp to [0, 1]
                    u = Math.max(0.0, Math.min(1.0, u));
                    v = Math.max(0.0, Math.min(1.0, v));

                    calibratedPins.put(selectedPinId, new Point2D.Double(u, v));
                    refreshPinList();

                    // Re-select active pin in list to maintain selection
                    for (int i = 0; i < pinListModel.size(); i++) {
                        if (parsePinIdFromListEntry(pinListModel.get(i)).equals(selectedPinId)) {
                            pinList.setSelectedIndex(i);
                            break;
                        }
                    }

                    selectActivePin(selectedPinId);
                    updateProgress();

                    // Auto-advance to next uncalibrated pin
                    selectNextUncalibratedPin();
                    repaint();
                }
            }
        }

        private void handleMouseDragged(MouseEvent e) {
            if (draggingPinId == null || componentImage == null) return;
            Point offset = getImageOffset();
            int imgW = (int) Math.round(componentImage.getWidth() * zoomFactor);
            int imgH = (int) Math.round(componentImage.getHeight() * zoomFactor);

            double u = (double) (e.getX() - offset.x) / imgW;
            double v = (double) (e.getY() - offset.y) / imgH;
            u = Math.max(0.0, Math.min(1.0, u));
            v = Math.max(0.0, Math.min(1.0, v));

            calibratedPins.put(draggingPinId, new Point2D.Double(u, v));
            selectActivePin(draggingPinId);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Draw subtle CAD grid
            drawGrid(g2);

            Point offset = getImageOffset();

            if (componentImage != null) {
                int imgW = (int) Math.round(componentImage.getWidth() * zoomFactor);
                int imgH = (int) Math.round(componentImage.getHeight() * zoomFactor);

                // Drop shadow around component image
                g2.setColor(new Color(0, 0, 0, 100));
                g2.fillRoundRect(offset.x + 4, offset.y + 4, imgW, imgH, 8, 8);

                // Draw component image
                g2.drawImage(componentImage, offset.x, offset.y, imgW, imgH, null);

                // Board outline border
                g2.setColor(new Color(60, 80, 110));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(offset.x, offset.y, imgW, imgH, 6, 6);

                // Render all calibrated pin dots
                for (Map.Entry<String, Point2D.Double> entry : calibratedPins.entrySet()) {
                    String pid = entry.getKey();
                    Point2D.Double pt = entry.getValue();
                    int px = offset.x + (int) Math.round(pt.x * imgW);
                    int py = offset.y + (int) Math.round(pt.y * imgH);

                    boolean isActive = pid.equals(selectedPinId);
                    drawPinDot(g2, px, py, pid, isActive);
                }

                // If hovering inside image and active pin has no dot yet, draw ghost placement reticle
                if (selectedPinId != null && !calibratedPins.containsKey(selectedPinId)) {
                    if (lastMousePos.x >= offset.x && lastMousePos.x <= offset.x + imgW &&
                        lastMousePos.y >= offset.y && lastMousePos.y <= offset.y + imgH) {
                        drawPlacementReticle(g2, lastMousePos.x, lastMousePos.y, selectedPinId);
                    }
                }
            } else {
                // Placeholder if no image is loaded
                drawEmptyImagePlaceholder(g2);
            }

            g2.dispose();
        }

        private void drawGrid(Graphics2D g2) {
            g2.setColor(new Color(25, 32, 45));
            int step = 25;
            for (int x = 0; x < getWidth(); x += step) {
                g2.drawLine(x, 0, x, getHeight());
            }
            for (int y = 0; y < getHeight(); y += step) {
                g2.drawLine(0, y, getWidth(), y);
            }
        }

        private void drawPinDot(Graphics2D g2, int px, int py, String pinId, boolean isActive) {
            int radius = isActive ? 8 : 6;

            // Outer glowing aura for active pin
            if (isActive) {
                g2.setColor(new Color(0, 229, 255, 60));
                g2.fillOval(px - radius - 6, py - radius - 6, (radius + 6) * 2, (radius + 6) * 2);
                g2.setColor(new Color(0, 229, 255, 120));
                g2.setStroke(new BasicStroke(2.0f));
                g2.drawOval(px - radius - 3, py - radius - 3, (radius + 3) * 2, (radius + 3) * 2);
            }

            // Pin center fill
            Color coreColor = isActive ? new Color(0, 229, 255) : new Color(0, 230, 118); // Cyan if active, Green if calibrated
            g2.setColor(coreColor);
            g2.fillOval(px - radius, py - radius, radius * 2, radius * 2);

            // Border
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(px - radius, py - radius, radius * 2, radius * 2);

            // Contact hole dot
            g2.setColor(new Color(20, 24, 32));
            g2.fillOval(px - 2, py - 2, 4, 4);

            // Pin label badge
            String label = pinId;
            Font f = new Font("Consolas", Font.BOLD, 11);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics(f);
            int textW = fm.stringWidth(label);
            int badgeX = px + radius + 4;
            int badgeY = py - 8;

            // Pill background
            g2.setColor(new Color(15, 20, 28, 220));
            g2.fillRoundRect(badgeX - 3, badgeY - 10, textW + 6, 16, 6, 6);
            g2.setColor(isActive ? new Color(0, 229, 255) : new Color(80, 100, 130));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(badgeX - 3, badgeY - 10, textW + 6, 16, 6, 6);

            // Text
            g2.setColor(isActive ? new Color(0, 229, 255) : Color.WHITE);
            g2.drawString(label, badgeX, badgeY + 2);
        }

        private void drawPlacementReticle(Graphics2D g2, int mx, int my, String pinId) {
            g2.setColor(new Color(255, 204, 0, 180));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4, 4}, 0.0f));
            g2.drawOval(mx - 10, my - 10, 20, 20);
            g2.drawLine(mx - 15, my, mx + 15, my);
            g2.drawLine(mx, my - 15, mx, my + 15);

            String text = "Click to place: " + pinId;
            g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
            g2.setColor(new Color(255, 204, 0));
            g2.drawString(text, mx + 12, my - 6);
        }

        private void drawEmptyImagePlaceholder(Graphics2D g2) {
            int w = getWidth() - 100;
            int h = getHeight() - 100;
            int x = 50, y = 50;

            g2.setColor(new Color(24, 30, 42));
            g2.fillRoundRect(x, y, w, h, 12, 12);
            g2.setColor(new Color(60, 75, 100));
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6, 6}, 0.0f));
            g2.drawRoundRect(x, y, w, h, 12, 12);

            g2.setColor(new Color(160, 180, 200));
            g2.setFont(new Font("Segoe UI", Font.BOLD, 18));
            String msg1 = "NO IMAGE LINKED FOR: " + board.getId();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg1, x + (w - fm.stringWidth(msg1)) / 2, y + h / 2 - 10);

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            String msg2 = "Click 'Change Image...' at the top-right to browse for an image file.";
            fm = g2.getFontMetrics();
            g2.drawString(msg2, x + (w - fm.stringWidth(msg2)) / 2, y + h / 2 + 18);
        }
    }
}
