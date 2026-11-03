package dev.eta.acefan;

import java.util.List;

/** Synthetic report checks: no device, Android runtime or OEM sample is needed. */
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
    static String section(String text, String heading, String nextHeading) {
        int first = text.indexOf(heading);
        int end = text.indexOf(nextHeading, first + heading.length());
        ok(first >= 0 && end > first);
        return text.substring(first, end);
    }
    static DiagnosticReport.Input injected(String status) {
        DiagnosticReport.Input in = new DiagnosticReport.Input();
        in.generatedAt = "2026-11-05 12:00:00";
        in.appVersion = "current-app";
        in.hookVersion = "current-hook";
        in.model = "PMS110";
        in.device = "OP64F1L1";
        in.hardwareGate = "通过：PMS110 / OP64F1L1（ACE 7 系列）";
        in.hookState = "已注入";
        in.frameworkApi = "93";
        in.targetPackage = "com.oplus.exsystemservice";
        in.knownApk = "不参与检测";
        in.status = status;
        return in;
    }
    static void reportFirst(String advice) {
        has(advice, "复制、分享或保存");
        has(advice, "完整报告");
        has(advice, "发给开发者");
        has(advice, "仅当完整报告不足以定位时");
        has(advice, "不要求先提供 APK");
        lacks(advice, "需要这份新 APK");
        lacks(advice, "原厂服务 APK 本身");
        lacks(advice, "关键保护链代码匹配");
    }

    public static void main(String[] args) {
        // Ready means installed, not yet applied; blank/zero evidence isn't a failure.
        DiagnosticReport.Input ready = injected("ready");
        ready.stage = "Hook安装";
        ready.detail = "实际接口已接入，等待允许场景中的原厂调用";
        ready.runtimeProgress = "接口已安装，等待真实场景";
        ready.runtimeCounters = "候选应用=0；同轮控制完成=0；原厂状态确认=0";
        ready.runtimeEvidence = "接口已安装，等待真实场景；候选应用=0\n"
                + "Hook安装：成功；调用=1；失败=0；elapsed=123ms；成员；等待真实调用\n"
                + "控制反馈：未触发；调用=0；失败=0；elapsed=0ms；；\n";
        ready.oem = "5"; ready.candidate = "12"; ready.request = "10";
        ready.driver = "9"; ready.effective = "8";
        ready.extra = "合成补充信息";
        String text = DiagnosticReport.build(ready);
        has(text, "ACE 7 系列风扇助手 · 设备兼容报告");
        has(text, "格式 acefan-report-2");
        has(text, "状态：接口已接入 · 查看运行证据");
        String setup = section(text, "[4] 接口接入与当前诊断", "[5] 运行证据与历史失败");
        has(setup, "阶段：Hook安装");
        has(setup, "详情：实际接口已接入，等待允许场景中的原厂调用");
        lacks(setup, "失败阶段");
        lacks(setup, "失败原因");
        has(text, "运行进度：接口已安装，等待真实场景");
        has(text, "运行计数：候选应用=0；同轮控制完成=0；原厂状态确认=0");
        has(text, ready.runtimeEvidence); // Exact multi-line evidence survives, including trailing newline.
        has(text, "最近失败阶段：未上报 / 未观察到");
        has(text, "最近失败详情：未上报 / 未观察到");
        has(text, "未上报或未触发不代表失败");
        has(text, "控制请求调用完成或原厂状态确认不等于实测物理转速");
        has(text, "档位（原厂 / 候选 / 请求 / 驱动 / 有效）：5 / 12 / 10 / 9 / 8");
        has(text, "[7] 补充\n合成补充信息");
        has(text, "Xposed Bridge API：93（模块最低 82）");
        has(text, "App 本身也没有网络权限");
        has(text, "不含账号、位置、通讯录或网络数据");
        ok(text.endsWith("\n"));
        ok(text.indexOf("[1] 设备") < text.indexOf("[2] 框架与注入"));
        ok(text.indexOf("[5] 运行证据与历史失败") < text.indexOf("[6] 当前策略与遥测"));
        String readyAdvice = DiagnosticReport.advice(ready);
        has(readyAdvice, "尚未触发的环节不是失败");
        lacks(readyAdvice, "运行环节有失败记录");
        lacks(readyAdvice, "请你提供原厂服务 APK");
        lacks(readyAdvice, "只接管 ACE7pro");
        lacks(readyAdvice, "接口不支持");
        for (String banned : new String[]{"IMEI", "手机号", "msisdn", "latitude", "longitude"}) lacks(text, banned);

        // Position cannot shift when an individual level is missing.
        ready.oem = ""; ready.candidate = "12"; ready.request = "";
        has(DiagnosticReport.build(ready),
                "档位（原厂 / 候选 / 请求 / 驱动 / 有效）：未知 / 12 / 未知 / 9 / 8");

        // Not yet triggered or absent/legacy evidence is unknown, not an error or success.
        DiagnosticReport.Input pending = injected("pending");
        pending.detail = "等待原厂风扇初始化";
        String waiting = DiagnosticReport.build(pending);
        has(waiting, "状态：等待原厂风扇初始化");
        has(waiting, "逐环节证据（上报原文，上游可能截断）：\n未上报（不代表成功或失败）");
        has(waiting, "未触发不等于失败");
        lacks(DiagnosticReport.advice(pending), "运行环节有失败记录");
        DiagnosticReport.Input missing = injected("");
        has(DiagnosticReport.advice(missing), "上报缺失而不是通过或失败");
        lacks(DiagnosticReport.advice(missing), "运行环节有失败记录");
        missing.lastFailureStage = "null"; missing.lastFailureDetail = null;
        lacks(DiagnosticReport.advice(missing), "运行环节有失败记录");
        has(DiagnosticReport.build(missing), "最近失败阶段：未上报 / 未观察到");

        // Failed setup asks for the full report first, not mandatory fingerprint/APK matching.
        DiagnosticReport.Input failed = injected("setup-failed");
        failed.stage = "控制器与档位上限方法";
        failed.detail = "NoSuchMethodException: OEM 控制方法";
        failed.targetPath = "/system_ext/priv-app/OplusExSystemService/OplusExSystemService.apk";
        failed.targetSha256 = "0123456789abcdef";
        failed.lastFailureStage = failed.stage;
        failed.lastFailureDetail = "控制器 | NoSuchMethodException";
        String setupFailed = DiagnosticReport.build(failed);
        has(setupFailed, "状态：接口接入失败");
        has(setupFailed, "阶段：控制器与档位上限方法");
        has(setupFailed, "详情：NoSuchMethodException: OEM 控制方法");
        has(setupFailed, "最近失败详情：控制器 | NoSuchMethodException");
        has(setupFailed, "0123456789abcdef");
        reportFirst(DiagnosticReport.advice(failed));

        // Recovered stage still has the preceding failure; the evidence and last failure must coexist.
        DiagnosticReport.Input recovered = injected("confirmed");
        recovered.stage = "控制反馈读取";
        recovered.detail = "本次控制反馈成功";
        recovered.runtimeProgress = "原厂状态确认";
        recovered.runtimeCounters = "候选应用=2；同轮控制完成=2；原厂状态确认=1";
        recovered.runtimeEvidence = "原厂状态确认；候选应用=2\n"
                + "控制反馈读取：成功；调用=2；失败=1；elapsed=222ms；控制器；状态确认=true\n";
        recovered.lastFailureStage = "控制反馈读取";
        recovered.lastFailureDetail = "控制器#method | IllegalStateException: historical error";
        String after = DiagnosticReport.build(recovered);
        has(after, "状态：同轮原厂状态确认 · 非实测转速");
        has(after, "详情：本次控制反馈成功");
        has(after, recovered.runtimeEvidence);
        has(after, "最近失败阶段：控制反馈读取");
        has(after, "最近失败详情：控制器#method | IllegalStateException: historical error");
        has(after, "后续恢复成功后仍保留");
        has(after, "是否仍异常需结合最新证据");
        reportFirst(DiagnosticReport.advice(recovered));

        // Partial runtime failure with setup ready is not global incompatibility.
        DiagnosticReport.Input partial = injected("ready");
        partial.runtimeEvidence = "候选读取：成功；调用=3；失败=0\n"
                + "场景读取：失败；调用=1；失败=1\n";
        String partialText = DiagnosticReport.build(partial);
        has(partialText, "状态：接口已接入 · 查看运行证据");
        has(partialText, partial.runtimeEvidence);
        lacks(partialText, "接口不支持 · 仅观测");
        reportFirst(DiagnosticReport.advice(partial));
        // Historical failure count alone is still report-worthy after stage recovery.
        partial.runtimeEvidence = "场景读取：成功；调用=2；失败=12；elapsed=42ms";
        reportFirst(DiagnosticReport.advice(partial));
        // An explicit truncation marker and the whole supplied value remain visible.
        partial.runtimeEvidence = "第一行\n第二行\n…（已截断）";
        has(DiagnosticReport.build(partial), partial.runtimeEvidence);
        lacks(DiagnosticReport.advice(partial), "运行环节有失败记录");

        // Actual progress labels describe only their respective observations.
        ok(DiagnosticReport.statusLabel("attempted").contains("候选已应用"));
        ok(DiagnosticReport.statusLabel("request-observed").contains("未确认"));
        ok(DiagnosticReport.statusLabel("confirmed").contains("非实测转速"));
        ok(DiagnosticReport.statusLabel("partial-error").contains("部分异常"));

        // Older hook statuses and stale fields remain legible but are not promoted to current proof.
        DiagnosticReport.Input stale = injected("interface-matched");
        stale.appVersion = "0.4-new"; stale.hookVersion = "0.3-old"; stale.hookStale = true;
        stale.profile = "old-profile";
        stale.detail = "旧版匹配记录";
        String old = DiagnosticReport.build(stale);
        has(old, "与 App（0.4-new）不是同一版本");
        has(old, "状态：接口匹配 · 待实测");
        has(old, "阶段：不适用（旧版未提供阶段）");
        has(old, "不是接口通过，也不是失败");
        has(old, "旧版 Hook 的兼容状态");
        has(old, "最近失败阶段：未上报 / 未观察到");
        lacks(DiagnosticReport.advice(stale), "当前状态没有明显阻塞项");
        stale.status = "known-firmware"; stale.trace = "旧版解析轨迹"; stale.knownApk = "一致";
        String oldFull = DiagnosticReport.build(stale);
        has(oldFull, "状态：已识别参考固件");
        has(oldFull, "与历史参考固件一致（仅供参考）");
        has(oldFull, "仍建议以重启后的报告为准");
        lacks(DiagnosticReport.advice(stale), "可能尚未上报");
        stale.status = "unsupported"; stale.knownApk = "不一致";
        has(DiagnosticReport.build(stale), "状态：接口不支持 · 仅观测");
        has(DiagnosticReport.build(stale), "不一致（历史参考信息，不是本版接入门槛）");
        reportFirst(DiagnosticReport.advice(stale));
        stale.status = "runtime-error";
        has(DiagnosticReport.build(stale), "状态：运行异常 · 已交还原厂");
        reportFirst(DiagnosticReport.advice(stale));
        stale.status = "setup-failed"; stale.knownApk = "未校验";
        has(DiagnosticReport.build(stale), "参考固件哈希：未校验");

        // A stale observation must not be presented as a live injection.
        DiagnosticReport.Input history = injected("ready");
        history.hookState = "历史注入回报（已过期）";
        String historical = DiagnosticReport.build(history);
        has(historical, "注入状态：历史注入回报（已过期）（不能证明当前注入）");
        has(DiagnosticReport.advice(history), "没有有效的注入回报");
        DiagnosticReport.Input preview = injected("pending");
        preview.hookState = "Root只读预览";
        has(DiagnosticReport.build(preview), "未注入 · 仅本机只读观测");
        has(DiagnosticReport.advice(preview), "先在 LSPosed 启用本模块");

        // Family wording must not restrict all hardware to Pro or promise untested hardware.
        DiagnosticReport.Input outside = injected("outside-family");
        outside.model = "foreign"; outside.device = "foreign-device";
        outside.hardwareGate = "不通过：foreign / foreign-device";
        String foreign = DiagnosticReport.advice(outside);
        has(foreign, "ACE 7 系列");
        has(foreign, "上报的硬件识别未通过");
        lacks(foreign, "只接管 ACE7pro");
        lacks(foreign, "仅覆盖 ACE7pro");
        lacks(foreign, "兼容：foreign");

        DiagnosticReport.Input none = new DiagnosticReport.Input();
        String notInjected = DiagnosticReport.build(none);
        has(notInjected, "注入状态：未知（无回报）");
        has(notInjected, "Xposed Bridge API：未知（旧版 Hook 未回报）");
        has(notInjected, "作用域只勾 com.oplus.exsystemservice");
        has(notInjected, "硬件门槛：未知（未读取）");
        has(DiagnosticReport.build(null), "[适配建议]");

        // Resolution trace snapshots are isolated from mutations by report callers.
        FanServiceCompat.resetTrace();
        ok(FanServiceCompat.lastTrace().isEmpty());
        ok("".equals(FanServiceCompat.stageOf(new RuntimeException("plain"))));
        ok("".equals(FanServiceCompat.stageOf(new Exception(new RuntimeException("outer")))));
        List<String> snapshot = FanServiceCompat.lastTrace();
        snapshot.add("mutated by caller");
        ok(!FanServiceCompat.lastTrace().contains("mutated by caller"));

        System.out.println("DiagnosticReport: " + checks + " synthetic checks PASS"
                + " (ready / not-triggered / setup failure / recovered / partial / legacy / stale)");
    }
}
