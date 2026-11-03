package dev.eta.acefan;

/**
 * Pure-Java device compatibility report. No Android, no Xposed, no I/O: every field is a
 * string handed in by the caller, so the same formatter can be unit-tested and reused.
 * The report is evidence for adaptation, never a substitute for physical verification.
 */
public final class DiagnosticReport {
    public static final String FORMAT = "acefan-report-2";
    private static final String UNKNOWN = "未知";

    /** All values are already formatted for display; empty means "not observed". */
    public static final class Input {
        public String generatedAt = "";
        public String appVersion = "", hookVersion = "";
        public String model = "", device = "", brand = "", release = "", sdk = "";
        public String display = "", fingerprint = "", incremental = "";
        public String hardwareGate = "";
        public String hookState = "";
        public String frameworkApi = "", frameworkVersion = "", manager = "";
        public String scope = "com.oplus.exsystemservice";
        public String targetPackage = "", targetVersion = "", targetPath = "", targetSha256 = "", knownApk = "";
        public String status = "", stage = "", detail = "", profile = "", trace = "";
        public String runtimeEvidence = "", runtimeProgress = "", runtimeCounters = "";
        public String lastFailureStage = "", lastFailureDetail = "";
        public String enabled = "", active = "", phase = "", reason = "";
        public String oem = "", candidate = "", request = "", driver = "", effective = "";
        public String battery = "", board = "", fan = "", mos = "";
        public String plugged = "", keyguard = "", interactive = "";
        public String extra = "";
        /** A fresh injected hook uses a different version than this App build. */
        public boolean hookStale;
    }

    private DiagnosticReport() { }

