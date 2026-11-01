package dev.eta.acefan;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only, bounded telemetry sampler. Never escalates privileges or changes device permissions. */
public final class Telemetry {
    private Telemetry() { }

    private static final Object LOCK = new Object();
    private static final int MAX_ZONES = 256;
    private static final long DISCOVERY_RETRY_MS = 60_000L;
    private static final String THERMAL_ROOT = "/sys/class/thermal";
    private static final String FAN_ROOT = "/sys/class/leds/fan/";
    private static final String[] TYPES = {"battery", "board_temp", "fan_temp", "svooc_mos_btb_usr"};
    private static final String[] FAN_FILES = {"rpm", "level", "level_eff", "state", "status", "speed"};

    private static final Map<String, String> thermalPath = new HashMap<String, String>();
    private static final Map<String, Boolean> thermalFound = new HashMap<String, Boolean>();
    private static long nextThermalDiscovery;
    private static String bootId;
    private static boolean bootResolved;

    public static JSONObject sample(Context context) {
        JSONObject out = new JSONObject();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        put(out, "wall", wall);
        put(out, "elapsed", elapsed);
        put(out, "boot", getBootId());

        Intent batteryIntent = null;
        try {
            if (context != null) batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) { }
        discoverThermalsIfDue(SystemClock.elapsedRealtime());
        addBattery(out, batteryIntent);

        try {
            PowerManager pm = context == null ? null : (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) put(out, "interactive", pm.isInteractive());
            else putNull(out, "interactive");
        } catch (Exception e) { putNull(out, "interactive"); }

        putNullable(out, "board", readThermalC("board_temp"));
        putNullable(out, "fan", readThermalC("fan_temp"));
        putNullable(out, "mos", readThermalC("svooc_mos_btb_usr"));
        addFan(out);
        return out;
    }

    private static void addBattery(JSONObject out, Intent intent) {
        int status = intent == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = intent == null ? 0 : intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        put(out, "plugged", plugged);
        put(out, "status", status);
        put(out, "charging", status == BatteryManager.BATTERY_STATUS_CHARGING);
        // A wireless bit excludes wired classification even if a platform reports combined bits.
        boolean wireless = (plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0;
        boolean wired = !wireless && (plugged & (BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
        put(out, "wired", wired);

        Integer stickyTenths = null;
        if (intent != null) {
            int t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) stickyTenths = t;
        }
        Integer sysfsTenths = readInt("/sys/class/power_supply/battery/temp");
        boolean sysfsValid = validCelsiusTenths(sysfsTenths);
        boolean stickyValid = validCelsiusTenths(stickyTenths);

        // Thermal zone with the exact "battery" type is primary; fixed power-supply temp is fallback.
        Double thermalBattery = readThermalC("battery");
        boolean thermalValid = validCelsius(thermalBattery);
        Double selected = thermalValid ? thermalBattery : (sysfsValid ? sysfsTenths / 10.0 : (stickyValid ? stickyTenths / 10.0 : null));
        String source = thermalValid ? "thermal:battery" : (sysfsValid ? "sysfs:battery/temp" : (stickyValid ? "battery_sticky" : "unavailable"));
        putNullable(out, "battery", selected);
        put(out, "batteryValid", selected != null);
        put(out, "batterySource", source);
        // This is the time this sampler read the value; a sticky broadcast is not a measurement timestamp.
        put(out, "quality", selected == null ? "unavailable" : ("battery_sticky".equals(source) ? "sticky_read_time_not_measurement_time" : "read_time_not_measurement_time"));
    }

    private static boolean validCelsiusTenths(Integer value) {
        if (value == null) return false;
        double c = value / 10.0;
        return c >= -20.0 && c <= 100.0;
    }

    private static boolean validCelsius(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite() && value >= -20.0 && value <= 100.0;
    }

    private static Double readThermalC(String exactType) {
        String path;
        synchronized (LOCK) { path = thermalPath.get(exactType); }
        if (path == null) return null;
        Integer raw = readInt(path);
        if (raw == null) {
            synchronized (LOCK) {
                thermalPath.remove(exactType);
                thermalFound.put(exactType, Boolean.FALSE);
                nextThermalDiscovery = SystemClock.elapsedRealtime() + DISCOVERY_RETRY_MS;
            }
            return null;
        }
        double celsius = raw / 1000.0;
        return validCelsius(celsius) ? celsius : null;
    }

    private static void discoverThermalsIfDue(long now) {
        synchronized (LOCK) {
            if (now < nextThermalDiscovery) return;
            nextThermalDiscovery = now + DISCOVERY_RETRY_MS;
            for (String type : TYPES) {
                if (thermalPath.containsKey(type)) continue;
                thermalFound.put(type, Boolean.FALSE);
            }
            File[] all = new File(THERMAL_ROOT).listFiles();
            if (all == null) return;
            int scanned = 0;
            for (File zone : all) {
                if (!zone.getName().startsWith("thermal_zone")) continue;
                if (++scanned > MAX_ZONES) break;
                String type = readText(new File(zone, "type").getPath());
                if (type == null) continue;
                for (String expected : TYPES) {
                    if (expected.equals(type.trim())) {
                        thermalPath.put(expected, new File(zone, "temp").getPath());
                        thermalFound.put(expected, Boolean.TRUE);
                    }
                }
            }
        }
    }

    private static void addFan(JSONObject out) {
        putNullable(out, "rpm", readLong(FAN_ROOT + "rpm"));
        putNullable(out, "driver", readLong(FAN_ROOT + "level"));
        putNullable(out, "effective", readLong(FAN_ROOT + "level_eff"));
        putNullable(out, "state", readLong(FAN_ROOT + "state"));
        putNullable(out, "fanStatus", readText(FAN_ROOT + "status"));
        putNullable(out, "rawSpeed", readLong(FAN_ROOT + "speed"));
    }

    private static String getBootId() {
        synchronized (LOCK) {
            if (!bootResolved) {
                String value = readText("/proc/sys/kernel/random/boot_id");
                bootId = value == null || value.length() == 0
                        ? Build.FINGERPRINT + "-" + UUID.randomUUID().toString() : value;
                bootResolved = true;
            }
            return bootId;
        }
    }

    private static Integer readInt(String path) {
        String value = readText(path);
        if (value == null) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException e) { return null; }
    }

    private static Long readLong(String path) {
        String value = readText(path);
        if (value == null) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException e) { return null; }
    }

    private static String readText(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            char[] chars = new char[128];
            int count = reader.read(chars, 0, chars.length);
            return count <= 0 ? null : new String(chars, 0, count).trim();
        } catch (IOException e) { return null; }
        catch (SecurityException e) { return null; }
        finally { if (reader != null) try { reader.close(); } catch (IOException ignored) { } }
    }

    private static void putNullable(JSONObject out, String key, Object value) {
        if (value == null) putNull(out, key); else put(out, key, value);
    }
    private static void putNull(JSONObject out, String key) {
        try { out.put(key, JSONObject.NULL); } catch (JSONException ignored) { }
    }
    private static void put(JSONObject out, String key, Object value) {
        try { out.put(key, value); } catch (JSONException ignored) { }
    }
}
