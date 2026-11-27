package dev.eta.acefan;

public final class KernelHandoffTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static String line() {
        return "NONRELEASE protocol=2 configProtocol=2 configRevision=74 strategy=2 configured=0 live=1 blocked=0 fault=0 "
            + "owned=0 ready=0 leaseUntil=0 nowMs=1000 ownerEpoch=36 target=-1 output=0 "
            + "requestedLevel=20 physicalState=0 effective=0 readbackMask=7 tempValidMask=0 temp0=36700 temp1=40395 "
            + "temp2=43828 temp3=44528 sampleAtMs=900 power=1 powerEpoch=1 sourcePresentMask=1 sourceOnlineMask=0 "
            + "sourceFailedMask=0 batteryStatus=2 batteryPresent=1 reason=0";
    }
    public static void main(String[] args) throws Exception {
        check(KernelHandoff.released(KernelReport.parse(line()), 74), "exact disabled release accepted");
        check(!KernelHandoff.released(KernelReport.parse(line()), 73), "old revision rejected");
        check(!KernelHandoff.released(KernelReport.parse(line().replace("owned=0", "owned=1")), 74), "owned rejects refresh");
        check(!KernelHandoff.released(KernelReport.parse(line().replace("configured=0", "configured=1")), 74), "enabled rejects refresh");
        check(KernelHandoff.released(KernelReport.parse(line().replace("fault=0", "fault=-16")), 74), "release token does not clear hardware protections");
        for (String mode : new String[]{"1", "2"}) {
            check(KernelHandoff.fresh(1000, 1000, mode, "100", mode, "100"), "both stock manual modes");
            check(KernelHandoff.fresh(1000, 5999, mode, "100", mode, "100"), "fresh endpoint");
            check(!KernelHandoff.fresh(1000, 6000, mode, "100", mode, "100"), "expired rejects");
            check(!KernelHandoff.fresh(1000, 999, mode, "100", mode, "100"), "future rejects");
            check(!KernelHandoff.fresh(1000, 1100, mode, "100", "0", "100"), "return auto cancels");
            check(!KernelHandoff.fresh(1000, 1100, mode, "100", mode, "0"), "changed manual level cancels");
            check(!KernelHandoff.fresh(1000, 1100, mode, null, mode, null), "unknown level rejects");
        }
        for (String mode : new String[]{null, "0", "3", ""})
            check(!KernelHandoff.fresh(1000, 1100, mode, "100", mode, "100"), "non-manual rejects");
        KernelHandoff.Retry retry = new KernelHandoff.Retry();
        check(retry.begin(74, 1, 1000), "initial release attempt");
        check(!retry.begin(74, 1, 2499), "backoff avoids busy retry");
        check(retry.begin(74, 1, 2500), "unconsumed release retries fresh proof");
        check(retry.begin(74, 1, 4000), "third bounded retry");
        check(retry.exhausted() && !retry.begin(74, 1, 6000), "no perpetual retry");
        check(retry.begin(74, 2, 6000), "changed customize event allows new proof same revision");
        retry.complete();
        check(!retry.begin(74, 2, 9000), "successful consumption stops refreshes");
        check(retry.begin(75, 2, 9000), "new committed revision allowed");
        CompatibilityTest.Engine engine = new CompatibilityTest.Engine();
        FanServiceCompat.Bootstrap boot = FanServiceCompat.bootstrap(CompatibilityTest.Engine.class,
                CompatibilityTest.Ctx.class, CompatibilityTest.Hdl.class);
        FanServiceCompat c = CompatibilityTest.resolve(boot, engine, CompatibilityTest.Controller.class, CompatibilityTest.Config.class);
        c.updated.setBoolean(c.controller, true);
        Integer before = c.level(c.controller);
        c.invalidateConfirmation();
        check(Boolean.FALSE.equals(c.confirmed(c.controller)), "same-level cached success invalidated");
        check(before.equals(c.level(c.controller)), "does not set a target or alter saved OEM level");
        check(c.event() == 2, "does not clear health/event protection");
        System.out.println("Kernel handoff: " + checks + " assertions PASS (host contract; not device proof)");
    }
}
