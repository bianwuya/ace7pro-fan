package dev.eta.acefan;

import java.util.HashMap;
import java.util.Map;

/** Bounded parser for the kernel messages delivered by Android's UEventObserver native receiver. */
final class RootFgEvent {
    final String path, state, rpm;
    private RootFgEvent(String path, String state, String rpm) {
        this.path = path; this.state = state; this.rpm = rpm;
    }
    static RootFgEvent parse(String message) {
        if (message == null || message.length() > 16384 || message.indexOf('\0') < 0) return null;
        Map<String, String> fields = new HashMap<>();
        int start = 0, count = 0;
        while (start < message.length()) {
            int end = message.indexOf('\0', start);
            if (end < 0) return null;
            if (++count > 128) return null;
            String item = message.substring(start, end);
            int equal = item.indexOf('=');
            if (equal > 0) {
                String key = item.substring(0, equal), value = item.substring(equal + 1);
                if (fields.put(key, value) != null) return null;
            }
            start = end + 1;
        }
        if (!FanRpm.DEVPATH.equals(fields.get("DEVPATH")) || !"leds".equals(fields.get("SUBSYSTEM"))
                || !"change".equals(fields.get("ACTION"))) return null;
        String state = fields.get("FAN_STATE"), rpm = fields.get("FAN_RPM");
        if (state == null || state.length() > 32 || (rpm != null && rpm.length() > 6)) return null;
        return new RootFgEvent(fields.get("DEVPATH"), state, rpm);
    }
}
