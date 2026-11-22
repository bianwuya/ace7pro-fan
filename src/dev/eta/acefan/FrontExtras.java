package dev.eta.acefan;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** User-invoked diagnostic and local file operations. No OEM calls or network I/O. */
public final class FrontExtras {
    private static final Uri BASE = Uri.parse("content://" + DataProvider.AUTHORITY);
    private static final String[] COLUMNS = {"boot", "battery", "board", "fan", "mos", "driver", "effective", "state",
        "oem", "candidate", "request", "active", "reason", "interactive", "plugged", "charging", "quality",
        "configVersion", "hook", "hookVersion", "phase", "keyguardLocked", "handoffRemainingMs", "build", "controlMode"};
    private static final String[] DAILY = {"version", "state", "fusedTemp", "startTemp", "minLevel", "contributorMask",
        "demand", "oem", "candidate", "request", "decisionElapsed", "decisionFusedTemp"};
    private FrontExtras() { }

    public static String report(Context context) {
        JSONObject j = latestHook(context);
        String boot = "";
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            char[] b = new char[64]; int n = r.read(b); if (n > 0) boot = new String(b, 0, n).trim();
        } catch (IOException | RuntimeException ignored) { }
        boolean fresh = j != null && ReportFreshness.fresh(j.optLong("wall", 0), j.optLong("elapsed", -1),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), j.optString("boot", ""), boot,
                Sampling.staleMs(Sampling.IDLE_MS));
        return DiagnosticReport.build(reportInput(context, j, fresh));
    }

    private static JSONObject latestHook(Context context) {
        try {
            Bundle b = context.getContentResolver().call(BASE, "latest", null, null);
            JSONObject newest = b == null ? null : parse(b.getString("json"));
            if (newest != null && "已注入".equals(newest.optString("hook"))) return newest;
            JSONObject hookup = null;
            Uri u = DataProvider.CONTENT_URI.buildUpon().appendQueryParameter("since", Long.toString(
                    System.currentTimeMillis() - 60L * 60_000L)).build();
            try (Cursor c = context.getContentResolver().query(u, new String[]{"json"}, null, null, null)) {
                if (c != null) for (int n = 0; n < 4000 && c.moveToNext(); n++) {
                    JSONObject j = parse(c.getString(0));
                    if (j != null && "已注入".equals(j.optString("hook"))) hookup = j;
                }
            }
            return hookup;
        } catch (RuntimeException ignored) { return null; }
    }

    private static JSONObject parse(String raw) {
        if (raw != null) try { return new JSONObject(raw); } catch (Exception ignored) { }
        return null;
    }

    private static String targetVersionOf(Context c, String packageName) {
        try {
            String v = c.getPackageManager().getPackageInfo(packageName, 0).versionName;
            return v == null ? "" : v;
        } catch (Exception ignored) { return "未安装或不可见"; }
    }

    private static DiagnosticReport.Input reportInput(Context context, JSONObject j, boolean fresh) {
        DiagnosticReport.Input in = new DiagnosticReport.Input();
        in.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        in.appVersion = BuildInfo.APP_VERSION;
        in.hookVersion = j == null ? "" : j.optString("hookVersion", "");
        // Same rule the UI uses for "待重载": an injected hook with the wrong version is stale.
        boolean injected = j != null && "已注入".equals(j.optString("hook", "")) && fresh;
        in.hookStale = injected && !BuildInfo.HOOK_VERSION.equals(in.hookVersion);
        in.model = Build.MODEL;
        in.device = Build.DEVICE;
        in.brand = Build.BRAND + " / " + Build.MANUFACTURER;
        in.release = Build.VERSION.RELEASE;
        in.sdk = String.valueOf(Build.VERSION.SDK_INT);
        in.display = Build.DISPLAY;
        in.fingerprint = Build.FINGERPRINT;
        in.incremental = Build.VERSION.INCREMENTAL;
        in.hardwareGate = j == null ? "" : j.optString("hardwareGate", "");
        in.hookState = j == null ? "" : j.optString("hook", "");
        if ("已注入".equals(in.hookState) && !fresh) {
            in.hookState = "历史注入回报（已过期）";
            in.extra = "第 [2]–[6] 节的 Hook 状态和运行证据来自过期历史回报，不能证明当前状态。";
        }
        in.frameworkApi = j == null || j.isNull("frameworkApi") ? "" : j.optString("frameworkApi", "");
        in.frameworkVersion = j == null || j.isNull("frameworkVersion") ? "" : j.optString("frameworkVersion", "");
        in.manager = FrameworkCompat.managers(context);
        in.scope = j == null ? "com.oplus.exsystemservice" : j.optString("targetScope", "com.oplus.exsystemservice");
        in.status = j == null ? "" : j.optString("compatibilityStatus", "");
        in.stage = j == null ? "" : j.optString("compatibilityStage", "");
        in.detail = j == null ? "" : j.optString("compatibilityDetail", "");
        in.profile = j == null ? "" : j.optString("firmwareProfile", "");
        in.trace = j == null ? "" : j.optString("compatibilityTrace", "");
        in.runtimeEvidence = MainActivity.reportField(j, "runtimeEvidence");
        in.runtimeProgress = MainActivity.reportField(j, "runtimeProgress");
        in.runtimeCounters = MainActivity.reportField(j, "runtimeCounters");
        in.lastFailureStage = MainActivity.reportField(j, "lastFailureStage");
        in.lastFailureDetail = MainActivity.reportField(j, "lastFailureDetail");
        in.currentFailuresReported=j!=null&&j.has("currentFailures");
        in.currentFailures=MainActivity.reportField(j,"currentFailures");
        in.lastFailureResolved=MainActivity.reportField(j,"lastFailureResolved");
        in.controlConfirmation=MainActivity.reportField(j,"controlConfirmation");
        in.knownApk = j == null ? "" : j.optString("knownApk", "");
        // Fall back to what this app can read itself when the hook never reported (not injected).
        in.targetPackage = j == null || j.optString("targetPackage", "").isEmpty()
                ? "com.oplus.exsystemservice" : j.optString("targetPackage");
        in.targetVersion = targetVersionOf(context, in.targetPackage);
        if (j != null) {
            JSONObject day = j.optJSONObject("daily");
            if (day != null) in.extra += (in.extra.isEmpty() ? "" : "\n")
                    + "日用回报（" + (fresh ? "近期" : "历史") + "）：配置 " + MainActivity.level(day,"version")
                    + "，开关 " + MainActivity.bool(day,"enabled") + "，状态 " + day.optString("state","—")
                    + "，融合/介入温度 " + MainActivity.temp(day,"fusedTemp") + "/" + MainActivity.temp(day,"startTemp")
                    + "，需求档 " + MainActivity.level(day,"demand") + "。\n"
                    + "最近原厂决策（开机后 ms）：" + day.optString("decisionElapsed", "—")
                    + "，当时融合温度 " + MainActivity.temp(day,"decisionFusedTemp")
                    + "，原厂/候选/请求 " + MainActivity.level(day,"oem") + "/"
                    + MainActivity.level(day,"candidate") + "/" + MainActivity.level(day,"request") + "；不代表实际档位。";
            in.targetPath = j.optString("targetPath", "");
            in.targetSha256 = j.optString("targetSha256", "");
            in.enabled = MainActivity.bool(j, "enabled");
            in.active = MainActivity.bool(j, "active");
            in.phase = j.optString("phase", "");
            in.reason = j.optString("reason", "");
            in.oem = MainActivity.level(j, "oem");
            in.candidate = MainActivity.level(j, "candidate");
            in.request = MainActivity.level(j, "request");
            in.driver = MainActivity.level(j, "driver");
            in.effective = MainActivity.level(j, "effective");
            in.battery = MainActivity.temp(j, "battery");
            in.board = MainActivity.temp(j, "board");
            in.fan = MainActivity.temp(j, "fan");
            in.mos = MainActivity.temp(j, "mos");
            in.plugged = MainActivity.pluggedText(j);
            in.keyguard = MainActivity.bool(j, "keyguardLocked");
            in.interactive = MainActivity.bool(j, "interactive");
        }
        if (in.targetPath == null || in.targetPath.isEmpty()) in.targetPath = "未上报";
        if (in.targetSha256 == null || in.targetSha256.isEmpty()) in.targetSha256 = "未上报（不作为接入门槛）";
        return in;
    }

    public static int exportCsv(Context context, Uri dest) throws IOException {
        try (Cursor c = context.getContentResolver().query(DataProvider.CONTENT_URI, new String[]{"json"}, null, null, null)) {
            if (c == null) throw new IOException("history unavailable");
            try (BufferedWriter out = writer(context, dest)) {
                out.write("wall_iso,wall_ms,elapsed_ms,boot,battery_C,board_C,fan_C,mos_C,driverLevel,levelEff,state,oem,candidate,request,active,reason,interactive,plugged,charging,quality,configVersion,hook,hookVersion,phase,keyguardLocked,handoffRemainingMs,build,controlMode");
                for (String key : DAILY) out.write(",daily_" + key);
                out.newLine();
                SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
                int count = 0;
                for (int n = 0; n < 4000 && c.moveToNext(); n++) {
                    JSONObject j = parse(c.getString(0));
                    if (j == null || j.optLong("wall", 0) <= 0) continue;
                    long at = j.optLong("wall");
                    out.write(MainActivity.quote(time.format(new Date(at))) + "," + at);
                    cell(out, j.opt("elapsed"));
                    for (String key : COLUMNS) cell(out, j.opt(key));
                    JSONObject day = j.optJSONObject("daily");
                    for (String key : DAILY) cell(out, day == null ? null : day.opt(key));
                    out.newLine(); count++;
                }
                return count;
            }
        }
    }

    private static void cell(BufferedWriter out, Object value) throws IOException {
        out.write(',');
        if (value == null || value == JSONObject.NULL) return;
        String text = String.valueOf(value);
        // Protect user-opened spreadsheet files from formula evaluation in telemetry text fields.
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        if (value instanceof String && first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0) text = "'" + text;
        out.write(MainActivity.quote(text));
    }

    private static BufferedWriter writer(Context context, Uri dest) throws IOException {
        OutputStream stream = context.getContentResolver().openOutputStream(dest, "wt");
        if (stream == null) throw new IOException("document unavailable");
        return new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    public static void writeReport(Context context, Uri dest, String report) throws IOException {
        try (BufferedWriter out = writer(context, dest)) { out.write(report); }
    }

    public static boolean clearHistory(Context context) {
        try {
            Bundle r = context.getContentResolver().call(BASE, "clear", null, null);
            return r != null && r.getBoolean("ok", false);
        } catch (RuntimeException ignored) { return false; }
    }
}
