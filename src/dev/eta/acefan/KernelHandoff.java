package dev.eta.acefan;

/** A fresh, root-confirmed release token. No target, temperature or hardware writes. */
public final class KernelHandoff {
    private KernelHandoff() { }
    public static boolean manual(String mode) { return "1".equals(mode) || "2".equals(mode); }
    public static boolean released(KernelReport report, long revision) {
        return revision > 0 && report.get("configRevision") == revision
                && report.get("configProtocol") == 2 && report.get("owned") == 0
                && report.get("configured") == 0;
    }
    public static boolean fresh(long at, long now, String mode, String level,
            String currentMode, String currentLevel) {
        return at > 0 && now >= at && now - at < 5000 && manual(mode)
                && mode.equals(currentMode) && level != null && level.equals(currentLevel);
    }
    /** Three fresh proofs maximum per settings/config event; no perpetual OEM retry loop. */
    public static final class Retry {
        private long revision = -1, generation = -1, attemptedAt;
        private int attempts;
        private boolean done;
        public boolean matches(long r, long g) { return revision == r && generation == g; }
        public boolean completed() { return done; }
        public boolean exhausted() { return attempts >= 3; }
        public void complete() { done = true; }
        public boolean begin(long r, long g, long now) {
            if (!matches(r, g)) { revision = r; generation = g; attempts = 0; done = false; }
            if (done || attempts >= 3 || (attempts > 0 && now - attemptedAt < 1500)) return false;
            attemptedAt = now; attempts++; return true;
        }
    }
}
