package dev.eta.acefan;

/** Bounded, boot-bound read-only report from the existing root watchdog. Not an RPM source. */
final class FanDebugReport {
    static final String PROPERTY = "sys.eta.acefan.fgdebug";
    static final long FRESH_MS = 15_000L;

    static Integer parse(String report, String boot, long now) {
        if (report == null || report.length() > 91 || boot == null || boot.length() != 36 || now < 0) return null;
        String[] fields = report.split("\\|", -1);
        if (fields.length != 3 || !boot.equals(fields[0]) || !digits(fields[1])) return null;
        try {
            long at = Long.parseLong(fields[1]);
            if (at < 0 || now < at || now - at >= FRESH_MS) return null;
            String flag = fields[2];
            if (!"-1".equals(flag) && !digits(flag)) return null;
            int value = Integer.parseInt(flag);
            return value < -1 ? null : value;
        } catch (NumberFormatException ignored) { return null; }
    }
    private static boolean digits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }
}
