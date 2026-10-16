import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

// ============================================================================
// CPS PERSISTENCE ENGINE — Layout & Board Serialization / Deserialization
// ============================================================================
// Handles:
// 1. Full Layout: all boards, pinouts, firmware state, and netlist wires.
// 2. Individual Board: board definition, pinout, components, and firmware code.
// ============================================================================
public class CpsPersistence {

    public static class LayoutData {
        public String workspaceName;
        public Map<String, CpsDomain.Board> boards = new LinkedHashMap<>();
        public List<CpsDomain.Wire> wires = new ArrayList<>();
        public int terminalPort = 0;
    }

    // =========================================================================
    // 1. LAYOUT PERSISTENCE
    // =========================================================================

    public static void saveLayout(String workspaceName,
                                  Map<String, CpsDomain.Board> boards,
                                  List<CpsDomain.Wire> wires,
                                  File targetFile) throws IOException {
        saveLayout(workspaceName, boards, wires, 0, targetFile);
    }

    public static void saveLayout(String workspaceName,
                                  Map<String, CpsDomain.Board> boards,
                                  List<CpsDomain.Wire> wires,
                                  int terminalPort,
                                  File targetFile) throws IOException {
        ensureParentDir(targetFile);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "CPS_LAYOUT_V1");
        root.put("workspace", workspaceName);
        root.put("savedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        if (terminalPort > 0) {
            root.put("terminalPort", terminalPort);
        }

        // Serialize boards
        List<Map<String, Object>> boardsList = new ArrayList<>();
        for (CpsDomain.Board b : boards.values()) {
            boardsList.add(serializeBoardToMap(b));
        }
        root.put("boards", boardsList);

        // Serialize wires
        List<Map<String, Object>> wiresList = new ArrayList<>();
        for (CpsDomain.Wire w : wires) {
            if (w.pinA != null && w.pinB != null) {
                Map<String, Object> wireMap = new LinkedHashMap<>();
                wireMap.put("name", w.wireName);
                String fromBoard = findBoardForPin(boards, w.pinA);
                String toBoard   = findBoardForPin(boards, w.pinB);
                wireMap.put("fromBoard", fromBoard);
                wireMap.put("fromPin",   w.pinA.pinId);
                wireMap.put("toBoard",   toBoard);
                wireMap.put("toPin",     w.pinB.pinId);
                wiresList.add(wireMap);
            }
        }
        root.put("wires", wiresList);

        String json = SimpleJson.toJson(root, 2);
        Files.writeString(targetFile.toPath(), json, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static LayoutData loadLayout(File sourceFile) throws IOException {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("Layout file not found: " + sourceFile.getAbsolutePath());
        }

        String content = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
        Object parsed = SimpleJson.parse(content);
        if (!(parsed instanceof Map)) {
            throw new IOException("Invalid layout file format: Root is not a JSON object");
        }

        Map<String, Object> root = (Map<String, Object>) parsed;
        LayoutData layout = new LayoutData();
        layout.workspaceName = (String) root.getOrDefault("workspace", "RestoredWorkspace");
        if (root.containsKey("terminalPort")) {
            layout.terminalPort = ((Number) root.get("terminalPort")).intValue();
        }

        // 1. Reconstitute Boards
        List<Object> boardsList = (List<Object>) root.get("boards");
        if (boardsList != null) {
            for (Object bo : boardsList) {
                if (bo instanceof Map) {
                    CpsDomain.Board b = deserializeBoardFromMap((Map<String, Object>) bo, null);
                    layout.boards.put(b.getId(), b);
                }
            }
        }

        // 2. Reconstitute Wires
        List<Object> wiresList = (List<Object>) root.get("wires");
        if (wiresList != null) {
            for (Object wo : wiresList) {
                if (wo instanceof Map) {
                    Map<String, Object> wm = (Map<String, Object>) wo;
                    String wName     = (String) wm.getOrDefault("name", "w_" + System.currentTimeMillis());
                    String fromBoard = (String) wm.get("fromBoard");
                    String fromPin   = (String) wm.get("fromPin");
                    String toBoard   = (String) wm.get("toBoard");
                    String toPin     = (String) wm.get("toPin");

                    CpsDomain.Board b1 = layout.boards.get(fromBoard);
                    CpsDomain.Board b2 = layout.boards.get(toBoard);
                    if (b1 != null && b2 != null) {
                        CpsDomain.Pin p1 = b1.getPin(fromPin);
                        CpsDomain.Pin p2 = b2.getPin(toPin);
                        if (p1 != null && p2 != null) {
                            CpsDomain.Wire wire = new CpsDomain.Wire(wName, p1, p2);
                            p1.attachWire(wire);
                            p2.attachWire(wire);
                            layout.wires.add(wire);
                        }
                    }
                }
            }
        }

        return layout;
    }

