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
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit opt-in, bounded registration test. No relay construction, policy files,
 * temperatures, settings/report writes or revision access. Every attempted registration has
 * a finally cleanup, including partial registration failure. Does not authorize fan control. */
final class RootListenerProbe {
    private interface Action { void run() throws Exception; }
    private static Map<String, Object> attempt(Action action) {
        Map<String, Object> result = new LinkedHashMap<>();
        try { action.run(); result.put("ok", true); }
        catch (Throwable t) {
            while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
            result.put("ok", false); result.put("error", t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return result;
    }

    static Map<String, Object> run(Context context, int pocketType, String[] systemKeys, String[] globalKeys) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("UID0 required");
        HandlerThread thread = new HandlerThread("ACEFan-readonly-listener-probe"); thread.start();
        Handler events = new Handler(thread.getLooper()); ContentResolver resolver = context.getContentResolver();
        Map<String, Object> out = new LinkedHashMap<>(), checks = new LinkedHashMap<>(), cleanup = new LinkedHashMap<>();
        out.put("probe", "root_listeners_readonly"); out.put("uid", Process.myUid());
        out.put("sysfsAccess", false); out.put("revisionAccess", false); out.put("settingsWrites", false); out.put("reportWrites", false);
        ContentObserver observer = new ContentObserver(events) { };
        PowerManager power = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        AudioManager audio = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        SensorManager sensors = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        PowerManager.OnThermalStatusChangedListener thermal = value -> { };
        AudioManager.AudioRecordingCallback recording = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> values) { }
        };
        AudioManager.OnModeChangedListener mode = value -> { };
        AtomicInteger pocket = new AtomicInteger(-1), pocketCount = new AtomicInteger();
        AtomicReference<String> pocketRaw = new AtomicReference<>();
        CountDownLatch pocketEvent = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
            @Override public void onSensorChanged(SensorEvent event) {
                if (event.sensor == null || event.sensor.getType() != pocketType) return;
                pocketCount.incrementAndGet();
                pocketRaw.set(event.values != null && event.values.length > 0 ? Float.toString(event.values[0]) : null);
                pocket.set(KernelProtocol.pocketGate(event.values));
                pocketEvent.countDown();
            }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { } };
        try {
            checks.put("contentObservers", attempt(() -> {
                Method register = ContentResolver.class.getMethod("registerContentObserver", Uri.class, boolean.class, ContentObserver.class, int.class);
                List<Uri> owner = new ArrayList<>(); owner.add(Uri.parse("content://dev.eta.acefan.data/config"));
                owner.add(Uri.parse("content://dev.eta.acefan.data/" + DailyFanContract.PATH));
                for (Uri uri : owner) register.invoke(resolver, uri, false, observer, 0);
                for (String key : systemKeys) register.invoke(resolver, Settings.System.getUriFor(key), false, observer, -1);
                for (String key : globalKeys) register.invoke(resolver, Settings.Global.getUriFor(key), false, observer, -1);
                out.put("observerUriCount", owner.size() + systemKeys.length + globalKeys.length);
            }));
            checks.put("thermal", attempt(() -> power.addThermalStatusListener(command -> events.post(command), thermal)));
            checks.put("audioRecording", attempt(() -> audio.registerAudioRecordingCallback(recording, events)));
            checks.put("audioMode", attempt(() -> audio.addOnModeChangedListener(command -> events.post(command), mode)));
            checks.put("pocket", attempt(() -> {
                Sensor sensor = KernelSettingsRelay.pocketSensor(sensors);
                if (sensor == null) throw new IllegalStateException("OEM pocket sensor unavailable");
                out.put("pocketWakeUp", sensor.isWakeUpSensor()); out.put("pocketName", sensor.getName());
                if (!sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, events))
                    throw new IllegalStateException("pocket registration rejected");
            }));
            checks.put("broadcast", attempt(() -> {
                IntentFilter filter = new IntentFilter(); filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); filter.addAction(Intent.ACTION_USER_UNLOCKED);
                filter.addAction("android.intent.action.USER_SWITCHED"); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
                context.registerReceiver(receiver, filter, null, events, Context.RECEIVER_EXPORTED);
            }));
            pocketEvent.await(1500, TimeUnit.MILLISECONDS);
            out.put("pocketSample", pocket.get()); out.put("pocketEventCount", pocketCount.get()); out.put("pocketRawValue", pocketRaw.get());
            out.put("gate", pocket.get() == 1 ? "observed_clear_not_authorized" : "unknown_or_blocked_fail_closed");
        } finally {
            cleanup.put("contentObservers", attempt(() -> resolver.unregisterContentObserver(observer)));
            cleanup.put("thermal", attempt(() -> power.removeThermalStatusListener(thermal)));
            cleanup.put("audioRecording", attempt(() -> audio.unregisterAudioRecordingCallback(recording)));
            cleanup.put("audioMode", attempt(() -> audio.removeOnModeChangedListener(mode)));
            cleanup.put("pocket", attempt(() -> sensors.unregisterListener(listener)));
            // Failed registerReceiver has no registration to remove.
            Map<?, ?> broadcast = (Map<?, ?>)checks.get("broadcast");
            if (broadcast != null && Boolean.TRUE.equals(broadcast.get("ok")))
                cleanup.put("broadcast", attempt(() -> context.unregisterReceiver(receiver)));
            thread.quitSafely(); thread.join(1000); out.put("eventThreadStopped", !thread.isAlive());
        }
        boolean ok = !thread.isAlive();
        for (Object result : checks.values()) ok &= Boolean.TRUE.equals(((Map<?, ?>)result).get("ok"));
        for (Object result : cleanup.values()) ok &= Boolean.TRUE.equals(((Map<?, ?>)result).get("ok"));
        out.put("registration", checks); out.put("cleanup", cleanup); out.put("ok", ok);
        return out;
    }
}
