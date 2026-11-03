package dev.eta.acefan;

/** Device-family scope and runtime facts, without firmware whitelists. */
public final class CompatibilityCatalog {
    public static final int MIN_XPOSED_API=82;
    private CompatibilityCatalog(){}
    public static boolean hardwareSupported(String model,String device){return hardwareSupported(model,device,"");}
    public static boolean hardwareSupported(String model,String device,String market){
        if("PMS110".equalsIgnoreCase(model))return true;
        return ace7(model)||ace7(market);
    }
    private static boolean ace7(String value){
        if(value==null)return false;
        return java.util.regex.Pattern.compile("(?i)(?:^|[^a-z0-9])ace[ _-]*7(?![0-9])(?:$|[^a-z0-9]|pro(?:$|[^a-z0-9])|t(?:$|[^a-z0-9])|v(?:$|[^a-z0-9]))").matcher(value).find();
    }
    public static boolean bridgeSupported(int api){return api>=MIN_XPOSED_API;}
    public static String statusLabel(String status){
        if("ready".equals(status))return "接口已接入 · 等待真实场景";
        if("attempted".equals(status))return "候选已应用 · 等待控制反馈";
        if("request-observed".equals(status))return "原厂控制调用完成";
        if("confirmed".equals(status))return "原厂状态已确认 · 非物理转速";
        if("setup-failed".equals(status))return "接口接入失败 · 可导出详情";
        if("outside-family".equals(status))return "ACE 7 系列以外 · 仅观测";
        if("known-firmware".equals(status))return "旧版参考固件结论 · 请重载";
        if("interface-matched".equals(status))return "旧版接口匹配 · 待实测";
        if("pending".equals(status))return "等待原厂风扇初始化";
        if("unsupported".equals(status))return "旧版接口未接入 · 见失败详情";
        if("runtime-error".equals(status))return "运行环节异常 · 见失败详情";
        return "尚未取得运行诊断";
    }
    public static String describe(){
        return "适配范围：一加 ACE 7 系列。读取型号、市场名称和原厂服务实际接口；系统版本号、APK 哈希和方法指纹均不是启动门槛。\n\n"
            +"检测流程：原厂初始化 → 实际对象及接口解析 → 上限/场景实读 → 安装候选层 Hook → 正常场景中的候选应用 → 同轮原厂控制反馈。未触发场景表示等待，不能判定不能用。\n\n"
            +"独立记录：候选入口、场景读取、档位上限、健康状态、控制观察、原厂重算。可选读取失败不全局熔断；本次必要读取失败保留本次原厂结果，下次调用继续。\n\n"
            +"失败反馈：设备兼容报告包含接口成员、实际值、调用/失败次数、失败环节、异常和时间（开机后毫秒）。复制、分享或保存完整报告后发给开发者。App 无网络权限，不自动上传。\n\n"
            +"增强条件保持息屏有线充电、原厂自动模式；已确认会话可锁屏衔接，解锁后最多 2 秒交还。保留原厂下游处理，不写风扇/温控节点。\n\n"
            +"Android 9+；传统 Xposed API ≥82。框架须兼容当前系统；运行框架与 Manager 版本分开显示。接口加载、候选应用、控制反馈与物理效果分别记录，不是多 ROM 实测承诺。";
    }
}