    public static String build(Input in) {
        if (in == null) in = new Input();
        StringBuilder b = new StringBuilder();
        b.append("ACE 7 系列风扇助手 · 设备兼容报告\n");
        b.append("格式 ").append(FORMAT)
                .append(" · App ").append(show(in.appVersion))
                .append(" · 运行 Hook ").append(show(in.hookVersion)).append('\n');
        if (!raw(in.generatedAt).isEmpty()) b.append("生成 ").append(raw(in.generatedAt)).append('\n');
        if (in.hookStale) {
            b.append("注意：运行中的 Hook（").append(show(in.hookVersion))
                    .append("）与 App（").append(show(in.appVersion)).append("）不是同一版本。")
                    .append("下方接口适配字段若无内容，是缺少上报而不是接口通过；")
                    .append("请正常重启让新 Hook 加载后再导出一次。\n");
        }

        b.append("\n[1] 设备\n");
        b.append("型号 / 代号：").append(orUnknown(join(raw(in.model), raw(in.device)))).append('\n');
        b.append("厂商：").append(show(in.brand)).append('\n');
        b.append("Android：").append(orUnknown(join(releaseText(in.release), apiText2(in.sdk)))).append('\n');
        b.append("系统版本：").append(show(in.display)).append('\n');
        b.append("指纹：").append(show(in.fingerprint)).append('\n');
        b.append("增量版本：").append(show(in.incremental)).append('\n');
        b.append("硬件门槛：").append(hardwareText(in.hardwareGate)).append('\n');

        b.append("\n[2] 框架与注入\n");
        b.append("注入状态：").append(hookStateText(in.hookState)).append('\n');
        b.append("Xposed Bridge API：").append(apiText(in.frameworkApi)).append('\n');
        b.append("运行框架版本：").append(show(in.frameworkVersion)).append('\n');
        b.append("Manager：").append(show(in.manager)).append('\n');
        b.append("作用域：").append(show(in.scope)).append('\n');

        b.append("\n[3] 原厂服务\n");
        b.append("包名：").append(show(in.targetPackage)).append('\n');
        b.append("版本：").append(show(in.targetVersion)).append('\n');
        b.append("APK 路径：").append(show(in.targetPath)).append('\n');
        b.append("APK SHA-256：").append(show(in.targetSha256)).append('\n');
        b.append("参考固件哈希：").append(knownApkText(in.knownApk)).append('\n');

        b.append("\n[4] 接口接入与当前诊断\n");
        b.append("状态：").append(statusLabel(in.status)).append('\n');
        b.append("阶段：").append(stageText(in.status, in.stage)).append('\n');
        b.append("详情：").append(show(in.detail)).append('\n');
        b.append("接口档案：").append(show(in.profile)).append('\n');
        b.append("状态、阶段与详情为 Hook 上报，可能保留先前记录，请结合运行证据核对。\n");
        if (!raw(in.trace).isEmpty()) b.append("解析轨迹：\n").append(raw(in.trace)).append('\n');

        b.append("\n[5] 运行证据与历史失败\n");
        b.append("运行进度：").append(reported(in.runtimeProgress)).append('\n');
        b.append("运行计数：").append(reported(in.runtimeCounters)).append('\n');
        b.append("逐环节证据（上报原文，上游可能截断）：\n");
        if (raw(in.runtimeEvidence).isEmpty()) {
            b.append("未上报（不代表成功或失败）\n");
        } else {
            // Preserve the complete value received from the provider, including any truncation marker.
            b.append(in.runtimeEvidence);
            if (!in.runtimeEvidence.endsWith("\n")) b.append('\n');
        }
        b.append("最近失败阶段：").append(reported(in.lastFailureStage)).append('\n');
        b.append("最近失败详情：").append(reported(in.lastFailureDetail)).append('\n');
        b.append("最近失败记录在后续恢复成功后仍保留，是否仍异常需结合最新证据；未上报或未触发不代表失败。\n")
                .append("控制请求调用完成或原厂状态确认不等于实测物理转速。\n");

        b.append("\n[6] 当前策略与遥测\n");
        b.append("增强开关：").append(onOff(in.enabled)).append('\n');
        b.append("正在增强：").append(onOff(in.active)).append('\n');
        b.append("阶段：").append(show(in.phase)).append('\n');
        b.append("决策原因：").append(show(in.reason)).append('\n');
        b.append("档位（原厂 / 候选 / 请求 / 驱动 / 有效）：")
                .append(show(in.oem)).append(" / ").append(show(in.candidate)).append(" / ")
                .append(show(in.request)).append(" / ").append(show(in.driver)).append(" / ")
                .append(show(in.effective)).append('\n');
        b.append("温度°C（电池 / 主板 / 风扇 / MOS）：")
                .append(orUnknown(join(in.battery, in.board, in.fan, in.mos))).append('\n');
        b.append("供电 / 锁屏 / 亮屏：")
                .append(orUnknown(join(in.plugged, in.keyguard, in.interactive))).append('\n');

        if (!raw(in.extra).isEmpty()) b.append("\n[7] 补充\n").append(raw(in.extra)).append('\n');

        b.append("\n[适配建议]\n").append(advice(in));
        b.append("\n说明：本报告只含公开硬件、版本与风扇接口信息；不含账号、位置、通讯录或网络数据，")
                .append("App 本身也没有网络权限，导出内容由你自行决定发给谁。\n");
        return b.toString();
    }