    // =========================================================================
    // 2. BOARD & FIRMWARE PERSISTENCE
    // =========================================================================

    public static void saveBoard(CpsDomain.Board board, File targetFile) throws IOException {
        ensureParentDir(targetFile);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "CPS_BOARD_V1");
        root.put("savedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("board", serializeBoardToMap(board));

        String json = SimpleJson.toJson(root, 2);
        Files.writeString(targetFile.toPath(), json, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static CpsDomain.Board loadBoard(File sourceFile, String overrideName) throws IOException {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("Board file not found: " + sourceFile.getAbsolutePath());
        }

        String content = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
        Object parsed = SimpleJson.parse(content);
        if (!(parsed instanceof Map)) {
            throw new IOException("Invalid board file format: Root is not a JSON object");
        }

        Map<String, Object> root = (Map<String, Object>) parsed;
        Map<String, Object> boardMap = (Map<String, Object>) root.get("board");
        if (boardMap == null) {
            boardMap = root; // fallback if bare board object was saved
        }

        return deserializeBoardFromMap(boardMap, overrideName);
    }

    // =========================================================================
    // HELPERS: Board Serialization / Deserialization
    // =========================================================================

    private static Map<String, Object> serializeBoardToMap(CpsDomain.Board b) {
        Map<String, Object> bm = new LinkedHashMap<>();
        bm.put("id", b.getId());
        bm.put("model", b.model);

        // Serialize pins
        List<Map<String, Object>> pinsList = new ArrayList<>();
        for (CpsDomain.Pin p : b.pins.values()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.pinId);
            pm.put("mode", p.mode.name());
            pm.put("voltage", p.voltage);
            pinsList.add(pm);
        }
        bm.put("pins", pinsList);

        // Serialize components
        List<Map<String, Object>> compList = new ArrayList<>();
        for (CpsDomain.ElectronicComponent c : b.components.values()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("type", c.getType());
            compList.add(cm);
        }
        bm.put("components", compList);

        // Serialize flashed firmware (if any)
        CpsDomain.Firmware fw = b.getFlashedFirmware();
        if (fw != null) {
            Map<String, Object> fwm = new LinkedHashMap<>();
            fwm.put("name", fw.getName());
            fwm.put("class", fw.getClass().getSimpleName());
            fwm.put("parameters", fw.getParameters());
            bm.put("firmware", fwm);
        }

        // Serialize radio telemetry bridge port (if active)
        if (b.radioBridge != null) {
            bm.put("radioPort", b.radioBridge.getPort());
        }

        return bm;
    }

