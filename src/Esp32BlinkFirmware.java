import java.text.SimpleDateFormat;
import java.util.*;

// ============================================================================
// ON-BOARD MICROCONTROLLER FIRMWARE: ESP32 SIMPLE BLINK
// ============================================================================
// Represents compiled firmware flashed into the ESP32 node.
// Toggles the target LED GPIO pin at a configurable interval and emits
// real-time telemetry over Port 1 (Radio Bridge) and the terminal bus.
// ============================================================================
public class Esp32BlinkFirmware implements CpsDomain.Firmware {

    // ─── Hardware Pin Configuration ──────────────────────────────────────────
    private final String ledPin;          // e.g. "GPIO_02" (Standard built-in ESP32 LED)

    // ─── Operational Parameters ──────────────────────────────────────────────
    private int periodMs;                 // Total blink cycle period in milliseconds (default 1000ms = 1 Hz)
    private volatile boolean paused = false;

    // ─── Internal RAM State on the MCU ───────────────────────────────────────
    private boolean ledState = false;     // false = LOW (0.0V), true = HIGH (3.3V)
    private long toggleCount = 0;
    private long bootTimeMs = 0;
    private long lastToggleMs = 0;
    private long tickCounter = 0;

    public Esp32BlinkFirmware(String ledPin, int periodMs) {
        this.ledPin = (ledPin != null && !ledPin.isEmpty()) ? ledPin : "GPIO_02";
        this.periodMs = Math.max(100, periodMs);
    }

    public Esp32BlinkFirmware(int periodMs) {
        this("GPIO_02", periodMs);
    }

    public Esp32BlinkFirmware() {
        this("GPIO_02", 1000); // 1000ms period (1 Hz: 500ms ON / 500ms OFF)
    }

    @Override
    public String getName() {
        return "ESP32_SimpleBlink_v1.0";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ledPin", ledPin);
        p.put("periodMs", periodMs);
        p.put("paused", paused);
        return p;
    }

    @Override
    public void setup(CpsDomain.McuHAL mcu) {
        bootTimeMs = mcu.millis();
        lastToggleMs = bootTimeMs;
        tickCounter = 0;
        toggleCount = 0;
        ledState = false;

        // Drive initial pin state to LOW (0.0V)
        mcu.digitalWrite(ledPin, CpsDomain.SignalState.LOW);
        mcu.setProperty("ledPin", ledPin);
        mcu.setProperty("ledState", "LOW");
        mcu.setProperty("voltage", 0.0);
        mcu.setProperty("toggleCount", 0L);

        System.out.printf("[%s] Booted on %s [%s]. Output pin: %s (Period: %dms / %.1fHz)%n",
            getName(), mcu.getBoardId(), mcu.getModel(), ledPin, periodMs, 1000.0 / periodMs);

        broadcastTelemetry(mcu);
    }

    @Override
    public void loop(CpsDomain.McuHAL mcu) {
        if (paused) return;

        tickCounter++;
        long now = mcu.millis();

        // Toggle state every half period (or on discrete 1 Hz clock ticks when tick-driven)
        ledState = !ledState;
        toggleCount++;
        lastToggleMs = now;

        double voltage = ledState ? 3.3 : 0.0;
        mcu.digitalWrite(ledPin, ledState ? CpsDomain.SignalState.HIGH : CpsDomain.SignalState.LOW);

        mcu.setProperty("ledState", ledState ? "HIGH" : "LOW");
        mcu.setProperty("voltage", voltage);
        mcu.setProperty("toggleCount", toggleCount);

        broadcastTelemetry(mcu);
    }

    public void setPeriodMs(int ms) {
        this.periodMs = Math.max(100, ms);
    }

    public int getPeriodMs() {
        return periodMs;
    }

    public double getFrequencyHz() {
        return 1000.0 / periodMs;
    }

    public boolean getLedState() {
        return ledState;
    }

    public long getToggleCount() {
        return toggleCount;
    }

    public String getLedPin() {
        return ledPin;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean p) {
        this.paused = p;
    }

    public void toggleManually(CpsDomain.McuHAL mcu) {
        ledState = !ledState;
        toggleCount++;
        mcu.digitalWrite(ledPin, ledState ? CpsDomain.SignalState.HIGH : CpsDomain.SignalState.LOW);
        broadcastTelemetry(mcu);
    }

    private void broadcastTelemetry(CpsDomain.McuHAL mcu) {
        long uptimeSec = (mcu.millis() - bootTimeMs) / 1000L;
        String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date());

        String json = String.format(Locale.US,
            "{\"device\":\"%s\",\"firmware\":\"%s\",\"time\":\"%s\",\"ledPin\":\"%s\"," +
            "\"ledState\":\"%s\",\"voltage\":%.2f,\"toggleCount\":%d,\"periodMs\":%d," +
            "\"freqHz\":%.2f,\"dutyCycle\":50.0,\"uptimeSec\":%d}",
            mcu.getBoardId(), getName(), timeStr, ledPin,
            ledState ? "HIGH" : "LOW",
            ledState ? 3.30 : 0.00,
            toggleCount, periodMs, 1000.0 / periodMs, uptimeSec
        );

        mcu.radioTransmit(json);
    }
}
