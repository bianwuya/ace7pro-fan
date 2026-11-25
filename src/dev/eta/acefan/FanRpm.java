package dev.eta.acefan;

/** Passive FG-event latch. No rpm-node polling, target table, level conversion or control I/O. */
public final class FanRpm {
    public static final String SOURCE = "oplus_fan:uevent_fg";
    public static final String DEVPATH = "/devices/platform/soc/soc:pwm_fan/leds/fan";
    private static final long MAX_RPM = 200_000L; // Parser sanity bound, not a hardware rating.
    private boolean attached;
    private String observer = "not_attached", quality = "not_attached";
    /** Wait beyond the known 1.2 s FG window after observing an effective-level edge. */
    public static final long SETTLE_MS = 2_000L;
    private long level = -1L, generation, changedAt = -1L, epoch;
    private int lastRequest = -1;
    private Event measured, latest;

    private static final class Event {
        final long raw, wall, elapsed;
        final String state;
        Event(long raw, String state, long wall, long elapsed) {
            this.raw = raw; this.state = state; this.wall = wall; this.elapsed = elapsed;
        }
    }

    public static final class View {
        public final String observer, quality, state;
        public final long raw, wall, elapsed, level, generation;
        public final double value;
        View(String observer, String quality, Event shown, Event latest, long level, long generation) {
            this.observer = observer; this.quality = quality; this.level = level; this.generation = generation;
            state = latest == null ? "" : latest.state;
            raw = latest == null ? -1L : latest.raw;
            wall = shown == null ? -1L : shown.wall;
            elapsed = shown == null ? -1L : shown.elapsed;
            value = "held".equals(quality) && shown != null ? shown.raw : Double.NaN;
        }
    }

    public synchronized void observer(boolean ready) {
        attached = ready; observer = ready ? "attached" : "unavailable";
        invalidate(); quality = ready ? "waiting_event" : "observer_unavailable";
    }

    /** A stop clears the hold; positive request edges only reject in-flight reads, never define the effective level. */
    public synchronized void invalidate() {
        measured = null; latest = null; level = -1L; changedAt = -1L; generation++; epoch++;
        quality = attached ? "waiting_event" : observer;
    }

    public synchronized boolean request(int requested) {
        boolean changed = requested != lastRequest || requested <= 0;
        lastRequest = requested;
        if (changed) epoch++; // Reject in-flight evidence, but a positive request is not effective feedback.
        if (requested <= 0) invalidate();
        return changed;
    }

    public synchronized long token() { return epoch; }

    private void observe(long wall, long elapsed, Long enabled, Long effective, Integer debug, String status) {
        long current = enabled != null && enabled == 1L && effective != null && effective > 0L && effective <= 20L
                ? effective : -1L;
        if (level != current) {
            level = current; measured = null; latest = null; changedAt = elapsed; generation++; epoch++;
            quality = "waiting_event";
        }
        String blocked = null;
        if (enabled == null || effective == null) blocked = "driver_unknown";
        else if (enabled != 1L || effective == 0L) blocked = "fan_off";
        else if (current < 0L) blocked = "driver_unknown";
        else if (debug == null || debug < -1) blocked = "debug_unknown";
        else if (debug >= 0) blocked = "debug_override";
        else if (!"NORMAL".equals(status)) blocked = "driver_fault";
        else if (!attached) blocked = observer;
        if (blocked != null) {
            measured = null; quality = blocked;
        } else if (measured != null) {
            // A held value intentionally has no short TTL; current driver/boot evidence must remain available.
            if (wall < measured.wall || elapsed < measured.elapsed ||
                    Math.abs((wall - measured.wall) - (elapsed - measured.elapsed)) > 10_000L) {
                measured = null; quality = "clock_changed";
            }
            else quality = "held";
        } else if (!"zero_report".equals(quality) && !"invalid_event".equals(quality)) quality = "waiting_event";
    }

    /** Copy only OEM-delivered events, binding the first valid reading to this effective-level generation. */
    public synchronized boolean accept(String path, String state, String raw, long wall, long elapsed,
            Long enabled, Long effective, Integer debug, String status) {
        return accept(token(), path, state, raw, wall, elapsed, enabled, effective, debug, status);
    }

    public synchronized boolean accept(long token, String path, String state, String raw, long wall, long elapsed,
            Long enabled, Long effective, Integer debug, String status) {
        if (!attached || token != epoch || !DEVPATH.equals(path)) return false;
        observe(wall, elapsed, enabled, effective, debug, status);
        if (raw == null) { invalidate(); return true; } // Hall/presence event is not a measurement.
        latest = new Event(parse(raw), knownState(state), wall, elapsed);
        if (level < 0L || debug == null || debug != -1 || !"NORMAL".equals(status)) return true;
        if (wall <= 0L || elapsed < 0L || elapsed < changedAt || latest.raw < 0L || latest.state.isEmpty()) {
            measured = null; quality = "invalid_event";
        } else if (latest.raw == 0L) {
            measured = null; quality = "zero_report";
        } else if (!"NORMAL".equals(latest.state)) {
            measured = null; quality = "driver_fault";
        } else if (measured == null && elapsed - changedAt >= SETTLE_MS) {
            measured = latest; quality = "held";
        }
        // Later NORMAL events at the same level do not redraw the latched measurement.
        return true;
    }

    public synchronized View snapshot(long wall, long elapsed, Long enabled, Long effective, Integer debug, String status) {
        observe(wall, elapsed, enabled, effective, debug, status);
        return new View(observer, quality, measured, latest, level, generation);
    }

    public static boolean valid(double value) {
        return value > 0.0 && value <= MAX_RPM && value == Math.rint(value);
    }

    private static long parse(String raw) {
        if (raw.isEmpty() || raw.length() > 6) return -1L;
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) < '0' || raw.charAt(i) > '9') return -1L;
        try { long n = Long.parseLong(raw); return n <= MAX_RPM ? n : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static String knownState(String state) {
        if (state == null) return "";
        switch (state) {
            case "NORMAL": case "BLOCKED": case "DAMAGED": case "RPM_LOW":
            case "NOT_PRESENT": case "PRESENT": case "OCP_ISSUE": return state;
            default: return "";
        }
    }
}
