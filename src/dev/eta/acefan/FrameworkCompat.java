package dev.eta.acefan;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

/** Optional framework information. Manager discovery is never used as an activation test. */
public final class FrameworkCompat {
    private FrameworkCompat() { }
    private static void put(JSONObject out,String key,Object value){try{out.put(key,value);}catch(Exception ignored){}}
    public static final String MANAGER = "org.lsposed.manager";

    /** Call only from the injected process. The UI does not load Xposed classes. */
    public static void putRuntime(JSONObject out) {
        try { RuntimeInfo.write(out); }
        catch (Throwable ignored) {
            put(out, "frameworkApi", JSONObject.NULL);
            put(out, "frameworkVersion", JSONObject.NULL);
        }
    }

    private static final class RuntimeInfo {
        static void write(JSONObject out) throws Exception {
            int api = de.robv.android.xposed.XposedBridge.getXposedVersion();
            put(out, "frameworkApi", api);
            put(out, "frameworkCompatible", CompatibilityCatalog.bridgeSupported(api));
            put(out, "frameworkName", "传统 Xposed 兼容框架");
            put(out, "frameworkVersion", JSONObject.NULL);
            put(out, "frameworkVersionSource", "框架未公开语义版本；不使用 Manager 版本代替");
            // Optional, non-API BuildConfig fields. Release frameworks may inline/obfuscate them.
            // Absence is expected and must never prevent the module from loading.
            ClassLoader cl = de.robv.android.xposed.XposedBridge.class.getClassLoader();
            for (String name : new String[]{"org.lsposed.lspd.core.BuildConfig", "org.lsposed.lspd.BuildConfig"}) {
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    Object version = c.getField("VERSION_NAME").get(null);
                    if (!(version instanceof String) || ((String) version).trim().isEmpty()) continue;
                    put(out, "frameworkVersion", version);
                    put(out, "frameworkVersionSource", "注入框架 BuildConfig（非稳定接口）");
                    try { put(out, "frameworkName", c.getField("FRAMEWORK_NAME").get(null)); } catch (Throwable ignored) { }
                    try { put(out, "frameworkVersionCode", c.getField("VERSION_CODE").get(null)); } catch (Throwable ignored) { }
                    break;
                } catch (Throwable ignored) { }
            }
        }
    }

    public static String managers(Context context) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(MANAGER, 0);
            String version = p.versionName == null ? "版本未公开" : p.versionName;
            return "org.lsposed.manager " + version + "（仅 Manager，不是运行框架版本）";
        } catch (Exception ignored) {
            return "未发现可见的独立 Manager；寄生入口或改包名版本仍可能存在";
        }
    }

    /** Known independent manager only. No QUERY_ALL_PACKAGES or automatic root request. */
    public static boolean openIndependent(Activity activity) {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(MANAGER);
            if (i == null) return false;
            activity.startActivity(i);
            return true;
        } catch (Exception ignored) { return false; }
    }

    /** Best-effort legacy LSPosed parasitic entry, explicitly selected by the user. */
    public static boolean openParasitic(Activity activity) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            // LAUNCH_MANAGER is a CATEGORY, not an action. Some ROMs block this entry.
            i.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
            i.setComponent(new ComponentName("com.android.shell", "com.android.shell.BugreportWarningActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static String systemLine(Context context) {
        String target = "未检测到原厂服务";
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo("com.oplus.exsystemservice", 0);
            target = "OplusExSystemService " + p.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return Build.MODEL + " / " + Build.DEVICE + "\nAndroid " + Build.VERSION.RELEASE
                + " · SDK " + Build.VERSION.SDK_INT + "\n" + Build.DISPLAY + "\n" + target;
    }
}