    @SuppressWarnings("unchecked")
    private static CpsDomain.Board deserializeBoardFromMap(Map<String, Object> bm, String overrideName) {
        String id    = overrideName != null ? overrideName : (String) bm.get("id");
        String model = (String) bm.getOrDefault("model", "CUSTOM");

        // Factory instantiation if known standard model
        CpsDomain.Board board;
        switch (model.toUpperCase()) {
            case "ESP32_WROOM", "ESP32" -> board = HardwareDictionary.createESP32(id);
            case "NODEMCU_ESP8266", "NODEMCU" -> board = HardwareDictionary.createNodeMCU(id);
            case "ARDUINO_UNO_R3", "ARDUINO" -> board = HardwareDictionary.createArduinoUno(id);
            case "RELAY_HV_MODULE" -> board = HardwareDictionary.createRelayModule(id);
            default -> board = new CpsDomain.Board(id, model);
        }

        // Apply/overwrite pin configurations
        List<Object> pinsList = (List<Object>) bm.get("pins");
        if (pinsList != null) {
            for (Object po : pinsList) {
                if (po instanceof Map) {
                    Map<String, Object> pm = (Map<String, Object>) po;
                    String pId   = (String) pm.get("id");
                    String pMode = (String) pm.getOrDefault("mode", "DIGITAL_IN");
                    double pVolt = pm.get("voltage") instanceof Number
                        ? ((Number) pm.get("voltage")).doubleValue() : 0.0;

                    CpsDomain.PinMode mode;
                    try {
                        mode = CpsDomain.PinMode.valueOf(pMode);
                    } catch (Exception e) {
                        mode = CpsDomain.PinMode.DIGITAL_IN;
                    }

                    CpsDomain.Pin existingPin = board.getPin(pId);
                    if (existingPin == null) {
                        board.addPin(pId, mode);
                        existingPin = board.getPin(pId);
                    }
                    if (existingPin != null) {
                        existingPin.mode = mode;
                        existingPin.setVoltage(pVolt);
                    }
                }
            }
        }

        // Re-flash firmware if present in the specification
        Map<String, Object> fwm = (Map<String, Object>) bm.get("firmware");
        if (fwm != null) {
            String fwClass = (String) fwm.getOrDefault("class", "");
            String fwName  = (String) fwm.getOrDefault("name", "");
            Map<String, Object> params = (Map<String, Object>) fwm.get("parameters");
            if (params == null) params = Collections.emptyMap();

            if (fwClass.contains("SmartRoom") || fwName.contains("SmartRoom")) {
                int hysSec = 30;
                if (params.get("hysteresisSec") instanceof Number) {
                    hysSec = ((Number) params.get("hysteresisSec")).intValue();
                }
                double radarThresh = 1.5;
                if (params.get("radarThresholdV") instanceof Number) {
                    radarThresh = ((Number) params.get("radarThresholdV")).doubleValue();
                }
                String pirPin   = (String) params.getOrDefault("pirPin", "GPIO_12");
                String doorPin  = (String) params.getOrDefault("doorPin", "GPIO_14");
                String radarPin = (String) params.getOrDefault("radarPin", "GPIO_27");
                String lightPin = (String) params.getOrDefault("lightRelayPin", "GPIO_21");
                String hvacPin  = (String) params.getOrDefault("hvacRelayPin", "GPIO_22");

                SmartRoomFirmware fw = new SmartRoomFirmware(
                    pirPin, doorPin, radarPin, lightPin, hvacPin, hysSec, radarThresh);
                board.flashFirmware(fw);
            }
        }

        // Restore radio telemetry bridge if recorded
        if (bm.containsKey("radioPort")) {
            int rPort = ((Number) bm.get("radioPort")).intValue();
            board.enableRadioBridge(rPort);
        }

        return board;
    }

    private static String findBoardForPin(Map<String, CpsDomain.Board> boards, CpsDomain.Pin pin) {
        for (Map.Entry<String, CpsDomain.Board> entry : boards.entrySet()) {
            if (entry.getValue().pins.containsValue(pin)) {
                return entry.getKey();
            }
        }
        return "UNKNOWN";
    }

    private static void ensureParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    // =========================================================================
    // 3. LIGHTWEIGHT SELF-CONTAINED JSON SERIALIZER & PARSER
    // =========================================================================

    public static class SimpleJson {

