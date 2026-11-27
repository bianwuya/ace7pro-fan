package dev.eta.acefan;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * APK app_process entry point for KSU's root service (no Activity, permissions, UID/SELinux changes).
 * Settings + safety gates + identical-line lease writes only. All thermal sampling and fan control
 * belong to eta_policy. This class never invokes an OEM controller/getter/setter/HAL.
 *
 * Three independent lanes: callbacks/heartbeat (no IPC); settings/safety worker (may block in IPC);
 * one writer (no Provider IPC). A blocked worker, dead callbacks or blocked report publication loses
 * authorization within bounded time, then the writer disables or lets the kernel's <=5s lease expire.
 * AndroidSettings are revalidated every 2s to cover lost notifications; Provider config is read only
 * at startup, on its dedicated change URIs, or retry after an unsuccessful read.
 */
public final class KernelSettingsRelay {
    private static final String TAG = "ACEFan-KernelRelay";
    private static final Uri API = Uri.parse("content://dev.eta.acefan.data");
    private static final Uri CONFIG = Uri.withAppendedPath(API, "config");
    private static final Uri DAILY = Uri.withAppendedPath(API, DailyFanContract.PATH);
    private static final int POCKET_SENSOR = 0x1fa2683;
    private static final String[] SYSTEM_KEYS = {"cooling_fan_main_switch_modify", "cooling_fan_main_switch",
            "cooling_fan_mode_v3", "cooling_fan_mode_auto_control_switch", "cooling_fan_charge_switch",
            "regular_charge_protection_status", "cooling_fan_mic_mutual", "rider_mode_main_switch",
            "super_powersave_mode_state", "sys_oplus_high_performance_spec"};
    private static final String[] GLOBAL_KEYS = {"cooling_fan_service_abort", "op_audio_mic_status", "gamemode_delayed"};
    private final Context context;
    private final ContentResolver resolver;
    private final RootProviderAccess providers;
    private final PolicyFiles files;
    private final RevisionStore revisions;
    private final KernelProtocol.Lease lease;
    private final HandlerThread eventThread = new HandlerThread("ACEFan-root-events");
    private Handler events;
    private final Semaphore workWake = new Semaphore(0), writeWake = new Semaphore(0);
    private final AtomicLong epoch = new AtomicLong(), configEpoch = new AtomicLong();
    private final AtomicBoolean configDirty = new AtomicBoolean(true), stopping = new AtomicBoolean();
    private volatile long eventsAt, statusAt, publisherAt;
    private volatile Authorization authorization;
    private volatile KernelReport status;
    private volatile Sent sent;
    private volatile String diagnostic = "initializing", signalSupport = "not_registered", sourceSetupFailure = "registration_incomplete";
    private volatile boolean observersReady, thermalListenerReady, audioListenerReady, pocketListenerReady;
    private volatile int pocket = -1;
    private Thread worker, writer, reporter;
    private RootFanRpmObserver fgObserver;
    private volatile FileDescriptor writingFd;
    private PowerManager power;
    private AudioManager audio;
    private UsbManager usb;
    private SensorManager sensors;
    private SensorEventListener pocketListener;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private AudioManager.AudioRecordingCallback recordingListener;
    private AudioManager.OnModeChangedListener modeListener;
    private ContentObserver observer;
    private BroadcastReceiver receiver;
    private Method getCurrentUser;

    private static final class Meta {
        final KernelProtocol.Settings daily, charge;
        final long dailyVersion, chargeVersion;
        Meta(KernelProtocol.Settings daily, KernelProtocol.Settings charge, long dv, long cv) {
            this.daily = daily; this.charge = charge; dailyVersion = dv; chargeVersion = cv;
        }
    }
    private static final class Authorization {
        final KernelProtocol.Config config;
        final Meta meta;
        final long epoch, at;
        Authorization(KernelProtocol.Config config, Meta meta, long epoch, long at) {
            this.config = config; this.meta = meta; this.epoch = epoch; this.at = at;
        }
    }
    private static final class Sent {
        final long revision;
        final KernelProtocol.Config config;
        final Meta meta;
        Sent(long revision, KernelProtocol.Config config, Meta meta) { this.revision = revision; this.config = config; this.meta = meta; }
    }