    /** Concrete next evidence; missing observations are not themselves failures. */
    public static String advice(Input in) {
        if (in == null) in = new Input();
        String status = raw(in.status), state = raw(in.hookState);
        boolean injected = "已注入".equals(state);
        StringBuilder b = new StringBuilder();
        boolean any = false;
        if (in.hookStale) {
            boolean missing = status.isEmpty() || status.equals("pending") || raw(in.trace).isEmpty();
            b.append("- 运行中的 Hook 是旧版本（").append(show(in.hookVersion)).append("），本报告来自旧版回报。")
                    .append(missing
                            ? "接口诊断或解析轨迹可能尚未上报，"
                            : "接口诊断字段虽有内容，仍建议以重启后的报告为准，")
                    .append("请正常重启让本版 Hook 加载后再导出一次。\n");
            if (missing) {
                b.append("- 未上报的字段不是接口通过，也不是失败；重启前请勿据此判断兼容性。\n");
            }
            any = true;
        }
        if (isHardwareFail(in.hardwareGate) || "outside-family".equals(status)) {
            b.append("- 本模块面向 ACE 7 系列；上报的硬件识别未通过。旧版 Hook 可能仍使用旧范围，")
                    .append("不能仅凭旧记录判断本版是否能接入。如需排查，请通过复制、分享或保存导出完整报告发给开发者。\n");
            any = true;
        }
        if (!injected) {
            b.append("- 没有有效的注入回报：先在 LSPosed 启用本模块，作用域只勾 ").append(show(in.scope))
                    .append("，然后正常重启（覆盖安装不会让已运行的原厂进程重载）。\n");
            b.append("- 若已启用仍无回报，可通过复制、分享或保存导出完整报告发给开发者，")
                    .append("并确认框架支持当前 Android 版本且仍兼容传统 Xposed 模块。\n");
            any = true;
        }
        boolean partialFailure = hasRuntimeFailure(in);
        if ("setup-failed".equals(status) || "unsupported".equals(status)
                || "runtime-error".equals(status) || "partial-error".equals(status) || partialFailure) {
            b.append("- 接口接入或运行环节有失败记录：请通过现有的复制、分享或保存功能导出完整报告，")
                    .append("连同具体发生时间和现象发给开发者；最近失败可能是已恢复的历史记录。\n")
                    .append("- 仅当完整报告不足以定位时，开发者才可能请你提供原厂服务 APK；")
                    .append("不要求先提供 APK，也不应绕过原厂保护。\n");
            any = true;
        }
        if ("pending".equals(status) && injected) {
            b.append("- 框架已注入，但尚未观察到原厂风扇初始化；未触发不等于失败。")
                    .append("可检查原厂风扇开关，在正常使用相关功能后重新导出报告。\n");
            any = true;
        } else if ("ready".equals(status) || "attempted".equals(status)
                || "request-observed".equals(status) || "confirmed".equals(status)) {
            b.append("- 接入与运行证据分别查看；尚未触发的环节不是失败，也不证明实际效果。")
                    .append("在原有允许场景中验证，控制请求或原厂状态确认都不是物理转速实测。\n");
            any = true;
        } else if ("interface-matched".equals(status) || "known-firmware".equals(status)) {
            b.append("- 这是旧版 Hook 的兼容状态，不代表新版接口或物理效果已验收；")
                    .append("请参考当前运行证据并在允许场景中验证。\n");
            any = true;
        }
        if (status.isEmpty() && injected && !in.hookStale) {
            b.append("- 已注入但没有接口诊断状态：这是上报缺失而不是通过或失败，请重新导出或重启后复核。\n");
            any = true;
        }
        if (!any) {
            b.append("- 请结合逐环节运行证据判断；缺失或尚未触发的记录不能视为成功或失败。\n");
        }
        return b.toString();
    }

    /** Labels for the live path; fall back to catalog labels for all legacy statuses. */
    public static String statusLabel(String status) {
        String s = raw(status);
        if ("ready".equals(s)) return "接口已接入 · 查看运行证据";
        if ("attempted".equals(s)) return "候选已应用 · 等待控制反馈";
        if ("request-observed".equals(s)) return "同轮控制调用完成 · 原厂状态未确认";
        if ("confirmed".equals(s)) return "同轮原厂状态确认 · 非实测转速";
        if ("setup-failed".equals(s)) return "接口接入失败";
        if ("outside-family".equals(s)) return "设备未识别为 ACE 7 系列";
        if ("partial-error".equals(s)) return "运行环节部分异常";
        // Preserve historical labels independently of future catalog changes.
        if ("known-firmware".equals(s)) return "已识别参考固件";
        if ("interface-matched".equals(s)) return "接口匹配 · 待实测";
        if ("pending".equals(s)) return "等待原厂风扇初始化";
        if ("unsupported".equals(s)) return "接口不支持 · 仅观测";
        if ("runtime-error".equals(s)) return "运行异常 · 已交还原厂";
        return CompatibilityCatalog.statusLabel(s);
    }

