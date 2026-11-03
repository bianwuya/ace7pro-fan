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
    private static final String DB_NAME = "acefan-telemetry.db";
    private static final int DB_VERSION = 1;
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
            db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, version INTEGER NOT NULL)");
            ContentValues v = new ContentValues(); v.put("id", 1); v.put("enabled", 0); v.put("version", 0);
            db.insert("config", null, v);
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
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

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("config".equals(method)) {
            requireRead();
            Cursor c = db().rawQuery("SELECT enabled,version FROM config WHERE id=1", null);
            boolean enabled = false; long version = 0;
            try { if (c.moveToFirst()) { enabled = c.getInt(0) != 0; version = c.getLong(1); } } finally { c.close(); }
            Bundle out = new Bundle(); out.putBoolean("enabled", enabled); out.putLong("version", version); out.putString("policy", "battery"); return out;
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
            ContentValues v = new ContentValues(); v.put("enabled", extras.getBoolean("enabled") ? 1 : 0);
            db().execSQL("UPDATE config SET enabled=?,version=version+1 WHERE id=1", new Object[]{v.getAsInteger("enabled")});
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
