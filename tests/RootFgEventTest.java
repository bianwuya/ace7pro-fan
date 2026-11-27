package dev.eta.acefan;

public final class RootFgEventTest {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static String event(String rpm) {
        return "change@" + FanRpm.DEVPATH + "\0ACTION=change\0DEVPATH=" + FanRpm.DEVPATH
                + "\0SUBSYSTEM=leds\0FAN_STATE=NORMAL\0FAN_RPM=" + rpm + "\0SEQNUM=22\0";
    }
    public static void main(String[] args) {
        RootFgEvent e = RootFgEvent.parse(event("13099"));
        check(e != null && "13099".equals(e.rpm) && "NORMAL".equals(e.state) && FanRpm.DEVPATH.equals(e.path), "exact actual FG envelope accepted");
        check(RootFgEvent.parse(null) == null, "null rejected");
        check(RootFgEvent.parse("") == null, "empty rejected");
        check(RootFgEvent.parse(event("13099").replace('\0', '\n')) == null, "log text is not a kernel message");
        check(RootFgEvent.parse(event("13099").replace(FanRpm.DEVPATH, "/other")) == null, "unrelated path rejected");
        check(RootFgEvent.parse(event("13099").replace("SUBSYSTEM=leds", "SUBSYSTEM=thermal")) == null, "wrong subsystem rejected");
        check(RootFgEvent.parse(event("13099").replace("ACTION=change", "ACTION=add")) == null, "non-change ignored");
        check(RootFgEvent.parse(event("13099") + "DEVPATH=/other\0") == null, "duplicate DEVPATH rejected");
        check(RootFgEvent.parse(event("13099") + "FAN_RPM=14000\0") == null, "duplicate RPM rejected");
        check(RootFgEvent.parse(event("13099") + "incomplete=tail") == null, "truncated payload rejected");
        check(RootFgEvent.parse(event("1234567")) == null, "oversized RPM rejected before latch");
        check(RootFgEvent.parse(event("0")) != null, "real zero reaches existing zero-report invalidation");
        check(RootFgEvent.parse(event("bad")) != null, "invalid numeric event is handled by existing latch");
        check(RootFgEvent.parse(event("13099").replace("FAN_RPM=13099\0", "")) != null, "presence event without RPM invalidates instead of fabricating a reading");
        check(RootFgEvent.parse(event("13099").replace("FAN_STATE=NORMAL\0", "")) == null, "missing state rejected");
        StringBuilder oversized = new StringBuilder(event("13099"));
        while (oversized.length() <= 16384) oversized.append('x');
        check(RootFgEvent.parse(oversized.toString()) == null, "byte budget enforced");
        StringBuilder tooMany = new StringBuilder(event("13099"));
        for (int i = 0; i < 129; i++) tooMany.append('K').append(i).append("=v\0");
        check(RootFgEvent.parse(tooMany.toString()) == null, "field count bounded");
        FanRpm latch = new FanRpm(); latch.observer(true);
        long wall = 1710000000000L, elapsed = 10000;
        latch.snapshot(wall, elapsed, 1L, 10L, -1, "NORMAL");
        check(latch.accept(e.path, e.state, e.rpm, wall + 3000, elapsed + 3000, 1L, 10L, -1, "NORMAL"), "parsed kernel event reaches existing latch");
        check(latch.snapshot(wall + 3100, elapsed + 3100, 1L, 10L, -1, "NORMAL").value == 13099, "actual FG number displayed unchanged");
        check(latch.accept(e.path, e.state, "14000", wall + 6000, elapsed + 6000, 1L, 10L, -1, "NORMAL"), "later same-level event consumed");
        check(latch.snapshot(wall + 6100, elapsed + 6100, 1L, 10L, -1, "NORMAL").value == 13099, "first valid measurement still held");
        check(Double.isNaN(latch.snapshot(wall + 7000, elapsed + 7000, 1L, 9L, -1, "NORMAL").value), "changed effective level clears hold");
        check(Double.isNaN(latch.snapshot(wall + 8000, elapsed + 8000, 0L, 0L, -1, "NORMAL").value), "stop never invents measured zero");
        System.out.println("PASS RootFgEventTest: " + checks + " passive parser/latch regressions");
    }
}
