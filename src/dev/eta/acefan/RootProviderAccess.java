package dev.eta.acefan;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** app_process has no AMS application record. Use the same external-provider contract as
 * Android's content command, never an IApplicationThread or a forged application identity.
 * Each call owns one external handle, released in finally (also on death/unmarshal failure).
 * No cached provider can survive a restart. Only side-effect-free reads retry DeadObject once;
 * an uncertain pushRoot is NOT replayed, and the next report reacquires independently. */
final class RootProviderAccess {
    private final Object activityManager;
    private final Method acquire, release, providerCall;
    private final AttributionSource attribution;
    private final AtomicLong acquired = new AtomicLong(), released = new AtomicLong(), releaseFailures = new AtomicLong();

    RootProviderAccess() throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("external provider bridge requires UID 0");
        Class<?> am = Class.forName("android.app.IActivityManager");
        activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        acquire = am.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
        release = am.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
        providerCall = Class.forName("android.content.IContentProvider").getMethod("call",
                AttributionSource.class, String.class, String.class, String.class, Bundle.class);
        // Exactly the UID0 attribution used by cmds/content; 'root' is not an installed App UID.
        attribution = new AttributionSource.Builder(Process.myUid()).setPackageName("root").build();
    }

    Bundle call(String authority, String method, String argument, Bundle extras) {
        if (!("dev.eta.acefan.data".equals(authority) && ("config".equals(method)
                || DailyFanContract.READ.equals(method) || "pushRoot".equals(method)))
                && !("settings".equals(authority) && ("GET_system".equals(method) || "GET_global".equals(method))))
            throw new SecurityException("root provider bridge operation outside relay contract");
        boolean readonly = !"pushRoot".equals(method);
        for (int attempt = 0; ; attempt++) {
            try { return callOnce(authority, method, argument, extras); }
            catch (Exception e) {
                if (readonly && attempt == 0 && e instanceof DeadObjectException) continue;
                if (e instanceof RuntimeException) throw (RuntimeException)e;
                throw new IllegalStateException("external provider " + authority + "/" + method, e);
            }
        }
    }

    String setting(String namespace, String key) {
        if (!("system".equals(namespace) || "global".equals(namespace)))
            throw new IllegalArgumentException("read-only system/global settings only");
        Bundle extras = new Bundle(); extras.putInt("_user", 0);
        Bundle result = call("settings", "GET_" + namespace, key, extras);
        if (result == null || !result.containsKey("value"))
            throw new IllegalStateException("SettingsProvider returned no value envelope for " + key);
        Object value = result.get("value");
        if (value != null && !(value instanceof String)) throw new IllegalStateException("non-string setting " + key);
        return (String)value; // null is a real missing setting, not an IPC failure.
    }

    private Bundle callOnce(String authority, String method, String argument, Bundle extras) throws Exception {
        IBinder token = new Binder(); boolean held = false; Throwable failure = null;
        try {
            Object holder = invoke(acquire, activityManager, authority, 0, token, "*acefan-root*");
            if (holder == null) throw new IllegalStateException("external provider unavailable: " + authority);
            held = true; acquired.incrementAndGet();
            Object provider = Class.forName("android.app.ContentProviderHolder").getField("provider").get(holder);
            if (provider == null) throw new IllegalStateException("external provider has no binder: " + authority);
            Bundle result = (Bundle)invoke(providerCall, provider, attribution, authority, method, argument, extras);
            if (result != null) result.size(); // finish unmarshalling while the external handle is alive.
            return result;
        } catch (Exception | Error e) { failure = e; throw e; }
        finally {
            if (held) {
                try { invoke(release, activityManager, authority, token, 0); released.incrementAndGet(); }
                catch (Exception | Error e) {
                    releaseFailures.incrementAndGet();
                    if (failure != null) failure.addSuppressed(e);
                    else throw e;
                }
            }
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw e;
        }
    }

    Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acquire", "getContentProviderExternal"); out.put("release", "removeContentProviderExternalAsUser");
        out.put("uid", Process.myUid()); out.put("attributionPackage", "root"); out.put("user", 0);
        out.put("acquired", acquired.get()); out.put("released", released.get()); out.put("releaseFailures", releaseFailures.get());
        return out;
    }
}
