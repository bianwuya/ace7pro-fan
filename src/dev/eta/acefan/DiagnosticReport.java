package dev.eta.acefan;

/**
 * Pure-Java device compatibility report. No Android, no Xposed, no I/O: every field is a
 * string handed in by the caller, so the same formatter can be unit-tested and reused.
 * The report is evidence for adaptation, never a substitute for physical verification.
 */
public final class DiagnosticReport {
    public static final String FORMAT = "acefan-report-1";
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
        public String enabled = "", active = "", phase = "", reason = "";
        public String candidate = "", request = "", driver = "", effective = "";
        public String battery = "", board = "", fan = "", mos = "";
        public String plugged = "", keyguard = "", interactive = "";
        public String extra = "";
        /** True when the injected hook is older than this App build: fields below are absent. */
        public boolean hookStale;
    }

    private DiagnosticReport() { }

    public static String build(Input in) {
        if (in == null) in = new Input();
        StringBuilder b = new StringBuilder();
        b.append("ACE7pro 风扇助手 · 设备兼容报告\n");
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

        b.append("\n[4] 接口适配\n");
        b.append("结论：").append(CompatibilityCatalog.statusLabel(in.status)).append('\n');
        b.append("失败阶段：").append(show(in.stage)).append('\n');
        b.append("失败原因：").append(show(in.detail)).append('\n');
        b.append("匹配档案：").append(show(in.profile)).append('\n');
        if (!raw(in.trace).isEmpty()) b.append("解析轨迹：\n").append(raw(in.trace)).append('\n');

        b.append("\n[5] 当前策略与遥测\n");
        b.append("增强开关：").append(onOff(in.enabled)).append('\n');
        b.append("正在增强：").append(onOff(in.active)).append('\n');
        b.append("阶段：").append(show(in.phase)).append('\n');
        b.append("决策原因：").append(show(in.reason)).append('\n');
        b.append("档位（原厂 / 请求 / 驱动 / 有效）：")
                .append(orUnknown(join(in.candidate, in.request, in.driver, in.effective))).append('\n');
        b.append("温度°C（电池 / 主板 / 风扇 / MOS）：")
                .append(orUnknown(join(in.battery, in.board, in.fan, in.mos))).append('\n');
        b.append("供电 / 锁屏 / 亮屏：")
                .append(orUnknown(join(in.plugged, in.keyguard, in.interactive))).append('\n');

        if (!raw(in.extra).isEmpty()) b.append("\n[6] 补充\n").append(raw(in.extra)).append('\n');

        b.append("\n[适配建议]\n").append(advice(in));
        b.append("\n说明：本报告只含公开硬件、版本与风扇接口信息；不含账号、位置、通讯录或网络数据，")
                .append("App 本身也没有网络权限，导出内容由你自行决定发给谁。\n");
        return b.toString();
    }

    /** Concrete next evidence for this device, ordered by the first blocking gate. */
    public static String advice(Input in) {
        if (in == null) in = new Input();
        String status = raw(in.status), detail = raw(in.detail), state = raw(in.hookState);
        boolean injected = "已注入".equals(state) || "只读预览".equals(state);
        StringBuilder b = new StringBuilder();
        boolean any = false;
        // A stale hook may or may not have reported the compatibility fields; judge by content.
        if (in.hookStale) {
            boolean missing = status.isEmpty() || status.equals("pending") || raw(in.trace).isEmpty();
            b.append("- 运行中的 Hook 是旧版本（").append(show(in.hookVersion)).append("），本报告由它生成。")
                    .append(missing
                            ? "接口适配、原厂 APK 哈希或解析轨迹尚未上报，"
                            : "接口适配字段虽有内容，仍建议以重启后的报告为准，")
                    .append("请正常重启让本版 Hook 加载后再导出一次。\n");
            if (missing) {
                b.append("- 空白的“结论/失败阶段”是缺少上报，不是接口通过；重启前请勿据此判断兼容性。\n");
            }
            any = true;
        }
        if (isHardwareFail(in.hardwareGate)) {
            b.append("- 本模块只接管 ACE7pro（PMS110 / OP64F1L1）。这台设备不会因为装模块就生效，")
                    .append("需要按它的硬件与原厂风扇接口单独适配。\n");
            any = true;
        }
        if (!injected) {
            b.append("- 没有有效的注入回报：先在 LSPosed 启用本模块，作用域只勾 ").append(show(in.scope))
                    .append("，然后正常重启（覆盖安装不会让已运行的原厂进程重载）。\n");
            b.append("- 若已启用仍无回报：附上本报告第 [2] 节的原厂框架名称、版本与 Xposed Bridge API，")
                    .append("并确认框架支持当前 Android 版本且仍兼容传统 Xposed 模块。\n");
            any = true;
        }
        if ("unsupported".equals(status)) {
            b.append("- 接口未通过（阶段：").append(show(in.stage)).append("）：请提供该设备原厂服务 APK 本身")
                    .append("（第 [3] 节的路径、版本与 SHA-256 对应同一个文件），而不是截图或版本号。\n");
            if (detail.contains("指纹") || detail.contains("代码")) {
                b.append("- 失败点像是关键保护链代码变化：同版本固件也可能改了实现，需要这份新 APK 才能核对。\n");
            }
            if (detail.contains("NoSuchMethod") || detail.contains("NoSuchField") || detail.contains("missing")) {
                b.append("- 失败点像是方法/字段缺失：说明这个固件的风扇接口被裁剪或改名，需要比对第 [4] 节轨迹与 APK。\n");
            }
            b.append("- 在适配前请保持“仅观测”，不要为了让它生效而删除校验或强行接管。\n");
            any = true;
        } else if ("interface-matched".equals(status)) {
            b.append("- 接口与参考代码已匹配，但还没在这台设备上做过实机验收：")
                    .append("需要一次息屏有线充电的记录（是否增强、点亮锁屏是否延续、解锁后是否交还、拔线是否恢复）。\n");
            any = true;
        } else if ("runtime-error".equals(status)) {
            b.append("- 运行期出现异常并已交还原厂：附上失败原因与发生时间，便于定位是哪一步读取或调用失败。\n");
            any = true;
        } else if ("pending".equals(status) && injected) {
            b.append("- 框架已注入但还没看到原厂风扇初始化：确认风扇总开关打开、用过一次风扇或散热，再重新导出本报告。\n");
            any = true;
        }
        if (!any && status.isEmpty() && injected) {
            b.append("- 已注入但没有接口适配结论：这是上报缺失而不是通过，请重新导出或重启后复核。\n");
            any = true;
        }
        if (!any) {
            b.append("- 当前状态没有明显阻塞项；如仍不符合预期，请说明具体现象（阶段、档位、温度）与发生时间。\n");
        }
        return b.toString();
    }

    private static boolean isHardwareFail(String gate) {
        String g = raw(gate);
        return g.contains("不通过") || g.contains("不支持");
    }

    private static String hardwareText(String gate) {
        String g = raw(gate);
        return g.isEmpty() ? "未知（未读取）" : g;
    }

    private static String hookStateText(String state) {
        String s = raw(state);
        if (s.isEmpty()) return "未知（无回报）";
        if ("已注入".equals(s)) return "已注入目标进程";
        if ("只读预览".equals(s)) return "未注入 · 仅本机只读观测";
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
        if ("true".equals(s) || "一致".equals(s)) return "与已知参考固件一致";
        if ("false".equals(s) || "不一致".equals(s)) return "不一致（同接口代码仍可适配）";
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
        return s == null ? "" : s.trim();
    }

    /** Display value where absence must be stated explicitly. */
    private static String show(String s) {
        String v = raw(s);
        return v.isEmpty() || "null".equals(v) ? UNKNOWN : v;
    }
}