    public static void main(String[] args) throws Exception {
        final boolean probe = args.length == 1 && "--probe".equals(args[0]);
        final boolean listeners = args.length == 1 && "--probe-listeners".equals(args[0]);
        if (Process.myUid() != 0 || (args.length != 0 && !probe && !listeners))
            throw new SecurityException("root app_process entry; only optional arguments are --probe or --probe-listeners");
        if (probe || listeners) {
            Map<String, Object> out;
            int code = 0;
            try {
                Context context = systemContext();
                out = listeners ? RootListenerProbe.run(context, POCKET_SENSOR, SYSTEM_KEYS, GLOBAL_KEYS) : probe(context);
            }
            catch (Throwable t) { out = new LinkedHashMap<>(); out.put("ok", false); out.put("rootContextError", error(t)); code = 1; }
            System.out.println(new JSONObject(out).toString()); System.out.flush();
            // systemMain starts Binder/framework threads. Explicit exit is required for a one-shot
            // probe; no relay instance, signal handlers, callbacks, FD writer or revision journal exists.
            System.exit(code); return;
        }
        KernelSettingsRelay relay = new KernelSettingsRelay(systemContext());
        Runtime.getRuntime().addShutdownHook(new Thread(relay::shutdown, "ACEFan-root-shutdown"));
        relay.installSignals(); relay.start(); Looper.loop(); relay.shutdown();
    }
    private static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain"); systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext"); getSystemContext.setAccessible(true);
        Object result = getSystemContext.invoke(thread);
        if (!(result instanceof Context)) throw new IllegalStateException("root system Context unavailable");
        return (Context)result;
    }
    private interface ProbeRead { Object read() throws Exception; }
    private static Map<String, Object> probeRead(ProbeRead read) {
        Map<String, Object> out = new LinkedHashMap<>();
        try { out.put("value", read.read()); out.put("ok", true); }
        catch (Throwable t) { out.put("ok", false); out.put("error", error(t)); }
        return out;
    }
    private static Map<String, Object> bundleFields(Bundle b) {
        if (b == null) throw new IllegalStateException("Provider returned null Bundle");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : b.keySet()) out.put(key, b.get(key));
        return out;
    }
    private static Map<String, Object> probeSetting(RootProviderAccess providers, String namespace, String key) {
        return probeRead(() -> {
            // Reflection/Provider exceptions leave ok=false, not a missing-value default.
            String raw = providers.setting(namespace, key);
            Map<String, Object> value = new LinkedHashMap<>(); value.put("raw", raw);
            value.put("effective", KernelProtocol.oemSetting(namespace, key, raw));
            value.put("missingDefaultApplied", raw == null && KernelProtocol.oemMissingDefault(namespace, key) != null);
            value.put("missingEvidence", KernelProtocol.oemMissingEvidence(namespace, key));
            return value;
        });
    }
    /** One-shot true root Context/Provider/safety API probe. No node access (even reads), listeners,
     * thermal samples, settings writes, revision files, GUI or long-running worker threads. */
    private static Map<String, Object> probe(Context context) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        RootProviderAccess providers = new RootProviderAccess();
        out.put("ok", true); out.put("probe", "root_context_settings_readonly"); out.put("uid", Process.myUid());
        out.put("contextClass", context.getClass().getName()); out.put("packageName", context.getPackageName());
        out.put("sdk", Build.VERSION.SDK_INT); out.put("model", Build.MODEL); out.put("device", Build.DEVICE);
        out.put("sysfsAccess", false); out.put("revisionAccess", false); out.put("listenersRegistered", false);
        out.put("currentUser", probeRead(() -> Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null)));
        out.put("config", probeRead(() -> bundleFields(providers.call(API.getAuthority(), "config", null, null))));
        out.put("dailyConfig", probeRead(() -> bundleFields(providers.call(API.getAuthority(), DailyFanContract.READ, null, null))));
        out.put("providerParameters", probeRead(() -> {
            Meta meta = decodeProvider(providers.call(API.getAuthority(), "config", null, null), providers.call(API.getAuthority(), DailyFanContract.READ, null, null));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dailyVersion", meta.dailyVersion); result.put("chargeVersion", meta.chargeVersion);
            result.put("canonicalDisabled", new KernelProtocol.Config(meta.daily, meta.charge,
                    new KernelProtocol.Gates(0, 0, 0, 0, -1), KernelProtocol.ZONES).line(1, false).trim());
            result.put("notWritten", true); return result;
        }));
        Map<String, Object> system = new LinkedHashMap<>(), global = new LinkedHashMap<>();
        for (String key : SYSTEM_KEYS) system.put(key, probeSetting(providers, "system", key));
        for (String key : GLOBAL_KEYS) global.put(key, probeSetting(providers, "global", key));
        out.put("system", system); out.put("global", global);
        out.put("power", probeRead(() -> {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) throw new IllegalStateException("PowerManager unavailable");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("thermalStatus", pm.getCurrentThermalStatus()); // status enum, NOT a temperature sample
            result.put("listenerMethodPresent", PowerManager.class.getMethod("addThermalStatusListener", java.util.concurrent.Executor.class,
                    PowerManager.OnThermalStatusChangedListener.class) != null);
            result.put("listenerPermissionUntested", true); return result;
        }));
        out.put("audio", probeRead(() -> {
            AudioManager am = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) throw new IllegalStateException("AudioManager unavailable");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", probeRead(() -> am.getMode()));
            result.put("activeRecordings", probeRead(() -> {
                List<AudioRecordingConfiguration> list = am.getActiveRecordingConfigurations();
                if (list == null) throw new IllegalStateException("recording list unavailable");
                Map<String, Object> value = new LinkedHashMap<>(); value.put("count", list.size());
                value.put("clear", list.isEmpty()); return value;
            }));
            result.put("listenersNotRegistered", true); return result;
        }));
        out.put("usb", probeRead(() -> {
            UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
            if (manager == null) throw new IllegalStateException("UsbManager unavailable");
            Map<String, UsbDevice> devices = manager.getDeviceList();
            if (devices == null) throw new IllegalStateException("USB device list unavailable");
            List<Map<String, Object>> entries = new ArrayList<>(); boolean clear = true;
            for (UsbDevice device : devices.values()) {
                if (device == null) throw new IllegalStateException("USB entry unavailable");
                Map<String, Object> value = new LinkedHashMap<>(); value.put("vid", device.getVendorId()); value.put("pid", device.getProductId());
                boolean handle = device.getVendorId() == 8921 && device.getProductId() == 14443;
                value.put("blockedHandle", handle); if (handle) clear = false; entries.add(value);
            }
            Map<String, Object> result = new LinkedHashMap<>(); result.put("devices", entries); result.put("handleClear", clear); return result;
        }));
        out.put("pocket", probeRead(() -> {
            SensorManager manager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
            if (manager == null) throw new IllegalStateException("SensorManager unavailable");
            Sensor sensor = pocketSensor(manager);
            Map<String, Object> result = new LinkedHashMap<>(); result.put("present", sensor != null);
            result.put("type", POCKET_SENSOR);
            result.put("defaultNonWakeupPresent", manager.getDefaultSensor(POCKET_SENSOR) != null);
            result.put("wakeUpRequested", true);
            result.put("listedCount", manager.getSensorList(POCKET_SENSOR).size());
            result.put("gate", "unknown_no_sample_no_listener");
            result.put("registrationPermissionUntested", true);
            if (sensor != null) {
                result.put("name", sensor.getName()); result.put("vendor", sensor.getVendor()); result.put("stringType", sensor.getStringType());
                result.put("wakeUp", sensor.isWakeUpSensor());
                result.put("requiredPermission", probeRead(() -> Sensor.class.getMethod("getRequiredPermission").invoke(sensor)));
            }
            return result;
        }));
        Map<String, Object> permissions = new LinkedHashMap<>();
        for (String permission : new String[]{"android.permission.MODIFY_AUDIO_ROUTING", "android.permission.RECORD_AUDIO", "android.permission.READ_PHONE_STATE"})
            permissions.put(permission, probeRead(() -> context.checkSelfPermission(permission)));
        out.put("permissions", permissions);
        out.put("providerBridge", providers.diagnostics());
        return out;
    }

    private KernelSettingsRelay(Context context) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("UID 0 required");
        StringBuilder market = new StringBuilder();
        try {
            Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            for (String key : new String[]{"ro.vendor.oplus.market.name", "ro.oplus.market.name", "ro.product.marketname", "ro.product.vendor.marketname"})
                market.append(get.invoke(null, key, "")).append(' ');
        } catch (ReflectiveOperationException ignored) { /* unknown market never widens the family gate */ }
        if (!CompatibilityCatalog.hardwareSupported(Build.MODEL, Build.DEVICE, market.toString()))
            throw new SecurityException("kernel relay limited to the supported ACE 7 family");
        this.context = context; resolver = context.getContentResolver(); providers = new RootProviderAccess();
        files = PolicyFiles.discover();
        revisions = new RevisionStore();
        long kernel = KernelProtocol.snapshotRevision(read(files.snapshot, 1024));
        lease = new KernelProtocol.Lease(revisions.read(), kernel);
    }

    private void start() {
        eventThread.start(); events = new Handler(eventThread.getLooper());
        events.post(new Runnable() { @Override public void run() {
            eventsAt = now();
            if (!stopping.get()) events.postDelayed(this, 500);
        }});
        try { registerSources(); observersReady = true; }
        catch (Throwable t) {
            sourceSetupFailure = error(t); diagnostic = "safety_interface_unavailable:" + sourceSetupFailure;
            Log.e(TAG, diagnostic, t);
        }
        fgObserver = RootFanRpmObserver.start();
        // Root receives original kernel FG events independently; the old optional OEM Hook is retained.
        writer = daemon("ACEFan-root-writer", this::writerLoop);
        reporter = daemon("ACEFan-root-report", this::reportLoop);
        worker = daemon("ACEFan-root-settings", this::workerLoop);
        Log.i(TAG, "root system Context created; safety access is checked, not assumed; signals=" + signalSupport
                + "; FG=" + fgObserver.describe());
    }
    private static Thread daemon(String name, Runnable run) {
        Thread t = new Thread(run, name); t.setDaemon(true); t.start(); return t;
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static String error(Throwable t) {
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
    }
    private static void wake(Semaphore s) { if (s.availablePermits() == 0) s.release(); }
    private void invalidate(boolean config) {
        if (config) { configEpoch.incrementAndGet(); configDirty.set(true); }
        epoch.incrementAndGet(); authorization = null;
        wake(workWake); wake(writeWake);
    }

    static Sensor pocketSensor(SensorManager manager) {
        // PMS110 lists only the wake-up flavor (type 33171075). The one-argument
        // getDefaultSensor uses non-wakeup for vendor types and incorrectly returns null.
        Sensor sensor = manager.getDefaultSensor(POCKET_SENSOR, true);
        return sensor != null ? sensor : manager.getDefaultSensor(POCKET_SENSOR, false);
    }

    private void registerSources() throws Exception {
        if (Build.VERSION.SDK_INT < 31) throw new IllegalStateException("required safety callbacks need API31+");
        getCurrentUser = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser");
        // Observe ALL users; the current-user check in safety() rejects non-owner sessions.
        // External Provider and Settings reads are explicitly user0, never attributed to an App.
        Method register = ContentResolver.class.getMethod("registerContentObserver", Uri.class, boolean.class, ContentObserver.class, int.class);
        observer = new ContentObserver(events) {
            @Override public void onChange(boolean selfChange) { invalidate(true); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                // No-URI or ancestor invalidations cannot safely be attributed: reread both configs.
                boolean provider = uri == null || "dev.eta.acefan.data".equals(uri.getAuthority());
                invalidate(provider);
            }
        };
        register.invoke(resolver, CONFIG, false, observer, 0);
        register.invoke(resolver, DAILY, false, observer, 0);
        for (String key : SYSTEM_KEYS) register.invoke(resolver, Settings.System.getUriFor(key), false, observer, -1);
        for (String key : GLOBAL_KEYS) register.invoke(resolver, Settings.Global.getUriFor(key), false, observer, -1);
        power = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        audio = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        usb = (UsbManager)context.getSystemService(Context.USB_SERVICE);
        sensors = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        if (power == null || audio == null || usb == null || sensors == null) throw new IllegalStateException("required root safety service unavailable");
        thermalListener = value -> invalidate(false);
        power.addThermalStatusListener(command -> events.post(command), thermalListener); thermalListenerReady = true;
        recordingListener = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> values) { invalidate(false); }
        };
        audio.registerAudioRecordingCallback(recordingListener, events);
        modeListener = value -> invalidate(false);
        audio.addOnModeChangedListener(command -> events.post(command), modeListener); audioListenerReady = true;
        // OEM scenes/g$a.smali: type 0x1fa2683, zero=in-pocket, nonzero=out (device reports 5).
        // Require a finite real sample; absent sensor/initial/invalid samples stay UNKNOWN.
        Sensor pocketSensor = pocketSensor(sensors);
        if (pocketSensor == null) throw new IllegalStateException("OEM pocket safety sensor unavailable");
        pocketListener = new SensorEventListener() {
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
            @Override public void onSensorChanged(SensorEvent event) {
                if (event.sensor == null || event.sensor.getType() != POCKET_SENSOR) return;
                int next = KernelProtocol.pocketGate(event.values);
                if (next != pocket) { pocket = next; invalidate(false); }
            }
        };
        pocketListenerReady = sensors.registerListener(pocketListener, pocketSensor, SensorManager.SENSOR_DELAY_NORMAL, events);
        if (!pocketListenerReady) throw new IllegalStateException("root pocket callback registration rejected");
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED); filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
        receiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { invalidate(false); }};
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, events, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, null, events);
    }

    private Meta readProvider() {
        Bundle charge = providers.call(API.getAuthority(), "config", null, null);
        Bundle daily = providers.call(API.getAuthority(), DailyFanContract.READ, null, null);
        return decodeProvider(charge, daily);
    }
    private static Meta decodeProvider(Bundle charge, Bundle daily) {
        if (charge == null || daily == null) throw new IllegalStateException("Provider unavailable");
        DailyFanConfig day = DailyFanContract.decode(daily);
        long dv = DailyFanContract.version(daily, "version"), cv = DailyFanContract.version(charge, "version");
        Object enable = charge.get("enabled");
        if (!(enable instanceof Boolean)) throw new IllegalArgumentException("charge enabled unavailable");
        double[] weights = new double[]{number(charge, "batteryWeight"), number(charge, "boardWeight"),
                number(charge, "fanWeight"), number(charge, "mosWeight")};
        KernelProtocol.Settings c = new KernelProtocol.Settings((Boolean)enable, number(charge, "startTemp"),
                integer(charge, "minLevel"), weights, number(charge, "gain"), integer(charge, "ceiling"));
        double[] dw = new double[4]; for (int i = 0; i < 4; i++) dw[i] = day.weight(i);
        KernelProtocol.Settings d = new KernelProtocol.Settings(day.enabled, day.startC, day.floor, dw,
                number(charge, "gain"), integer(charge, "ceiling") < day.floor ? day.floor : integer(charge, "ceiling"));
        return new Meta(d, c, dv, cv);
    }
    private static double number(Bundle b, String key) {
        Object n = b.get(key); if (!(n instanceof Double)) throw new IllegalArgumentException("missing real parameter " + key);
        return (Double)n;
    }
    private static int integer(Bundle b, String key) {
        Object n = b.get(key); if (!(n instanceof Integer)) throw new IllegalArgumentException("missing integer parameter " + key);
        return (Integer)n;
    }
    private String system(String key) throws Exception {
        return KernelProtocol.oemSetting("system", key, providers.setting("system", key));
    }
    private String global(String key) {
        return KernelProtocol.oemSetting("global", key, providers.setting("global", key));
    }

    private KernelProtocol.Config safety(Meta meta) throws Exception {
        if (!observersReady || !thermalListenerReady || !audioListenerReady || !pocketListenerReady)
            throw new IllegalStateException("root safety listener unavailable; " + sourceSetupFailure);
        if (!Integer.valueOf(0).equals(getCurrentUser.invoke(null))) throw new IllegalStateException("foreground user not owner/user0");
        String main = system(SYSTEM_KEYS[0]), ui = system(SYSTEM_KEYS[1]), mode = system(SYSTEM_KEYS[2]), auto = system(SYSTEM_KEYS[3]);
        String charge = system(SYSTEM_KEYS[4]), protection = system(SYSTEM_KEYS[5]), micSwitch = system(SYSTEM_KEYS[6]);
        String rider = system(SYSTEM_KEYS[7]), saving = system(SYSTEM_KEYS[8]), performance = system(SYSTEM_KEYS[9]);
        String abort = global(GLOBAL_KEYS[0]), mic = global(GLOBAL_KEYS[1]), game = global(GLOBAL_KEYS[2]);
        int mask = 0;
        if (KernelProtocol.uiClear(ui) && KernelProtocol.yesNo(charge) != 0) mask |= 1;
        if (KernelProtocol.zero(protection)) mask |= 2;
        if (KernelProtocol.falseText(abort)) mask |= 4;
        List<AudioRecordingConfiguration> recordings = audio.getActiveRecordingConfigurations();
        if (KernelProtocol.yesNo(micSwitch) != 0 && KernelProtocol.zero(mic) && audio.getMode() == AudioManager.MODE_NORMAL
                && recordings != null && recordings.isEmpty()) mask |= 8;
        Map<String, UsbDevice> devices = usb.getDeviceList();
        if (devices == null) throw new IllegalStateException("USB list unavailable");
        boolean handleClear = true;
        for (UsbDevice d : devices.values()) {
            if (d == null) throw new IllegalStateException("USB entry unavailable");
            if (d.getVendorId() == 8921 && d.getProductId() == 14443) handleClear = false;
        }
        if (handleClear) mask |= 16;
        KernelReport current = status;
        if (current != null && KernelProtocol.fresh(statusAt, now(), 7000) && current.get("fault") == 0
                && current.get("live") == 1 && "1".equals(read(new File(files.device, "present"), 64))
                && "NORMAL".equals(read(new File(files.device, "status"), 128))) mask |= 32;
        if (pocket == 1 && KernelProtocol.zero(game) && KernelProtocol.zero(rider) && KernelProtocol.zero(saving)
                && KernelProtocol.performanceClear(performance)) mask |= 64;
        int thermal = power.getCurrentThermalStatus();
        KernelProtocol.Gates gates = new KernelProtocol.Gates(KernelProtocol.mainEnabled(main), KernelProtocol.mode(mode),
                KernelProtocol.yesNo(auto), mask, thermal);
        diagnostic = "root_context=system;gates=" + mask + ";thermal=" + thermal + ";pocket=" + pocket
                + ";signals=" + signalSupport + ";fg=" + (fgObserver == null ? "not_started" : fgObserver.describe());
        // OEM charging toggle limits the charge route only; it never rewrites the saved App setting.
        return new KernelProtocol.Config(meta.daily, meta.charge.withEnabled(meta.charge.enabled && "1".equals(charge)), gates, KernelProtocol.ZONES);
    }

    private void workerLoop() {
        Meta meta = null;
        while (!stopping.get()) {
            try {
                long e = epoch.get();
                if (configDirty.getAndSet(false) || meta == null) {
                    authorization = null;
                    long ce = configEpoch.get();
                    meta = null; // A failed replacement read must not authorize or reuse the old config.
                    meta = readProvider(); // Can block; writer never waits on this IPC or keeps renewing indefinitely.
                    if (ce != configEpoch.get()) { configDirty.set(true); meta = null; continue; }
                }
                KernelProtocol.Config c = safety(meta);
                if (e == epoch.get()) authorization = new Authorization(c, meta, e, now());
                else authorization = null;
                wake(writeWake);
            } catch (Throwable t) {
                authorization = null;
                String failure = "source_unavailable:" + error(t);
                if (!failure.equals(diagnostic)) Log.w(TAG, failure);
                diagnostic = failure;
                if (meta == null) configDirty.set(true);
                else authorization = new Authorization(new KernelProtocol.Config(meta.daily, meta.charge,
                        new KernelProtocol.Gates(0, 0, 0, 0, -1), KernelProtocol.ZONES), meta, epoch.get(), now());
                wake(writeWake);
            }
            try { workWake.tryAcquire(2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        }
    }
    private boolean freshAuthorization(Authorization a) {
        long n = now();
        return !stopping.get() && a != null && a == authorization && a.epoch == epoch.get()
                && KernelProtocol.writerFresh(a.at, eventsAt, statusAt, publisherAt, n);
    }
    private void writerLoop() {
        KernelProtocol.Config previous = null;
        Meta meta = null;
        boolean disabledFirst = false;
        String lastLine = null;
        long lastWriteAt = 0;
        try {
            while (true) {
                Authorization a = authorization;
                if (a != null) { previous = a.config; meta = a.meta; }
                if (previous != null) {
                    boolean enable = disabledFirst && freshAuthorization(a) && previous.allowed();
                    String line = lease.prepare(previous, enable, revisions::save);
                    // Disk I/O could stall. Recheck immediately before every enabled write.
                    if (enable && !freshAuthorization(a)) continue;
                    if (enable && line.equals(lastLine) && now() - lastWriteAt < KernelProtocol.WRITE_INTERVAL_MS) {
                        try { writeWake.tryAcquire(KernelProtocol.WRITE_INTERVAL_MS - (now() - lastWriteAt), TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                        continue;
                    }
                    if (enable || !line.equals(lastLine) || stopping.get()) {
                        if (!write(line, enable ? a : null)) continue;
                        lastLine = line; lastWriteAt = now();
                    }
                    // A Provider version may change without changing any wire parameter. The
                    // byte-identical, already-committed kernel config still acknowledges that read.
                    if (line.equals(lastLine)) sent = new Sent(lease.revision(), previous, meta);
                    disabledFirst = true; // initial complete read always sends disabled before any effective cfg
                }
                if (stopping.get()) break;
                try { writeWake.tryAcquire(KernelProtocol.WRITE_INTERVAL_MS, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
        } catch (Throwable t) {
            diagnostic = "writer_failed_no_more_leases:" + error(t); authorization = null;
            Log.e(TAG, diagnostic, t); // No uncertain-write retry. The kernel lease is the final fail-safe.
        } finally { closeWritingFd(); }
    }
    private boolean write(String line, Authorization required) throws Exception {
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 1024) throw new IOException("oversized config");
        FileDescriptor fd = Os.open(files.config.getPath(), OsConstants.O_WRONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        writingFd = fd;
        try {
            // Even opening a node may stall. No enabled write is issued on an authorization
            // that expired while waiting for the FD; disabled writes never require fresh sources.
            if (required != null && !freshAuthorization(required)) return false;
            int count = Os.write(fd, bytes, 0, bytes.length); // exactly ONE sysfs write, never stream/split or subprocess
            if (count != bytes.length) throw new IOException("short atomic config write");
            return true;
        } finally { writingFd = null; Os.close(fd); }
    }
    private void closeWritingFd() {
        FileDescriptor fd = writingFd; writingFd = null;
        if (fd != null) try { Os.close(fd); } catch (Throwable ignored) { }
    }

    private void reportLoop() {
        while (!stopping.get()) {
            long start = now();
            try {
                KernelReport r = KernelReport.parse(read(files.status, 8192));
                status = r; statusAt = now();
                Sent s = sent;
                Map<String, Object> fields = r.fields(System.currentTimeMillis(), now(), Telemetry.getBootId(), BuildInfo.HOOK_VERSION,
                        s == null ? 0 : s.revision, s == null ? null : s.config, s == null ? -1 : s.meta.dailyVersion,
                        s == null ? -1 : s.meta.chargeVersion, diagnostic,
                        read(new File(files.device, "status"), 128));
                JSONObject json = new JSONObject(fields);
                // Debug override is genuinely read; target RPM / rpm / speed caches are never consulted.
                Telemetry.addKernelFanRpm(json, r.get("physicalState"), r.get("effective"), (String)fields.get("fanStatus"));
                Bundle b = new Bundle(); b.putString("json", json.toString());
                Bundle ack = providers.call(API.getAuthority(), "pushRoot", null, b);
                if (ack == null || !ack.getBoolean("ok", false)) throw new IOException("root publication not acknowledged");
                publisherAt = now(); wake(workWake);
            } catch (Throwable t) {
                statusAt = 0; publisherAt = 0; invalidate(false);
                String failure = "status_or_provider_unavailable:" + error(t);
                if (!failure.equals(diagnostic)) Log.w(TAG, failure);
                diagnostic = failure;
            }
            long wait = Math.max(1, 5000 - (now() - start));
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
        }
    }

    /** Best-effort TERM/INT bridge. Android may not ship sun.misc.Signal; don't claim otherwise. */
    private void installSignals() {
        try {
            Class<?> signal = Class.forName("sun.misc.Signal"), handler = Class.forName("sun.misc.SignalHandler");
            Object callback = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, (proxy, method, args) -> {
                if ("handle".equals(method.getName())) daemon("ACEFan-root-stop", () -> { shutdown(); System.exit(0); });
                return null;
            });
            Method handle = signal.getMethod("handle", signal, handler);
            for (String name : new String[]{"TERM", "INT"}) handle.invoke(null, signal.getConstructor(String.class).newInstance(name), callback);
            signalSupport = "TERM_INT_registered";
        } catch (Throwable t) { signalSupport = "unavailable_kernel_lease_only"; }
        // SIGKILL and sudden death cannot run any shutdown hook; the kernel lease still expires.
    }
    private void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        if (fgObserver != null) fgObserver.close();
        authorization = null; wake(writeWake); wake(workWake);
        if (writer != null && writer != Thread.currentThread()) try { writer.join(2500); } catch (InterruptedException ignored) { }
        closeWritingFd();
        // Do not wait on blocked Provider/framework IPC in a shutdown hook.
        // A stuck writer must retain its process lock until death, not overlap a replacement relay.
        if (writer == null || !writer.isAlive()) revisions.close();
    }

    private static final class PolicyFiles {
        final File device, config, snapshot, status;
        private PolicyFiles(File device) {
            this.device = device;
            File p = new File(device, "eta_policy"); config = new File(p, "config"); snapshot = new File(p, "config_snapshot"); status = new File(p, "status");
        }
        static PolicyFiles discover() throws Exception {
            File[] leds = new File("/sys/class/leds").listFiles();
            if (leds == null || leds.length > 64) throw new IOException("LED inventory unavailable/bounded out");
            PolicyFiles found = null;
            for (File led : leds) {
                File real = led.getCanonicalFile();
                if (!("/sys" + FanRpm.DEVPATH).equals(real.getPath())) continue;
                PolicyFiles candidate = new PolicyFiles(real);
                node(candidate.config, 0200); node(candidate.snapshot, 0400); node(candidate.status, 0400);
                KernelReport.parse(read(candidate.status, 8192));
                if (found != null) throw new IOException("ambiguous eta_policy device");
                found = candidate;
            }
            if (found == null) throw new IOException("validated eta_policy on original LED device unavailable");
            return found;
        }
        static void node(File path, int permissions) throws Exception {
            StructStat st = Os.lstat(path.getPath());
            if (st.st_uid != 0 || !OsConstants.S_ISREG(st.st_mode) || (st.st_mode & 0777) != permissions)
                throw new SecurityException("unexpected root node identity/mode: " + path);
        }
    }
    static String read(File file, int max) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[Math.min(1024, max + 1)]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > max) throw new IOException("bounded read exceeded: " + file.getName());
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    /** Root-only, fsync + atomic rename revision journal. App restarts cannot reset this counter.
     * A process-held lock also excludes accidentally starting two root writers. */
    private static final class RevisionStore {
        private final File directory = new File("/data/adb/eta_acefan");
        private final File revision = new File(directory, "kernel-settings.revision");
        private final RandomAccessFile lockFile;
        private final FileLock lock;
        RevisionStore() throws Exception {
            if (!directory.exists()) Os.mkdir(directory.getPath(), 0700);
            StructStat d = Os.lstat(directory.getPath());
            if (d.st_uid != 0 || !OsConstants.S_ISDIR(d.st_mode) || (d.st_mode & 0022) != 0) throw new SecurityException("unsafe root relay directory");
            File path = new File(directory, "kernel-settings.lock");
            if (path.exists()) secure(path);
            FileDescriptor fd = Os.open(path.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
            Os.close(fd); secure(path);
            lockFile = new RandomAccessFile(path, "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) { lockFile.close(); throw new IOException("another kernel relay owns the root writer lock"); }
        }
        private static void secure(File path) throws Exception {
            StructStat s = Os.lstat(path.getPath());
            if (s.st_uid != 0 || !OsConstants.S_ISREG(s.st_mode) || (s.st_mode & 0777) != 0600) throw new SecurityException("unsafe root journal file");
        }
        long read() throws Exception {
            if (!revision.exists()) return 0;
            secure(revision); return KernelProtocol.uint(KernelSettingsRelay.read(revision, 32));
        }
        void save(long n) throws Exception {
            File temp = new File(directory, "kernel-settings.revision.tmp." + Process.myPid());
            byte[] data = (Long.toString(n) + "\n").getBytes(StandardCharsets.US_ASCII);
            FileDescriptor fd = Os.open(temp.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
            try {
                if (Os.write(fd, data, 0, data.length) != data.length) throw new IOException("short revision journal write");
                Os.fsync(fd);
            } finally { Os.close(fd); }
            Os.rename(temp.getPath(), revision.getPath());
            FileDescriptor dir = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(dir).st_mode)) throw new SecurityException("journal parent not a directory");
                Os.fsync(dir);
            } finally { Os.close(dir); }
        }
        void close() { try { lock.release(); lockFile.close(); } catch (Throwable ignored) { } }
    }
}
