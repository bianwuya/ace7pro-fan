package dev.eta.acefan;

import java.util.List;

/** Synthetic checks for the exportable device report. No device or OEM sample needed. */
public final class DiagnosticReportTest {
    static int checks;
    static void ok(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    static void has(String text, String needle) {
        checks++;
        if (!text.contains(needle)) throw new AssertionError("missing [" + needle + "] in:\n" + text);
    }
    static void lacks(String text, String needle) {
        checks++;
        if (text.contains(needle)) throw new AssertionError("unexpected [" + needle + "] in:\n" + text);
    }

    public static void main(String[] args) {
        // 1. Unsupported foreign device: the report must name the gate and ask for the APK.
        DiagnosticReport.Input in = new DiagnosticReport.Input();
        in.generatedAt = "2026-11-05 12:00:00";
        in.appVersion = "0.3.1-report";
        in.hookVersion = "0.3.0-compat";
        in.model = "PKX110"; in.device = "OP5D2BL1"; in.brand = "OnePlus / OPPO";
        in.release = "16"; in.sdk = "36"; in.display = "PKX110_16.0.1.300(CN01)";
        in.fingerprint = "OnePlus/PKX110/OP5D2BL1:16/AAAA/1:user/release-keys";
        in.hardwareGate = "不通过：PKX110 / OP5D2BL1（本模块仅覆盖 ACE7pro PMS110 / OP64F1L1）";
        in.hookState = "已注入";
        in.frameworkApi = "93";
        in.targetPackage = "com.oplus.exsystemservice";
        in.targetVersion = "1.4.6";
        in.targetPath = "/system_ext/priv-app/OplusExSystemService/OplusExSystemService.apk";
        in.targetSha256 = "abcdef0123456789";
        in.knownApk = "不一致";
        in.status = "unsupported";
        in.stage = "关键保护链代码指纹";
        in.detail = "NoSuchMethodException: 关键保护链代码变化：downstream（不一致）；需要新固件样本适配";
        in.trace = "→ 读取编排器实例\n指纹不通过的成员：[downstream（不一致）]";
        in.enabled = "false"; in.active = "false"; in.phase = "oem";
        in.reason = "系统风扇未适配，保留原厂";
        String text = DiagnosticReport.build(in);
        has(text, DiagnosticReport.FORMAT);
        has(text, "PKX110 / OP5D2BL1");
        has(text, "硬件门槛：不通过");
        has(text, "接口不支持 · 仅观测");
        has(text, "失败阶段：关键保护链代码指纹");
        has(text, "abcdef0123456789");
        has(text, "不一致（同接口代码仍可适配）");
        has(text, "11 处关键方法".substring(0, 2)); // trace echoed
        has(text, "ACE7pro");
        has(text, "Xposed Bridge API：93（模块最低 82）");
        has(text, "没有网络权限");
        // Only ACE7pro is targeted; do not promise support for this model.
        has(text, "需要按它的硬件与原厂风扇接口单独适配");
        // The report must not claim another device is supported.
        lacks(text, "兼容：PKX110");
        // No personal-data values are ever rendered: only hardware, version and fan interface facts.
        for (String banned : new String[]{"IMEI", "手机号", "msisdn", "latitude", "longitude"}) lacks(text, banned);
        // The disclaimer names the excluded categories; that is text about them, not their content.
        has(text, "不含账号、位置、通讯录或网络数据");
        ok(text.endsWith("\n"));
        ok(text.indexOf("[1] 设备") < text.indexOf("[2] 框架与注入"));
        ok(text.indexOf("[4] 接口适配") < text.indexOf("[适配建议]"));

        // 2. Matched interface but not yet validated: ask for a real charging session.
        DiagnosticReport.Input matched = new DiagnosticReport.Input();
        matched.model = "PMS110"; matched.device = "OP64F1L1";
        matched.hardwareGate = "通过：PMS110 / OP64F1L1";
        matched.hookState = "已注入"; matched.frameworkApi = "93";
        matched.status = "interface-matched"; matched.profile = "oplus-fan-v3-code1";
        matched.detail = "系统版本不限；接口与 11 处关键保护链代码匹配，尚待实机验证";
        matched.knownApk = "一致";
        String m = DiagnosticReport.build(matched);
        has(m, "接口匹配 · 待实测");
        has(m, "与已知参考固件一致");
        has(m, "还没在这台设备上做过实机验收");
        lacks(m, "需要按它的硬件与原厂风扇接口单独适配");

        // 3. Not injected at all: give the enablement checklist instead of an APK request.
        DiagnosticReport.Input none = new DiagnosticReport.Input();
        none.model = "PMS110"; none.device = "OP64F1L1";
        none.hookState = "";
        String n = DiagnosticReport.build(none);
        has(n, "注入状态：未知（无回报）");
        has(n, "Xposed Bridge API：未知（旧版 Hook 未回报）");
        has(n, "先在 LSPosed 启用本模块");
        has(n, "作用域只勾 com.oplus.exsystemservice");
        has(n, "硬件门槛：未知（未读取）");

        // 4. Null input must still render a usable skeleton, not crash.
        String empty = DiagnosticReport.build(null);
        has(empty, "[1] 设备");
        has(empty, "[适配建议]");

        // 5. Runtime error path keeps reporting the original fallback.
        DiagnosticReport.Input fused = new DiagnosticReport.Input();
        fused.model = "PMS110"; fused.device = "OP64F1L1"; fused.hookState = "已注入";
        fused.frameworkApi = "93"; fused.status = "runtime-error";
        fused.detail = "NullPointerException while reading battery";
        has(DiagnosticReport.build(fused), "运行异常 · 已交还原厂");
        has(DiagnosticReport.build(fused), "已交还原厂");

        // 6. Stale hook: must warn instead of claiming there is no blocker.
        DiagnosticReport.Input stale = new DiagnosticReport.Input();
        stale.model = "PMS110"; stale.device = "OP64F1L1";
        stale.appVersion = "0.3.1-report"; stale.hookVersion = "0.2.1-eco";
        stale.hookState = "已注入"; stale.hookStale = true;
        String st = DiagnosticReport.build(stale);
        has(st, "运行中的 Hook 不是本 App 版本");
        has(st, "旧版本");
        has(st, "不是接口通过");
        lacks(st, "当前状态没有明显阻塞项");
        // Injected + fresh but no status field must also not be read as "no blocker".
        DiagnosticReport.Input blank = new DiagnosticReport.Input();
        blank.model = "PMS110"; blank.device = "OP64F1L1";
        blank.hookState = "已注入"; blank.frameworkApi = "93";
        String bl = DiagnosticReport.build(blank);
        has(bl, "上报缺失而不是通过");
        lacks(bl, "当前状态没有明显阻塞项");

        // 7. Resolution stage tracking is observable and cleared per attempt.
        FanServiceCompat.resetTrace();
        ok(FanServiceCompat.lastTrace().isEmpty());
        ok("".equals(FanServiceCompat.stageOf(new RuntimeException("plain"))));
        Exception wrapped = new Exception(new RuntimeException("outer"));
        ok("".equals(FanServiceCompat.stageOf(wrapped)));
        List<String> snapshot = FanServiceCompat.lastTrace();
        snapshot.add("mutated by caller");
        ok(!FanServiceCompat.lastTrace().contains("mutated by caller"));

        System.out.println("DiagnosticReport: " + checks + " synthetic checks PASS"
                + " (foreign device / matched / not-injected / null / runtime-error / trace isolation)");
    }
}
