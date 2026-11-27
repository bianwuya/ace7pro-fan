package dev.eta.acefan;

import java.util.Map;

public final class KernelHardwareStatusTest {
    private static int count;
    private static void check(boolean ok, String message) {
        count++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String line = "NONRELEASE protocol=2 configProtocol=2 configRevision=74 strategy=2 configured=0 live=1 blocked=0 fault=0 "
                + "owned=0 ready=0 leaseUntil=950 configRevisionUnused=0 nowMs=1000 ownerEpoch=36 target=-1 output=0 "
                + "requestedLevel=6 physicalState=0 effective=0 readbackMask=7 tempValidMask=0 temp0=36700 temp1=40395 "
                + "temp2=43828 temp3=44528 sampleAtMs=900 power=1 powerEpoch=1 sourcePresentMask=1 sourceOnlineMask=0 "
                + "sourceFailedMask=0 batteryStatus=2 batteryPresent=1 reason=0";
        KernelReport report = KernelReport.parse(line);
        for (String health : new String[]{"NORMAL", "BLOCKED", "DAMAGED", "RPM_LOW", "NOT_PRESENT", "PRESENT", "OCP_ISSUE"}) {
            Map<String, Object> f = report.fields(1L, 1000L, "test", "test", 74L, null, -1L, -1L, "HOST_MOCK_ONLY", health + "\n");
            check(health.equals(f.get("fanStatus")), "actual driver status preserved: " + health);
        }
        for (String bad : new String[]{null, "", "normal", "0", "NORMAL BLOCKED", "future", "BLOCKED\nNORMAL"})
            check("UNKNOWN".equals(KernelReport.hardwareStatus(bad)), "missing/invalid hardware evidence is unknown");
        check("UNKNOWN".equals(report.fields(1L, 1000L, "test", "test", 74L, null, -1L, -1L,
                "HOST_MOCK_ONLY").get("fanStatus")), "old callers cannot infer NORMAL from live/fault");
        check("BLOCKED".equals(report.fields(1L, 1000L, "test", "test", 74L, null, -1L, -1L,
                "HOST_MOCK_ONLY", "BLOCKED").get("fanStatus")), "actual regression: policy fault0 does not hide BLOCKED");
        System.out.println("Kernel hardware health: " + count + " assertions PASS");
    }
}
