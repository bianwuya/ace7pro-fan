package dev.eta.acefan;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Synchronous UI adapter. Call load/saveDaily/saveCharge on IO. The preview computes from the snapshot without I/O. */
public final class FrontModel {
    private static final Uri BASE = Uri.parse("content://" + DataProvider.AUTHORITY);
    private static final int MAX_ROWS = 4000; // Also enforced by the Provider.

    private FrontModel() { }

    public static final class ChargeConfig {
        public final boolean valid, enabled;
        public final long version; // -1 if unavailable
        public final double startTemp; // NaN if unavailable

        private ChargeConfig(boolean valid, boolean enabled, long version, double startTemp) {
            this.valid = valid; this.enabled = enabled; this.version = version; this.startTemp = startTemp;
        }
    }

    public static final class Snapshot {
        public final ChargeConfig charge;
        public final DailyFanConfig dailyConfig; // Closed engineering default if dailyValid is false; not a saved row.
        public final boolean dailyValid, historyAvailable;
        public final long dailyVersion; // Saved version, -1 if unavailable.
        public final long wallTimeMillis;
        public final FrontPresentation.Live live; // Typed, read-only; no diagnostic payload.
        private final String boot;
        private final List<FrontPresentation.TrendPoint> history;

        private Snapshot(ChargeConfig charge, DailyRow daily, boolean historyAvailable,
                long wallTimeMillis, String boot, FrontPresentation.Live live,
                List<FrontPresentation.TrendPoint> history) {
            this.charge = charge; dailyConfig = daily.config; dailyVersion = daily.version;
            dailyValid = daily.version >= 0; this.historyAvailable = historyAvailable;
            this.wallTimeMillis = wallTimeMillis; this.boot = boot; this.live = live; this.history = history;
        }

        /** Immutable, chronological slice of the one-hour query; supports exactly 15, 30 or 60 minutes. No I/O. */
        public List<FrontPresentation.TrendPoint> trend(int minutes) {
            return FrontPresentation.trendWindow(history, wallTimeMillis, minutes);
        }
    }

    public enum SaveStatus {
        SAVED("saved"), VERSION_CONFLICT("version_conflict"), UNAVAILABLE("unavailable");
        public final String code;
        SaveStatus(String code) { this.code = code; }
    }

    public static final class DailySaveResult {
        public final SaveStatus status;
        public final DailyFanConfig config; // Actual persisted row on success/conflict, null if unavailable.
        public final long version; // Actual persisted version on success/conflict, -1 if unavailable.

        private DailySaveResult(SaveStatus status, DailyFanConfig config, long version) {
            this.status = status; this.config = config; this.version = version;
        }
    }

    /** Reads independent config/telemetry sources. Fallback sampling is read-only; it never calls push. */
    public static Snapshot load(Context context) { return load(context, true); }

    /** A live poll only needs a short recent history to find the latest Hook record. */
    public static Snapshot load(Context context, boolean includeHistory) {
        ContentResolver resolver = resolver(context);
        ChargeConfig charge = readCharge(call(resolver, "config", null));
        DailyRow daily = readDaily(call(resolver, DailyFanContract.READ, null));
        FrontPresentation.Reading latest = latest(call(resolver, "latest", null));
        History history = history(resolver, System.currentTimeMillis(), includeHistory);
        String boot = Boot.ID;
        FrontPresentation.Reading hook = latestHook(latest, history.rows);
        FrontPresentation.Reading stored = newest(latest, history.rows);
        FrontPresentation.Power power = livePower(context);
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        FrontPresentation.Reading local = null;
        if (FrontPresentation.displayReading(hook, stored, null, power, wall, elapsed, boot) == null) {
            try { local = parse(Telemetry.sample(context), FrontPresentation.Source.APP_LOCAL); }
            catch (RuntimeException ignored) { /* Missing data stays unknown. */ }
            power = livePower(context);
            wall = System.currentTimeMillis(); elapsed = SystemClock.elapsedRealtime();
        }
        FrontPresentation.Live live = FrontPresentation.live(hook, stored, local, power, wall, elapsed, boot,
                BuildInfo.HOOK_VERSION, daily.version, charge.version);
        return new Snapshot(charge, daily, history.available, wall, boot, live,
                includeHistory ? FrontPresentation.trend(history.rows, wall) : java.util.Collections.emptyList());
    }

