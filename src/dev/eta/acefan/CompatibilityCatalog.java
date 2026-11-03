package dev.eta.acefan;

/** Compatibility claims, not a list of devices/versions that have all been tested. */
public final class CompatibilityCatalog {
    public static final int MIN_XPOSED_API = 82;
    public static final String KNOWN_APK_SHA256 = "3eeee6cd69d22fad8ddf6b341c54128ca9c0fb505c6e24ce85ffdd2ef780544c";

    private CompatibilityCatalog() { }

    public static boolean hardwareSupported(String model, String device) {
        return "PMS110".equals(model) && "OP64F1L1".equals(device);
    }

    public static boolean bridgeSupported(int api) { return api >= MIN_XPOSED_API; }

    public static String statusLabel(String status) {
        if ("known-firmware".equals(status)) return "已识别参考固件";
        if ("interface-matched".equals(status)) return "接口匹配 · 待实测";
        if ("pending".equals(status)) return "等待原厂风扇初始化";
        if ("unsupported".equals(status)) return "接口不支持 · 仅观测";
        if ("runtime-error".equals(status)) return "运行异常 · 已交还原厂";
        return "尚未取得版本兼容诊断";
    }

    public static String describe() {
        return "系统版本\n"
                + "安装/观测：Android 9 及以上（minSdk 28），不再将 Android 17/SDK 37 作为增强的唯一入口。\n"
                + "增强：仍限定 ACE7pro PMS110 / OP64F1L1；原厂 Oplus 风扇 V3 的候选、选择器、控制器、初始化及重算接口须完整匹配。系统版本号本身不能证明适配；AOSP、移除原厂服务或接口变化时仅观测。\n"
                + "参考样本：PMS110_17.0.0.159(CN01)，Android 17 / SDK 37，OplusExSystemService 1.4.6。旧版模块已有注入/遥测记录；本版仍需重载与功能复测。其他系统版本暂无真实固件样本，不能列为已实测。\n\n"
                + "LSPosed / Xposed 版本\n"
                + "模块入口使用传统 Xposed API ≥82，不依赖 modern libxposed API，也不按分支版本号硬封锁。仅提供 modern API、不兼容传统模块的框架不支持。\n"
                + "• 官方 LSPosed 1.8.6：上游声明 Android 8.1–13，传统模块候选。\n"
                + "• 官方 LSPosed 1.9.2：上游声明 Android 8.1–14，传统模块候选。不要将其用在 Android 17 上。\n"
                + "• JingMatrix LSPosed 1.10.2：上游声明 Android 8.1–16，传统模块候选。\n"
                + "• JingMatrix Vector 2.x：上游声明传统/现代 API 兼容，当前 README 范围到 Android 17 Beta；不同发布版须核对其发行说明。\n"
                + "• 开发参考机 LSPosed 2.2.1 (7912)：2026-11-03 已取得旧版模块注入/遥测证据，不等于已验收新版本或所有同号分支。\n\n"
                + "上述 Android 范围是框架上游声明，不是本模块的风扇实测范围。框架必须首先支持你的 Android；API 数字不是 LSPosed 的 1.x/2.x 版本号。Manager 的版本也不等于正在运行的框架版本。独立/寄生/改包名管理器不改变模块接口要求。\n\n"
                + "使用条件不变：息屏有线充电、原厂自动模式；锁屏衔接及解锁后 2 秒交还保持原样。不增加亮屏、无线或其他控制场景，不绕过原厂保护。\n\n"
                + "来源与完整验证边界见随包 COMPATIBILITY.md。";
    }
}
