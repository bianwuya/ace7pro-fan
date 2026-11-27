package dev.eta.acefan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Only level/state are writable. No permission changes, subprocesses or retry loop. */
final class FanNodeIo {
    static final String ROOT = "/sys/class/leds/fan/";
    static final String DIRECT_TRANSPORT = "direct-sysfs", OEM_TRANSPORT = "oem-fan-only-misc-aidl";
    interface Access {
        String read(String name) throws IOException;
        boolean writable(String name);
        int write(String name, String value) throws IOException;
        default String transport() { return DIRECT_TRANSPORT; }
    }
    /** Only the two audited fan-local original methods, never a generic misc-node writer. */
    interface NodeBridge {
        boolean level(int value) throws IOException;
        void state(boolean on) throws IOException;
    }
    /** Select before the first write, without a write probe. Selection is immutable even on error. */
    static final class RoutedAccess implements Access {
        private final Access files;
        private NodeBridge bridge;
        private boolean selected, direct, bridged;
        RoutedAccess(Access files) { this.files = files; }
        synchronized void bindBridge(NodeBridge bridge) {
            if (selected || this.bridge != null || bridge == null) throw new IllegalStateException("fan bridge already bound/transport selected");
            this.bridge = bridge;
        }
        private void select() {
            if (selected) return;
            selected = true; // a failed capability check must not become a later route change
            boolean level = files.writable("level"), state = files.writable("state");
            direct = level && state;
            bridged = !direct && bridge != null;
        }
        public String read(String name) throws IOException {
            if (!"level".equals(name) && !"state".equals(name) && !"level_eff".equals(name) && !"status".equals(name))
                throw new IOException("unknown fan read node");
            return files.read(name); // no OEM defaults: all confirmation comes from the original sysfs reader
        }
        public synchronized boolean writable(String name) {
            if (!"level".equals(name) && !"state".equals(name)) return false;
            select();
            return direct || bridged;
        }
        public synchronized int write(String name, String value) throws IOException {
            // Validate even if a caller bypasses FanNodeIo's tighter 1..maximum policy check.
            int level = -1;
            if ("level".equals(name)) {
                try { level = Integer.parseInt(value); }
                catch (RuntimeException e) { throw new IOException("invalid fan level", e); }
                if (level < 1 || level > 20 || !String.valueOf(level).equals(value)) throw new IOException("invalid fan level");
            } else if (!"state".equals(name) || (!"0".equals(value) && !"1".equals(value))) {
                throw new IOException("invalid/read-only fan write");
            }
            if (!selected) throw new IOException("fan transport not selected");
            if (direct) return files.write(name, value); // failure is final; NEVER retry through the bridge
            if (!bridged) throw new IOException("fan transport unavailable");
            if ("level".equals(name)) {
                if (!bridge.level(level)) throw new IOException("OEM fan level rejected");
            } else bridge.state("1".equals(value)); // void is not confirmation; FanNodeIo must read state back
            return value.length();
        }
        public synchronized String transport() {
            return direct ? DIRECT_TRANSPORT : bridged ? OEM_TRANSPORT : selected ? "unavailable" : "unselected";
        }
    }
    static final class Files implements Access {
        private File file(String name) {
            if (!"level".equals(name) && !"state".equals(name) && !"level_eff".equals(name) && !"status".equals(name))
                throw new IllegalArgumentException("fan node");
            return new File(ROOT + name);
        }
        public String read(String name) throws IOException {
            byte[] data = new byte[256];
            try (FileInputStream in = new FileInputStream(file(name))) {
                int n = in.read(data);
                if (n <= 0 || n == data.length) throw new IOException("invalid " + name + " length");
                return new String(data, 0, n, StandardCharsets.US_ASCII).trim();
            }
        }
        public boolean writable(String name) { return file(name).canWrite(); }
        public int write(String name, String value) throws IOException {
            if (!"level".equals(name) && !"state".equals(name)) throw new IOException("read-only node");
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            try (FileOutputStream out = new FileOutputStream(file(name))) { out.write(bytes); }
            return bytes.length;
        }
    }
    static final class Reading {
        final int state, level, effective;
        final String status;
        Reading(int state, int level, int effective, String status) {
            this.state = state; this.level = level; this.effective = effective; this.status = status;
        }
        boolean healthy() { return "NORMAL".equals(status); }
    }

    private final Access access;
    private int maximum;
    private boolean ready, touched, prepared, uncertainStop, stopConfirmed;
    private Reading reading;
    private String error = "", stopError = "";
    private long writes;
    FanNodeIo(Access access) { this.access = access; }

    synchronized boolean probe(int maximum) {
        if (ready) return error.isEmpty();
        if (!error.isEmpty()) return false; // failed closed for this process; not a permission-probing loop
        this.maximum = maximum;
        try {
            if (maximum < 6 || maximum > 20) throw new IOException("unsupported maximum=" + maximum);
            if (!access.writable("level") || !access.writable("state")) throw new IOException("level/state not writable by service identity");
            reading = readAll();
            if (!reading.healthy()) throw new IOException("fan status=" + reading.status);
            ready = true;
            return true;
        } catch (Exception e) { fail("probe", e); return false; }
    }