    private static boolean hasRuntimeFailure(Input in) {
        if (!raw(in.lastFailureStage).isEmpty() || !raw(in.lastFailureDetail).isEmpty()) return true;
        // Some older/partial records have only per-stage evidence, with no lastFailure fields.
        // "失败=0" is a counter, not a failure; do not match it as one.
        String evidence = raw(in.runtimeEvidence);
        return evidence.contains("：失败") || evidence.contains(":失败")
                || evidence.matches("(?s).*失败=[1-9][0-9]*.*");
    }

    private static boolean isHardwareFail(String gate) {
        String g = raw(gate);
        return g.contains("不通过") || g.contains("不支持");
    }

    private static String hardwareText(String gate) {
        String g = raw(gate);
        return g.isEmpty() ? "未知（未读取）" : g;
    }

    /** Old passed statuses had no stage; new stage values can also describe non-failures. */
    private static String stageText(String status, String stage) {
        String s = raw(stage);
        if (!s.isEmpty()) return s;
        String st = raw(status);
        if ("known-firmware".equals(st) || "interface-matched".equals(st)) return "不适用（旧版未提供阶段）";
        return UNKNOWN;
    }

    private static String hookStateText(String state) {
        String s = raw(state);
        if (s.isEmpty()) return "未知（无回报）";
        if ("已注入".equals(s)) return "已注入目标进程";
        if ("只读预览".equals(s) || "Root只读预览".equals(s)) return "未注入 · 仅本机只读观测";
        if (s.contains("历史") || s.contains("过期")) return s + "（不能证明当前注入）";
        return s;
    }

    private static String apiText(String api) {
        String s = raw(api);
        if (s.isEmpty() || "null".equals(s)) return "未知（旧版 Hook 未回报）";
        return s + "（模块最低 " + CompatibilityCatalog.MIN_XPOSED_API + "）";
    }

    private static String knownApkText(String known) {
        String s = raw(known);
        if (s.isEmpty()) return UNKNOWN;
        if ("true".equals(s) || "一致".equals(s)) return "与历史参考固件一致（仅供参考）";
        if ("false".equals(s) || "不一致".equals(s)) return "不一致（历史参考信息，不是本版接入门槛）";
        return s;
    }

    private static String onOff(String v) {
        String s = raw(v);
        if ("true".equals(s) || "是".equals(s)) return "是";
        if ("false".equals(s) || "否".equals(s)) return "否";
        return s.isEmpty() ? UNKNOWN : s;
    }

    private static String releaseText(String release) {
        String s = raw(release);
        return s.isEmpty() ? "" : "Android " + s;
    }

    private static String apiText2(String sdk) {
        String s = raw(sdk);
        return s.isEmpty() ? "" : "API " + s;
    }

    /** Joins the present parts with " / "; absent parts are skipped rather than faked. */
    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        if (parts == null) return "";
        for (String p : parts) {
            String v = raw(p);
            if (v.isEmpty()) continue;
            if (b.length() > 0) b.append(" / ");
            b.append(v);
        }
        return b.toString();
    }

    private static String orUnknown(String v) {
        String s = raw(v);
        return s.isEmpty() ? UNKNOWN : s;
    }

    /** Raw trimmed value. Empty stays empty so callers can distinguish "absent" from "unknown". */
    private static String raw(String s) {
        if (s == null) return "";
        String v = s.trim();
        return "null".equals(v) ? "" : v;
    }

    private static String reported(String s) {
        String v = raw(s);
        return v.isEmpty() || "null".equals(v) ? "未上报 / 未观察到" : v;
    }

    /** Display value where absence must be stated explicitly. */
    private static String show(String s) {
        String v = raw(s);
        return v.isEmpty() || "null".equals(v) ? UNKNOWN : v;
    }
}
