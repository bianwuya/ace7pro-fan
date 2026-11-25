package dev.eta.acefan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.database.SQLException;
import android.content.ContentUris;
import android.database.MatrixCursor;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private, UID-checked telemetry/configuration provider. */
public final class DataProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.eta.acefan.data";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/samples");
    public static final Uri DAILY_CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/" + DailyFanContract.PATH);
    private static final String DB_NAME = "acefan-telemetry.db";
    private static final int DB_VERSION = 3;
    private static final String CREATE_DAILY = "CREATE TABLE daily_config (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL CHECK(enabled IN(0,1)), version INTEGER NOT NULL CHECK(version>=0), start_temp REAL NOT NULL CHECK(start_temp BETWEEN 20 AND 50), min_level INTEGER NOT NULL CHECK(min_level BETWEEN 1 AND 20), w_battery REAL NOT NULL CHECK(w_battery BETWEEN 0 AND 100), w_board REAL NOT NULL CHECK(w_board BETWEEN 0 AND 100), w_fan REAL NOT NULL CHECK(w_fan BETWEEN 0 AND 100), w_mos REAL NOT NULL CHECK(w_mos BETWEEN 0 AND 100), CHECK(w_battery+w_board+w_fan+w_mos>0))";
    private static final String SELECT_DAILY = "SELECT enabled,version,start_temp,min_level,w_battery,w_board,w_fan,w_mos FROM daily_config WHERE id=1";
    private static final int MAX_JSON_BYTES = 32768;
    private static final int MAX_SAMPLES = 10000;
    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long PUSH_INTERVAL_MS = 1000L;
    private static final int MATCH_SAMPLES = 1;
    private static final String[] COLUMNS = {"id", "wall", "elapsed", "json"};
    private Helper helper;
    private final Object pushLock = new Object();
    private long lastPushElapsed;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(AUTHORITY, "samples", MATCH_SAMPLES); }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) { super(c, DB_NAME, null, DB_VERSION); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY, wall INTEGER NOT NULL, elapsed INTEGER NOT NULL, json TEXT NOT NULL)");
            db.execSQL("CREATE INDEX sample_wall_idx ON sample(wall)");
            db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, version INTEGER NOT NULL, start_temp REAL NOT NULL DEFAULT 33.0)");
            ContentValues v = new ContentValues(); v.put("id", 1); v.put("enabled", 0); v.put("version", 0); v.put("start_temp", 33.0);
            db.insert("config", null, v);
            createDaily(db);
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE config ADD COLUMN start_temp REAL NOT NULL DEFAULT 33.0");
            }
            if (oldVersion < 3) createDaily(db);
        }
        private static void createDaily(SQLiteDatabase db) {
            db.execSQL(CREATE_DAILY);
            ContentValues row = dailyValues(DailyFanConfig.DEFAULT, 0L);
            row.put("id", 1);
            db.insertOrThrow("daily_config", null, row);
        }
    }

    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        helper = new Helper(getContext());
        return true;
    }

    private int caller() { return Binder.getCallingUid(); }
    private void requireRead() { int u = caller(); if (u != Process.myUid() && u != 1000 && u != 0) throw new SecurityException("UID not allowed"); }
    private void requireTelemetryWrite() { int u = caller(); if (u != Process.myUid() && u != 1000 && u != 0) throw new SecurityException("UID not allowed"); }
    private void requireOwnerWrite() { int u = caller(); if (u != Process.myUid() && u != 0) throw new SecurityException("UID not allowed"); }
    private SQLiteDatabase db() { if (helper == null) throw new IllegalStateException("Provider unavailable"); return helper.getWritableDatabase(); }

    @Override public String getType(Uri uri) {
        requireRead();
        return MATCHER.match(uri) == MATCH_SAMPLES ? "vnd.android.cursor.dir/vnd." + AUTHORITY + ".sample" : null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        requireRead();
        if (MATCHER.match(uri) != MATCH_SAMPLES) throw new IllegalArgumentException("Unknown URI");
        if (!TextUtils.isEmpty(selection) || (selectionArgs != null && selectionArgs.length != 0) || !TextUtils.isEmpty(sortOrder))
            throw new IllegalArgumentException("Custom selection/sort is not supported");
        long since = 0;
        String s = uri.getQueryParameter("since");
        if (s != null) { try { since = Long.parseLong(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid since"); } }
        String[] cols = projection == null ? COLUMNS : projection;
        if (cols.length == 0) throw new IllegalArgumentException("Empty projection is not supported");
        for (String col : cols) if (!isColumn(col)) throw new IllegalArgumentException("Invalid projection");
        // Select the latest bounded window first, then return in ascending time order for chart consumers.
        String where = since > 0 ? "wall>=?" : null;
        String[] args = since > 0 ? new String[]{Long.toString(since)} : null;
        Cursor c = db().rawQuery("SELECT " + joinColumns(cols) + " FROM (SELECT id,wall,elapsed,json FROM sample" +
                (where == null ? "" : " WHERE " + where) + " ORDER BY wall DESC,id DESC LIMIT 4000) ORDER BY wall ASC,id ASC", args);
        c.setNotificationUri(getContext().getContentResolver(), CONTENT_URI);
        return c;
    }
    private static boolean isColumn(String x) { for (String c : COLUMNS) if (c.equals(x)) return true; return false; }
    private static String joinColumns(String[] cols) { StringBuilder b = new StringBuilder(); for (String c: cols) { if (b.length()>0)b.append(','); b.append(c); } return b.toString(); }

    @Override public Uri insert(Uri uri, ContentValues values) { requireTelemetryWrite(); throw new UnsupportedOperationException("Use call(push)"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { requireOwnerWrite(); throw new UnsupportedOperationException("Use call(clear)"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { requireOwnerWrite(); throw new UnsupportedOperationException("Use call(setConfig)"); }

    private static ContentValues dailyValues(DailyFanConfig config, long version) {
        ContentValues v = new ContentValues();
        v.put("enabled", config.enabled ? 1 : 0);
        v.put("version", version);
        v.put("start_temp", config.startC);
        v.put("min_level", config.floor);
        v.put("w_battery", config.weight(0));
        v.put("w_board", config.weight(1));
        v.put("w_fan", config.weight(2));
        v.put("w_mos", config.weight(3));
        return v;
    }

    private static Bundle readDaily(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery(SELECT_DAILY, null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("daily configuration row missing");
            DailyFanConfig config = new DailyFanConfig(c.getInt(0) == 1, c.getDouble(2), c.getInt(3),
                    new double[]{c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getDouble(7)});
            return DailyFanContract.encode(config, c.getLong(1));
        }
    }

    private Bundle writeDaily(Bundle extras) {
        DailyFanConfig config = DailyFanContract.decode(extras);
        long before = DailyFanContract.version(extras, "expectedVersion");
        if (before >= Long.MAX_VALUE - 1) throw new IllegalArgumentException("daily version exhausted");
        SQLiteDatabase d = db();
        boolean changed;
        Bundle result;
        d.beginTransaction();
        try {
            changed = d.update("daily_config", dailyValues(config, before + 1L), "id=1 AND version=?",
                    new String[]{Long.toString(before)}) == 1;
            result = readDaily(d);
            result.putBoolean("ok", changed);
            if (!changed) result.putString("error", "version_conflict");
            d.setTransactionSuccessful();
        } finally { d.endTransaction(); }
        if (changed) getContext().getContentResolver().notifyChange(DAILY_CONFIG_URI, null);
        return result;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (DailyFanContract.READ.equals(method)) {
            requireRead(); return readDaily(db());
        }
        if (DailyFanContract.WRITE.equals(method)) {
            requireOwnerWrite(); return writeDaily(extras);
        }
        if (DailyFanContract.PREVIEW.equals(method)) {
            requireRead();
            DailyFanConfig config = DailyFanContract.decode(extras);
            double[] readings = extras.getDoubleArray("readings");
            if (readings == null || readings.length != DailyFanConfig.CHANNEL_COUNT)
                throw new IllegalArgumentException("four preview readings required; use NaN for unavailable");
            DailyFanPolicy.Fusion f = DailyFanPolicy.fuse(config, readings);
            Bundle out = new Bundle();
            out.putBoolean("hasTemperature", f.contributorMask != 0);
            if (f.contributorMask != 0) out.putDouble("fusedTemp", f.celsius);
            out.putInt("contributorMask", f.contributorMask);
            out.putDoubleArray("proportions", config.proportions());
            out.putDoubleArray("usedProportions", config.proportions(f.contributorMask));
            return out;
        }
        if ("config".equals(method)) {
            requireRead();
            Cursor c = db().rawQuery("SELECT enabled,version,start_temp FROM config WHERE id=1", null);
            boolean enabled = false; long version = 0; double start = ChargeCurve.DEFAULT_START;
            try { if (c.moveToFirst()) { enabled = c.getInt(0) != 0; version = c.getLong(1); start = c.getDouble(2); } } finally { c.close(); }
            Bundle out = new Bundle(); out.putBoolean("enabled", enabled); out.putLong("version", version); out.putString("policy", "battery");
            out.putDouble("startTemp", start); return out;
        }
        if ("latest".equals(method)) {
            requireRead(); Bundle out = new Bundle();
            Cursor c = db().rawQuery("SELECT json FROM sample ORDER BY wall DESC,id DESC LIMIT 1", null);
            try { out.putString("json", c.moveToFirst() ? c.getString(0) : "{\"hook\":\"等待注入\"}"); } finally { c.close(); }
            return out;
        }
        if ("push".equals(method)) {
            requireTelemetryWrite();
            String json = extras == null ? arg : extras.getString("json", arg);
            if (json == null) throw new IllegalArgumentException("Missing telemetry JSON");
            JSONObject obj = validJson(json);
            long wall = obj.optLong("wall", System.currentTimeMillis());
            long elapsed = obj.optLong("elapsed", android.os.SystemClock.elapsedRealtime());
            synchronized (pushLock) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastPushElapsed != 0 && now - lastPushElapsed < PUSH_INTERVAL_MS) throw new IllegalStateException("Push rate limited");
                lastPushElapsed = now;
            }
            ContentValues v = new ContentValues(); v.put("wall", wall); v.put("elapsed", elapsed); v.put("json", obj.toString());
            SQLiteDatabase d = db(); d.beginTransaction();
            try {
                d.insertOrThrow("sample", null, v);
                d.delete("sample", "wall < ?", new String[]{Long.toString(System.currentTimeMillis() - RETENTION_MS)});
                // Primary-key trim: id is a monotonic rowid, so this drops exactly the oldest rows
                // without the full-table NOT IN scan the previous statement performed every push.
                d.execSQL("DELETE FROM sample WHERE id <= (SELECT MAX(id) FROM sample) - ?", new Object[]{MAX_SAMPLES});
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
            getContext().getContentResolver().notifyChange(CONTENT_URI, null);
            Bundle out = new Bundle(); out.putBoolean("ok", true); return out;
        }
        if ("setConfig".equals(method)) {
            requireOwnerWrite();
            if (extras == null || !extras.containsKey("enabled")) throw new IllegalArgumentException("enabled required");
            boolean hasStart = extras.containsKey("startTemp");
            if (hasStart) {
                double start = extras.getDouble("startTemp");
                if (Double.isNaN(start) || start < 20.0 || start > 50.0) throw new IllegalArgumentException("startTemp out of range");
                db().execSQL("UPDATE config SET enabled=?,start_temp=?,version=version+1 WHERE id=1", new Object[]{extras.getBoolean("enabled") ? 1 : 0, start});
            } else {
                ContentValues v = new ContentValues(); v.put("enabled", extras.getBoolean("enabled") ? 1 : 0);
                db().execSQL("UPDATE config SET enabled=?,version=version+1 WHERE id=1", new Object[]{v.getAsInteger("enabled")});
            }
            getContext().getContentResolver().notifyChange(CONTENT_URI, null);
            return call("config", null, null);
        }
        if ("clear".equals(method)) {
            requireOwnerWrite(); db().delete("sample", null, null);
            getContext().getContentResolver().notifyChange(CONTENT_URI, null);
            Bundle out = new Bundle(); out.putBoolean("ok", true); return out;
        }
        throw new IllegalArgumentException("Unknown method");
    }

    private static JSONObject validJson(String s) {
        if (s == null || s.getBytes(java.nio.charset.Charset.forName("UTF-8")).length > MAX_JSON_BYTES) throw new IllegalArgumentException("JSON absent or too large");
        try {
            Object value = new JSONTokener(s).nextValue();
            if (!(value instanceof JSONObject)) throw new IllegalArgumentException("JSON object required");
            JSONObject o = (JSONObject)value;
            // Compatibility reports add diagnostic fields; the byte budget still bounds the payload.
            if (o.length() > 96) throw new IllegalArgumentException("Too many fields");
            return o;
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
}