    synchronized Reading inspect() {
        if (!ready || !error.isEmpty()) return null;
        try {
            Reading previous = reading;
            reading = null; // a failed read cannot leave old state/request/effective advertised as current
            Reading next = readAll();
            reading = next;
            // level_eff is deliberately NOT compared: a driver derate is never a conflict.
            if (touched && previous != null && (next.state != previous.state || next.level != previous.level))
                throw new IOException("external state/request change; ownership released");
            if (!next.healthy()) throw new IOException("fan status=" + next.status);
            return next;
        } catch (Exception e) { fail("read", e); return null; }
    }

    synchronized boolean level(int level) {
        if (!ready || !error.isEmpty()) return false;
        if (level <= 0 || level > maximum) { fail("level", new IOException("invalid request=" + level)); return false; }
        Reading before = inspect();
        if (before == null) return false;
        // An enable edge prepares a positive level even if a stale stopped request matches it.
        if (before.level == level && (before.state == 1 || prepared)) return true;
        if (!writeAndVerify("level", String.valueOf(level), level)) return false;
        prepared = true;
        return true;
    }

    synchronized boolean state(boolean on) {
        if (!on) return stop();
        if (!ready || !error.isEmpty()) return false;
        Reading before = inspect();
        if (before == null) return false;
        if (before.state == (on ? 1 : 0)) return true;
        if (on && !prepared) { fail("state", new IOException("enable without prepared positive level")); return false; }
        boolean ok = writeAndVerify("state", on ? "1" : "0", on ? 1 : 0);
        if (!on) prepared = false;
        return ok;
    }

    /** Safety disable needs ONLY state. A bad level/status/effective read cannot block it.
     * Unknown pre-state permits one best-effort disable; unknown post-state latches no-retry.
     */
    synchronized boolean stop() {
        stopConfirmed = false;
        if (!ready || uncertainStop) return false;
        prepared = false;
        try {
            int before = readState();
            if (before == 0) {
                recordStopState(0); touched = false; stopConfirmed = true; stopError = "";
                return true;
            }
        } catch (Exception ignored) {
            // Closing is safe even when pre-state is unknown. Do not require unrelated telemetry.
        }
        uncertainStop = true; // latch BEFORE the single attempt, including an exception/short write
        Exception problem = null;
        try {
            writes++;
            if (access.write("state", "0") != 1) throw new IOException("short stop write");
        } catch (Exception e) { problem = e; }
        try {
            int after = readState();
            recordStopState(after);
            stopConfirmed = after == 0;
            if (!stopConfirmed && problem == null) problem = new IOException("stop readback mismatch: state=" + after);
        } catch (Exception e) {
            recordStopState(-1); // never retain an old state=0 as a current confirmation
            if (problem == null) problem = e;
        }
        if (stopConfirmed) touched = false;
        if (problem != null) {
            stopError = problem.getClass().getSimpleName() + ": " + String.valueOf(problem.getMessage());
            fail("stop", problem);
            return false; // even a matching post-state does not turn a failed syscall into success
        }
        uncertainStop = false; stopError = "";
        return true;
    }

    synchronized boolean stopConfirmed() { return stopConfirmed; }
    synchronized String stopError() { return stopError; }
    private int readState() throws IOException {
        try {
            int value = Integer.parseInt(access.read("state").trim());
            if (value != 0 && value != 1) throw new IOException("invalid fan state=" + value);
            return value;
        } catch (NumberFormatException e) { throw new IOException("non-integer state", e); }
    }
    private void recordStopState(int state) {
        // Only state was verified. Keep the last request for diagnostics, not stale effective/status.
        reading = new Reading(state, reading == null ? -1 : reading.level, -1, "");
    }

    synchronized void claim() { if (ready && error.isEmpty() && reading != null) touched = true; }
    synchronized void relinquish() { touched = false; prepared = false; }
    synchronized Reading last() { return reading; }
    synchronized boolean ready() { return ready && error.isEmpty(); }
    synchronized String error() { return error; }
    synchronized long writes() { return writes; }
    synchronized String transport() { return access.transport(); }

    private boolean writeAndVerify(String node, String value, int expected) {
        Reading before = reading;
        Exception problem = null;
        try {
            writes++;
            if (access.write(node, value) != value.length()) throw new IOException("short write");
        } catch (Exception e) { problem = e; }
        // Always read back after an attempted write, including an exception/short write. Never retry it.
        reading = null; // unknown post-write state must not reuse a pre-write reading
        try {
            reading = readAll();
            if (("level".equals(node) ? reading.level : reading.state) != expected)
                throw new IOException("readback mismatch");
            if (before != null && ("level".equals(node) ? reading.state != before.state : reading.level != before.level))
                throw new IOException("unrelated state/request changed during write");
            if (("level".equals(node) || expected == 1) && !reading.healthy())
                throw new IOException("fan fault after write=" + reading.status);
        } catch (Exception e) { if (problem == null) problem = e; }
        if (problem != null) { fail(node, problem); return false; }
        touched = true;
        return true;
    }
    private Reading readAll() throws IOException {
        try {
            int state = Integer.parseInt(access.read("state").trim());
            int level = Integer.parseInt(access.read("level").trim());
            int effective = Integer.parseInt(access.read("level_eff").trim());
            String status = access.read("status").trim();
            if ((state != 0 && state != 1) || level < 0 || (state == 1 && level == 0) || level > maximum || effective < 0 || effective > maximum || status.isEmpty())
                throw new IOException("node range/unknown status");
            return new Reading(state, level, effective, status);
        } catch (NumberFormatException e) { throw new IOException("non-integer node", e); }
    }
    private void fail(String operation, Exception e) {
        if (error.isEmpty()) error = operation + ": " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
}
