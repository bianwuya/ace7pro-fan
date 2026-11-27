package dev.eta.acefan;

/** Exact OEM input decoding regressions; no Android or hardware writes. */
public final class KernelGateInputsTest {
    private static int checks;
    private static void eq(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("gate case " + checks);
    }
    public static void main(String[] args) {
        eq(KernelProtocol.pocketGate(null) == -1);
        eq(KernelProtocol.pocketGate(new float[0]) == -1);
        eq(KernelProtocol.pocketGate(new float[]{Float.NaN}) == -1);
        eq(KernelProtocol.pocketGate(new float[]{Float.POSITIVE_INFINITY}) == -1);
        eq(KernelProtocol.pocketGate(new float[]{Float.NEGATIVE_INFINITY}) == -1);
        eq(KernelProtocol.pocketGate(new float[]{0f}) == 0);
        eq(KernelProtocol.pocketGate(new float[]{-0f}) == 0);
        eq(KernelProtocol.pocketGate(new float[]{1f}) == 1);
        eq(KernelProtocol.pocketGate(new float[]{5f}) == 1);
        eq(KernelProtocol.pocketGate(new float[]{-1f}) == 1);
        eq(!KernelProtocol.performanceClear(null));
        eq(!KernelProtocol.performanceClear("true"));
        eq(!KernelProtocol.performanceClear("true#com.antutu.ABenchMark"));
        eq(KernelProtocol.performanceClear("false"));
        eq(KernelProtocol.performanceClear("false#com.antutu.ABenchMark"));
        eq(!KernelProtocol.falseText("false#com.antutu.ABenchMark"));
        eq(KernelProtocol.mainEnabled("3") == 2 && KernelProtocol.mode("0") == 1);
        eq(KernelProtocol.yesNo("0") == 1 && KernelProtocol.yesNo("1") == 2);
        System.out.println("PASS KernelGateInputsTest: " + checks + " exact OEM gate assertions");
    }
}
