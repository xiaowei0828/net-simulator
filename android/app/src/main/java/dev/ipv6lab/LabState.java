package dev.ipv6lab;

import android.util.Log;
import dev.ipv6lab.core.Telemetry;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Logcat diagnostics with a bounded in-memory copy for the activity. */
public final class LabState {
    public static final String TAG = "IPv6Lab";
    public static volatile String phase = "已停止";
    public static volatile String detail = "启动后，被测应用将只能通过 IPv6 通信。";
    public static volatile String network = "等待连接";
    public static volatile boolean active;
    public static volatile Telemetry telemetry = new Telemetry(LabState::log, LabState::error);
    private static final ArrayDeque<String> logs = new ArrayDeque<>();
    private LabState() {}
    public static void log(String message) {
        Log.i(TAG, message);
        append(message);
    }
    public static void error(String message) { error(message, null); }
    public static void error(String message, Throwable cause) {
        Log.e(TAG, message, cause);
        append(message);
    }
    private static synchronized void append(String message) {
        if (logs.size() >= 150) logs.removeFirst();
        logs.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
    }
    public static synchronized String logs() { return String.join("\n", logs); }
    public static synchronized void reset() { logs.clear(); telemetry = new Telemetry(LabState::log, LabState::error); }
}
