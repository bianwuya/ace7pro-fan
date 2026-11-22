package dev.eta.acefan;

import android.os.Bundle;

/** Named, versioned Provider contract. Existing charge readers never depend on it. */
final class DailyFanContract {
    static final String READ = "dailyConfig", WRITE = "setDailyConfig", PREVIEW = "previewDailyConfig";
    static final String PATH = "daily-config";
    private static final String[] KEYS = {"batteryWeight", "boardWeight", "fanWeight", "mosWeight"};

    private DailyFanContract() { }

    static DailyFanConfig decode(Bundle input) {
        if (input == null) throw new IllegalArgumentException("missing daily configuration");
        double[] weights = new double[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) weights[i] = (Double) field(input, KEYS[i], Double.class);
        return new DailyFanConfig((Boolean) field(input, "enabled", Boolean.class),
                (Double) field(input, "startTemp", Double.class), (Integer) field(input, "minLevel", Integer.class), weights);
    }

    static long version(Bundle input, String key) {
        long n = (Long) field(input, key, Long.class);
        if (n < 0 || n == Long.MAX_VALUE) throw new IllegalArgumentException("invalid " + key);
        return n;
    }

    static Bundle encode(DailyFanConfig c, long version) {
        Bundle out = new Bundle();
        out.putLong("version", version);
        out.putBoolean("enabled", c.enabled);
        out.putDouble("startTemp", c.startC);
        out.putInt("minLevel", c.floor);
        for (int i = 0; i < KEYS.length; i++) out.putDouble(KEYS[i], c.weight(i));
        out.putDoubleArray("proportions", c.proportions());
        return out;
    }

    private static Object field(Bundle input, String key, Class<?> type) {
        Object value = input.get(key);
        if (!type.isInstance(value)) throw new IllegalArgumentException("missing or invalid " + key);
        return value;
    }
}