    /** Uses the draft's weights against the same snapshot, rechecking its age at the time of preview. No writes. */
    public static FrontPresentation.Preview preview(DailyFanConfig config, Snapshot snapshot) {
        return FrontPresentation.preview(config, snapshot == null ? null : snapshot.live.reading,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), snapshot == null ? "" : snapshot.boot);
    }

    /** Sends one Provider CAS. Never updates from a guessed row or retries a conflict. */
    public static DailySaveResult saveDaily(Context context, DailyFanConfig config, long expectedVersion) {
        if (config == null || expectedVersion < 0 || expectedVersion >= Long.MAX_VALUE - 1) return unavailable();
        Bundle extras = DailyFanContract.encode(config, expectedVersion);
        extras.remove("version"); // Write contract takes expectedVersion, not a caller-assigned version.
        extras.remove("proportions"); // Derived on readback, not persisted input.
        extras.putLong("expectedVersion", expectedVersion);
        Bundle reply = call(resolver(context), DailyFanContract.WRITE, extras);
        try {
            if (reply == null || !(reply.get("ok") instanceof Boolean)) return unavailable();
            boolean ok = reply.getBoolean("ok");
            if (!ok && !"version_conflict".equals(reply.getString("error"))) return unavailable();
            DailyRow row = readDaily(reply);
            if (row.version < 0 || (ok && (row.version != expectedVersion + 1 || !config.sameSettings(row.config))))
                return unavailable();
            return new DailySaveResult(ok ? SaveStatus.SAVED : SaveStatus.VERSION_CONFLICT, row.config, row.version);
        } catch (RuntimeException ignored) { return unavailable(); }
    }

    /** Legacy setConfig: writes only enabled, preserving charge threshold and Provider versioning. */
    public static ChargeConfig saveCharge(Context context, boolean enabled) {
        Bundle extras = new Bundle();
        extras.putBoolean("enabled", enabled);
        return readCharge(call(resolver(context), "setConfig", extras));
    }

    private static ContentResolver resolver(Context context) {
        try { return context.getContentResolver(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Bundle call(ContentResolver resolver, String method, Bundle extras) {
        if (resolver == null) return null;
        try { return resolver.call(BASE, method, null, extras); }
        catch (RuntimeException ignored) { return null; }
    }

    private static ChargeConfig readCharge(Bundle reply) {
        try {
            if (reply != null && reply.get("enabled") instanceof Boolean && reply.get("version") instanceof Long) {
                long version = reply.getLong("version");
                if (version >= 0) {
                    double start = reply.getDouble("startTemp", Double.NaN);
                    return new ChargeConfig(true, reply.getBoolean("enabled"), version,
                            start >= 20.0 && start <= 50.0 ? start : Double.NaN);
                }
            }
        } catch (RuntimeException ignored) { /* No trusted Provider row. */ }
        return new ChargeConfig(false, false, -1L, Double.NaN);
    }

    private static final class DailyRow {
        final DailyFanConfig config;
        final long version;
        DailyRow(DailyFanConfig config, long version) { this.config = config; this.version = version; }
    }

    private static DailyRow readDaily(Bundle reply) {
        try {
            if (reply != null) return new DailyRow(DailyFanContract.decode(reply), DailyFanContract.version(reply, "version"));
        } catch (RuntimeException ignored) { /* Never promote the fallback into a saved row. */ }
        return new DailyRow(DailyFanConfig.DEFAULT, -1L);
    }

    private static DailySaveResult unavailable() { return new DailySaveResult(SaveStatus.UNAVAILABLE, null, -1L); }

    private static final class History {
        final boolean available;
        final List<FrontPresentation.Reading> rows;
        History(boolean available, List<FrontPresentation.Reading> rows) { this.available = available; this.rows = rows; }
    }

    private static History history(ContentResolver resolver, long wall, boolean includeHistory) {
        ArrayList<FrontPresentation.Reading> rows = new ArrayList<>();
        if (resolver == null) return new History(false, rows);
        Uri uri = DataProvider.CONTENT_URI.buildUpon()
                .appendQueryParameter("since", Long.toString(Math.max(1L, includeHistory ? FrontPresentation.trendStart(wall, 60) : wall - 75_000L)))
                .build();
        try (Cursor cursor = resolver.query(uri, new String[]{"json"}, null, null, null)) {
            if (cursor == null) return new History(false, rows);
            while (rows.size() < MAX_ROWS && cursor.moveToNext()) rows.add(parse(cursor.getString(0)));
            return new History(true, rows);
        } catch (RuntimeException ignored) {
            rows.clear();
            return new History(false, rows);
        }
    }

    private static FrontPresentation.Reading latest(Bundle reply) {
        try { return reply == null ? null : parse(reply.getString("json")); }
        catch (RuntimeException ignored) { return null; }
    }

    private static FrontPresentation.Reading newest(FrontPresentation.Reading latest, List<FrontPresentation.Reading> rows) {
        return newest(latest, rows, false);
    }

    private static FrontPresentation.Reading latestHook(FrontPresentation.Reading latest, List<FrontPresentation.Reading> rows) {
        return newest(latest != null && latest.source == FrontPresentation.Source.HOOK ? latest : null, rows, true);
    }

    private static FrontPresentation.Reading newest(FrontPresentation.Reading latest,
            List<FrontPresentation.Reading> rows, boolean hookOnly) {
        // Provider's current UID0 publisher takes precedence over a skewed wall clock on an
        // older legacy Hook row. Once root publication expires, normal history selection resumes.
        if (FrontPresentation.freshRoot(latest, System.currentTimeMillis(), SystemClock.elapsedRealtime(), Boot.ID)) return latest;
        for (int i = rows.size() - 1; i >= 0; i--) {
            FrontPresentation.Reading row = rows.get(i);
            if (row != null && (!hookOnly || row.source == FrontPresentation.Source.HOOK))
                return latest == null || row.wall >= latest.wall ? row : latest;
        }
        return latest;
    }

    private static FrontPresentation.Reading parse(String json) {
        if (json != null) try { return parse(new JSONObject(json), null); }
        catch (Exception ignored) { /* Keep malformed rows as line breaks rather than synthesizing zeros. */ }
        return null;
    }

    private static FrontPresentation.Reading parse(JSONObject json, FrontPresentation.Source source) {
        return json == null ? null : FrontPresentation.decode(fields(json), source);
    }

    private static FrontPresentation.Fields fields(JSONObject json) {
        return key -> {
            Object value = json.opt(key);
            return value instanceof JSONObject ? fields((JSONObject) value) : value;
        };
    }

    private static FrontPresentation.Power livePower(Context context) {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                boolean wired = (plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) == 0
                        && (plugged & (BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
                boolean charging = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                        == BatteryManager.BATTERY_STATUS_CHARGING;
                return FrontPresentation.power(plugged, wired, charging, Telemetry.explicitlyDischarging(battery));
            }
        } catch (RuntimeException ignored) { /* Unknown power never enables a route. */ }
        return FrontPresentation.Power.UNKNOWN;
    }

    private static final class Boot {
        static final String ID = readId();
        private static String readId() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
                char[] buffer = new char[64];
                int n = reader.read(buffer);
                if (n > 0) return new String(buffer, 0, n).trim();
            } catch (IOException | RuntimeException ignored) { /* ReportFreshness still checks both clocks. */ }
            return "";
        }
    }
}
