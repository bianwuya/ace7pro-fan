package dev.eta.acefan;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Event-only OEM refresh bridge; never samples temperatures or selects/writes a fan target. */
final class KernelOemHandoff {
    private static final Uri API = Uri.parse("content://dev.eta.acefan.data");
    private static final Uri ACK = Uri.withAppendedPath(API, "kernelHandoff");
    private static boolean installed;
    private static Object attachedEngine;
    private static ContentObserver observer;
    private static long consumedAt;

    static synchronized void install(final ClassLoader loader) {
        if (installed) return;
        try {
            final FanServiceCompat.Bootstrap boot = FanServiceCompat.bootstrap(loader, Context.class, Handler.class);
            XposedBridge.hookMethod(boot.initialize, new XC_MethodHook() {
                @Override protected void afterHookedMethod(final MethodHookParam call) {
                    if (call.hasThrowable() || !(call.args[0] instanceof Context) || !(call.args[1] instanceof Handler)) return;
                    final Context context = (Context)call.args[0];
                    final Handler handler = (Handler)call.args[1];
                    final Object engine = call.thisObject;
                    handler.post(() -> attach(loader, boot, engine, context, handler));
                }
            });
            installed = true;
        } catch (Throwable t) { log("bootstrap unavailable", t); }
    }
    private static void attach(ClassLoader loader, FanServiceCompat.Bootstrap boot,
            Object engine, Context context, Handler handler) {
        if (attachedEngine == engine) return;
        try {
            final FanServiceCompat.Handoff bindings = FanServiceCompat.handoff(loader);
            if (observer != null) context.getContentResolver().unregisterContentObserver(observer);
            observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    refresh(boot, engine, bindings, context);
                }
            };
            context.getContentResolver().registerContentObserver(ACK, false, observer);
            attachedEngine = engine;
            // An acknowledgement may have arrived between OEM initialization and observer registration.
            refresh(boot, engine, bindings, context);
        } catch (Throwable t) { log("attach unavailable", t); }
    }
    private static void refresh(FanServiceCompat.Bootstrap boot, Object engine,
            FanServiceCompat.Handoff bindings, Context context) {
        try {
            Bundle token = context.getContentResolver().call(API, "kernelHandoff", null, null);
            if (token == null || !token.getBoolean("released", false)) return;
            long at = token.getLong("at", 0), revision = token.getLong("revision", 0);
            if (at <= 0 || at < consumedAt) return;
            if (at == consumedAt) { acknowledge(context, at); return; }
            String mode = Settings.System.getString(context.getContentResolver(), "cooling_fan_mode_v3");
            String level = Settings.System.getString(context.getContentResolver(), "cooling_fan_customize_level");
            if (!KernelHandoff.fresh(at, SystemClock.elapsedRealtime(), token.getString("mode"),
                    token.getString("level"), mode, level)) return;
            KernelReport proof = KernelReport.parse(token.getString("status"));
            if (!KernelHandoff.released(proof, revision)) return;
            // A token is consumed once, on the original OEM handler. Invalidate only the success
            // cache: do not alter its target, health, scene, mode, self-check or protection state.
            bindings.invalidateConfirmation();
            boot.refresh.invoke(engine, "ACEFan kernel manual handoff");
            consumedAt = at;
            acknowledge(context, at);
            XposedBridge.log("ACEFan: OEM manual handoff refreshed revision=" + revision);
        } catch (Throwable t) { log("refresh failed", t); }
    }
    private static void acknowledge(Context context, long at) {
        Bundle receipt = new Bundle(); receipt.putLong("at", at);
        context.getContentResolver().call(API, "consumeHandoff", null, receipt);
    }
    private static void log(String message, Throwable t) {
        XposedBridge.log("ACEFan kernel handoff: " + message + ": " + FanServiceCompat.summary(t));
    }
}