        public static String toJson(Object obj, int indent) {
            StringBuilder sb = new StringBuilder();
            serializeValue(obj, sb, 0, indent);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void serializeValue(Object obj, StringBuilder sb, int depth, int indent) {
            if (obj == null) {
                sb.append("null");
            } else if (obj instanceof String) {
                sb.append("\"").append(escape((String) obj)).append("\"");
            } else if (obj instanceof Number || obj instanceof Boolean) {
                sb.append(obj);
            } else if (obj instanceof Map) {
                serializeMap((Map<String, Object>) obj, sb, depth, indent);
            } else if (obj instanceof List) {
                serializeList((List<Object>) obj, sb, depth, indent);
            } else {
                sb.append("\"").append(escape(obj.toString())).append("\"");
            }
        }

        private static void serializeMap(Map<String, Object> map, StringBuilder sb, int depth, int indent) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                indent(sb, (depth + 1) * indent);
                sb.append("\"").append(escape(e.getKey())).append("\": ");
                serializeValue(e.getValue(), sb, depth + 1, indent);
                if (++i < map.size()) sb.append(",");
                sb.append("\n");
            }
            indent(sb, depth * indent);
            sb.append("}");
        }

        private static void serializeList(List<Object> list, StringBuilder sb, int depth, int indent) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, (depth + 1) * indent);
                serializeValue(list.get(i), sb, depth + 1, indent);
                if (i + 1 < list.size()) sb.append(",");
                sb.append("\n");
            }
            indent(sb, depth * indent);
            sb.append("]");
        }

        private static void indent(StringBuilder sb, int spaces) {
            sb.append(" ".repeat(Math.max(0, spaces)));
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        // --- Parser ---
        public static Object parse(String json) {
            return new Parser(json).parseValue();
        }

        private static class Parser {
            private final String src;
            private int idx = 0;

            Parser(String src) { this.src = src; }

            Object parseValue() {
                skipWs();
                if (idx >= src.length()) return null;
                char c = src.charAt(idx);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                if (c == '-' || Character.isDigit(c)) return parseNumber();
                throw new IllegalArgumentException("Unexpected char at " + idx + ": " + c);
            }

            Map<String, Object> parseObject() {
                Map<String, Object> map = new LinkedHashMap<>();
                idx++; // skip '{'
                while (true) {
                    skipWs();
                    if (idx < src.length() && src.charAt(idx) == '}') { idx++; break; }
                    String key = parseString();
                    skipWs();
                    if (idx < src.length() && src.charAt(idx) == ':') idx++;
                    Object val = parseValue();
                    map.put(key, val);
                    skipWs();
                    if (idx < src.length() && src.charAt(idx) == ',') idx++;
                    else if (idx < src.length() && src.charAt(idx) == '}') { idx++; break; }
                }
                return map;
            }

            List<Object> parseArray() {
                List<Object> list = new ArrayList<>();
                idx++; // skip '['
                while (true) {
                    skipWs();
                    if (idx < src.length() && src.charAt(idx) == ']') { idx++; break; }
                    Object val = parseValue();
                    list.add(val);
                    skipWs();
                    if (idx < src.length() && src.charAt(idx) == ',') idx++;
                    else if (idx < src.length() && src.charAt(idx) == ']') { idx++; break; }
                }
                return list;
            }

            String parseString() {
                skipWs();
                if (idx >= src.length() || src.charAt(idx) != '"') return "";
                idx++; // skip open quote
                StringBuilder sb = new StringBuilder();
                while (idx < src.length()) {
                    char c = src.charAt(idx++);
                    if (c == '"') break;
                    if (c == '\\' && idx < src.length()) {
                        char esc = src.charAt(idx++);
                        switch (esc) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            Boolean parseBoolean() {
                skipWs();
                if (src.startsWith("true", idx)) { idx += 4; return true; }
                if (src.startsWith("false", idx)) { idx += 5; return false; }
                return false;
            }

            Object parseNull() {
                skipWs();
                if (src.startsWith("null", idx)) idx += 4;
                return null;
            }

            Number parseNumber() {
                skipWs();
                int start = idx;
                while (idx < src.length()) {
                    char c = src.charAt(idx);
                    if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                        idx++;
                    } else break;
                }
                String numStr = src.substring(start, idx);
                if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                    return Double.parseDouble(numStr);
                }
                try {
                    return Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(numStr);
                }
            }

            void skipWs() {
                while (idx < src.length() && Character.isWhitespace(src.charAt(idx))) idx++;
            }
        }
    }
}
