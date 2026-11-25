package dev.eta.acefan;

public final class FanRpmTest {
    private static final long WALL = 1_710_000_000_000L;
    private static int checks;
    private static FanRpm.View view(FanRpm cache, long age, Long enabled, Long level, Integer debug, String status) {
        return cache.snapshot(WALL + age, 10_000L + age, enabled, level, debug, status);
    }
    private static boolean event(FanRpm cache, long age, String state, String raw, long level) {
        return cache.accept(FanRpm.DEVPATH, state, raw, WALL + age, 10_000L + age, 1L, level, -1, "NORMAL");
    }
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        FanRpm c = new FanRpm();
        check(Double.isNaN(view(c, 0, 1L, 10L, -1, "NORMAL").value), "no observer is unknown");
        c.observer(true);
        check(!c.accept("/other", "NORMAL", "12000", WALL, 10_000, 1L, 10L, -1, "NORMAL"), "wrong DEVPATH rejected");
        check(event(c, 0, "NORMAL", "12000", 10), "exact event consumed");
        check(Double.isNaN(view(c, 1, 1L, 10L, -1, "NORMAL").value), "edge event waits for a complete stable window");
        event(c, 2_000, "NORMAL", "12000", 10);
        FanRpm.View first = view(c, 2_001, 1L, 10L, -1, "NORMAL");
        check(first.value == 12000 && first.level == 10 && first.wall == WALL + 2_000, "measurement bound to effective level");
        check(event(c, 6_200, "NORMAL", "12500", 10), "later same-level event received");
        FanRpm.View held = view(c, 3_600_000, 1L, 10L, -1, "NORMAL");
        check(held.value == 12000 && held.wall == WALL + 2_000 && held.raw == 12500, "same level holds first measured value without TTL");
        check(held.generation == first.generation && "held".equals(held.quality), "holding does not create a new generation");
        check(Double.isNaN(view(c, 3_600_001, 1L, 11L, -1, "NORMAL").value), "level change invalidates before new sample");
        check(event(c, 3_600_100, "NORMAL", "13800", 11), "new level measurement");
        check(Double.isNaN(view(c, 3_600_101, 1L, 11L, -1, "NORMAL").value), "new level waits for settling");
        event(c, 3_602_001, "NORMAL", "13800", 11);
        check(view(c, 3_602_002, 1L, 11L, -1, "NORMAL").value == 13800, "new level replaces hold after settling");
        check(Double.isNaN(view(c, 3_600_102, 0L, 11L, -1, "NORMAL").value), "disable clears hold");
        check(Double.isNaN(view(c, 3_600_103, 1L, 11L, -1, "NORMAL").value), "restart cannot resurrect old hold");
        event(c, 3_600_104, "NORMAL", "14000", 11);
        check(Double.isNaN(view(c, 3_600_105, 1L, 11L, 12000, "NORMAL").value), "debug override rejected");
        check(Double.isNaN(view(c, 3_600_106, 1L, 11L, -1, "NORMAL").value), "debug recovery waits for a real new event");
        event(c, 3_600_107, "DAMAGED", "0", 11);
        check("zero_report".equals(view(c, 3_600_108, 1L, 11L, -1, "NORMAL").quality), "event zero not silently treated as stopped");
        for (String raw : new String[]{"-1", "1.2", "NaN", "200001", "9999999999999", " 100", "+100", ""}) {
            event(c, 3_600_109, "NORMAL", raw, 11);
            check(Double.isNaN(view(c, 3_600_110, 1L, 11L, -1, "NORMAL").value), "malformed input rejected: " + raw);
        }
        event(c, 3_600_111, "RPM_LOW", "10000", 11);
        check(Double.isNaN(view(c, 3_600_112, 1L, 11L, -1, "NORMAL").value), "same-package abnormal status rejected");
        event(c, 3_600_113, "NORMAL", "14000", 11);
        event(c, 3_600_114, "PRESENT", null, 11);
        check(Double.isNaN(view(c, 3_600_115, 1L, 11L, -1, "NORMAL").value), "hall-only event is not RPM");
        event(c, 3_600_116, "NORMAL", "14000", 11);
        check(Double.isNaN(view(c, 3_600_117, 1L, null, -1, "NORMAL").value), "unknown feedback clears hold");
        event(c, 3_600_118, "NORMAL", "14000", 11);
        check(Double.isNaN(view(c, 3_600_119, 1L, 11L, -1, "BLOCKED").value), "current driver fault clears hold");
        event(c, 3_600_120, "NORMAL", "14000", 11);
        check(Double.isNaN(view(c, -1, 1L, 11L, -1, "NORMAL").value), "clock reversal clears hold");
        event(c, 3_603_121, "NORMAL", "14000", 11);
        check(c.request(12), "request edge observed without claiming effective level");
        check(view(c, 3_603_122, 1L, 11L, -1, "NORMAL").value == 14000, "positive request without effective change keeps first hold");
        event(c, 3_603_123, "NORMAL", "15000", 11);
        check(!c.request(12) && view(c, 3_603_124, 1L, 11L, -1, "NORMAL").value == 14000, "identical effective level keeps first measurement");
        long oldToken = c.token();
        c.request(13);
        check(!c.accept(oldToken, FanRpm.DEVPATH, "NORMAL", "16000", WALL + 3_603_125, 3_613_125,
                1L, 13L, -1, "NORMAL"), "in-flight old generation rejected before reading new level");
        check(view(c, 3_603_126, 1L, 11L, -1, "NORMAL").value == 14000, "rejected edge does not replace same-level hold");
        check(Double.isNaN(c.snapshot(WALL + 3_663_127, 3_613_127, 1L, 11L, -1, "NORMAL").value), "wall clock drift invalidates backend too");
        c.accept(FanRpm.DEVPATH, "NORMAL", "14500", WALL + 3_663_128, 3_613_128, 1L, 11L, -1, "NORMAL");
        check(c.snapshot(WALL + 3_663_129, 3_613_129, 1L, 11L, -1, "NORMAL").value == 14500, "fresh event recovers after clock adjustment");
        c.observer(false);
        check(Double.isNaN(view(c, 3_600_125, 1L, 11L, -1, "NORMAL").value), "observer failure invalidates");
        check(!FanRpm.valid(0) && !FanRpm.valid(Double.NaN) && !FanRpm.valid(12.2), "no synthetic or fractional RPM");
        System.out.println("FanRpmTest: " + checks + " checks passed");
    }
}
