package dev.eta.acefan;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;

/** UID0 passive FG receiver. Uses the device-verified, kernel-authenticated Android
 * UEventObserver JNI transport, not logs, polling rpm caches, or a control command.
 * One receiver owns that process-local native socket. The old OEM Hook stays intact
 * in its separate process. A failed optional receiver never changes policy gates. */
final class RootFanRpmObserver implements AutoCloseable {
    private static boolean claimed;
    private volatile boolean stopping;
    private volatile String diagnostic = "not_started";
    private volatile long received, accepted;
    private Method addMatch, removeMatch, waitNext;
    private static final String MATCH = "DEVPATH=" + FanRpm.DEVPATH;

    static synchronized RootFanRpmObserver start() {
        if (Process.myUid() != 0) throw new SecurityException("root FG receiver requires UID 0");
        if (claimed) throw new IllegalStateException("one native FG receiver per root process");
        claimed = true;
        RootFanRpmObserver observer = new RootFanRpmObserver();
        try {
            // Signatures verified on the actual ROM. No compile-only platform replacement
            // classes are packaged, no policy/identity/permission changes are requested.
            Class<?> api = Class.forName("android.os.UEventObserver");
            Method setup = method(api, "nativeSetup");
            observer.addMatch = method(api, "nativeAddMatch", String.class);
            observer.removeMatch = method(api, "nativeRemoveMatch", String.class);
            observer.waitNext = method(api, "nativeWaitForNextEvent");
            setup.invoke(null);
            observer.addMatch.invoke(null, MATCH);
            Telemetry.fanRpmObserver(true);
            observer.diagnostic = "registered_waiting_event";
            Thread reader = new Thread(observer::readLoop, "ACEFan-root-FG");
            reader.setDaemon(true); reader.start();
        } catch (Throwable failure) {
            observer.diagnostic = "unavailable:" + error(failure);
            Telemetry.fanRpmObserver(false);
            Log.w("ACEFan-root-FG", observer.diagnostic);
        }
        return observer;
    }
    private static Method method(Class<?> api, String name, Class<?>... args) throws Exception {
        Method method = api.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private void readLoop() {
        try {
            while (!stopping) {
                Object raw = waitNext.invoke(null); // blocks on the platform's kernel uevent socket
                if (stopping) break;
                if (!(raw instanceof String)) throw new IllegalStateException("native uevent unavailable");
                RootFgEvent event = RootFgEvent.parse((String) raw);
                if (event == null) continue;
                received++;
                if (Telemetry.rootFanRpmEvent(event.path, event.state, event.rpm)) accepted++;
                diagnostic = "receiving";
            }
        } catch (Throwable failure) {
            diagnostic = "unavailable:" + error(failure);
            Telemetry.fanRpmObserver(false);
            Log.w("ACEFan-root-FG", diagnostic);
        }
    }
    String describe() { return diagnostic + ";events=" + received + ";accepted=" + accepted; }
    @Override public void close() {
        stopping = true;
        try { if (removeMatch != null) removeMatch.invoke(null, MATCH); } catch (Throwable ignored) { }
        Telemetry.fanRpmObserver(false);
        // native wait has no public close primitive; the daemon and its socket end
        // with this relay process. Never block policy shutdown waiting for an event.
    }
    private static String error(Throwable failure) {
        while (failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null)
            failure = failure.getCause();
        return failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage());
    }

    /** Bounded actual-device passive probe. No relay constructor, lease, revision,
     * Provider publication, thermal sampling, driver write or new test is started. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--probe".equals(args[0])) throw new IllegalArgumentException("--probe required");
        RootFanRpmObserver observer = start();
        long deadline = SystemClock.elapsedRealtime() + 25000;
        boolean measured = false;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                KernelReport status = KernelReport.parse(KernelSettingsRelay.read(new File("/sys/class/leds/fan/eta_policy/status"), 8192));
                JSONObject out = new JSONObject();
                Telemetry.addKernelFanRpm(out, status.get("physicalState"), status.get("effective"), status.get("fault") == 0 ? "NORMAL" : "UNKNOWN");
                out.put("fgReceiver", observer.describe()); out.put("probe", "passive_root_FG");
                System.out.println(out.toString()); System.out.flush();
                if ("held".equals(out.getJSONObject("fanRpm").getString("quality"))) { measured = true; break; }
                Thread.sleep(1000);
            }
        } finally { observer.close(); }
        System.exit(measured ? 0 : 2);
    }
}
