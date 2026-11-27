package dev.eta.acefan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure presentation rules. Routing, candidate evidence and measured feedback are separate facts. */
public final class FrontPresentation {
    public static final int BATTERY = 0, BOARD = 1, FAN = 2, MOS = 3;
    public static final String MISSING = "—";
    private static final int MAX_MASK = (1 << DailyFanConfig.CHANNEL_COUNT) - 1;
    private static final long MINUTE_MS = 60_000L;
    private static final long TREND_GAP_MS = 60_000L; // Above the stored idle cadence of 30 s.
    private static final long HOOK_FRESH_MS = Sampling.staleMs(Sampling.IDLE_MS);

    private FrontPresentation() { }

    public enum Power { UNKNOWN, BATTERY, WIRED_CHARGING, EXTERNAL }
    public enum Mode { OEM, CHARGE, DAILY }
    public enum Source { NONE, HOOK, ROOT_PREVIEW, APP_OBSERVATION, APP_LOCAL, UNKNOWN }
    public enum BatterySource { UNKNOWN, THERMAL, POWER_SUPPLY, STICKY }

    public enum DailyStatus {
        KERNEL_CONTROL("内核控制中"), KERNEL_BLOCKED("安全门控未允许 · 已交还原厂"),
        CONFIG_UNAVAILABLE("设置不可读"), DISABLED("未启用"), POWER_UNKNOWN("未确认电池放电"),
        EXTERNAL_POWER("外部供电时不介入"), RELOAD("模块待重载"), HOOK_READ_FAILED("模块未能读取设置"),
        WAITING_CONFIG("等待模块读取当前设置"), WAITING_SAMPLE("已读取设置 · 等待新鲜回报"),
        TOO_COOL("未超过启停温度"), WAITING_CANDIDATE("等待原厂计算日用档位"),
        OEM_ZERO("等待原厂起转"), OEM_SUFFICIENT("原厂档位已足够"),
        OEM_BLOCKED("原厂设置或保护未允许"), SCENE_BLOCKED("原厂当前场景不允许"),
        HEALTH_BLOCKED("风扇健康状态未通过"), NO_TEMPERATURE("无可用参与温度"),
        THERMAL_BLOCKED("热状态异常或未知 · 交还原厂"), AUTO_TARGET_UNVERIFIED("未确认普通自动档 · 保留原厂"),
        STOP_CANDIDATE("停转目标已计算"), STOP_REQUESTED("已请求停转"),
        COOLING("降温确认中"), CANDIDATE_ONLY("已抬高候选 · 等待控制反馈"),
        REQUEST_OBSERVED("已观察到控制请求"), REQUEST_CONFIRMED("原厂已确认控制请求"),
        OEM_STOPPED("原厂请求停转"), OEM_LIMITED("原厂限制了档位"), OEM_OVERRIDE("原厂采用其他档位");

        public final String text;
        DailyStatus(String text) { this.text = text; }
    }

    public static final class Live {
        public final Power power;
        /** Current route, not a claim of enhancement or fan rotation. */
        public final Mode mode;
        public final Source source;
        public final BatterySource batterySource;
        public final boolean telemetryFresh, fallback, hookFresh, needsReload;
        public final boolean chargeActive, dailyVersionPending, chargeVersionPending;
        public final boolean dailyConfigAcknowledged, dailyConfigReadFailed;
        /** Only a fresh injected record supplies the running Hook version. Empty means unknown. */
        public final String hookVersion;
        /** Exact known Hook state codes (empty means no evidence). Candidate/request states are not RPM. */
        public final String dailyState, chargePhase;
        public final long runtimeDailyVersion, runtimeChargeVersion; // -1 if unobserved
        public final long sampleWallTimeMillis; // 0 if no current observation
        public final double dailyFusedTemp, dailyStartTemp, chargeFusedTemp;
        public final int dailyContributorMask; // -1 if not reported
        /** Only the driver's effective feedback, never OEM candidate, request or configured floor. */
        public final double effectiveLevel;
        public final double fanRpm;
        public final long fanRpmWall, fanRpmLevel;
        public final String fanRpmQuality;
        final Reading reading;

        private Live(Power power, Mode mode, Reading reading, Reading freshHook, boolean needsReload,
                boolean chargeActive, long savedDailyVersion, long savedChargeVersion) {
            this.power = power; this.mode = mode; this.reading = reading;
            source = reading == null ? Source.NONE : reading.source;
            batterySource = reading == null ? BatterySource.UNKNOWN : reading.batterySource;
            telemetryFresh = reading != null;
            fallback = telemetryFresh && source != Source.HOOK;
            sampleWallTimeMillis = reading == null ? 0L : reading.wall;
            hookFresh = freshHook != null; this.needsReload = needsReload;
            hookVersion = hookFresh ? freshHook.hookVersion : "";
            this.chargeActive = chargeActive;
            chargePhase = mode == Mode.CHARGE ? freshHook.chargePhase : "";
            chargeFusedTemp = mode == Mode.CHARGE ? freshHook.chargeFusedTemp : Double.NaN;
            runtimeChargeVersion = hookFresh ? freshHook.chargeVersion : -1L;
            DailyReading daily = mode == Mode.DAILY ? freshHook.daily : null;
            dailyState = daily == null ? "" : daily.state;
            dailyFusedTemp = daily == null ? Double.NaN : daily.fusedTemp;
            dailyStartTemp = daily == null ? Double.NaN : daily.startTemp;
            dailyContributorMask = daily == null ? -1 : daily.contributorMask;
            // Configuration acknowledgement is not a live temperature/decision. An idle report can
            // still confirm the saved revision even after its 15-second readings have expired.
            DailyReading acknowledgement = !needsReload && power == Power.BATTERY && hookFresh
                    && freshHook.power == Power.BATTERY && freshHook.mode == Mode.DAILY ? freshHook.daily : null;
            runtimeDailyVersion = acknowledgement == null ? -1L : acknowledgement.version;
            dailyConfigReadFailed = acknowledgement != null && "config_unavailable".equals(acknowledgement.state);
            dailyConfigAcknowledged = !dailyConfigReadFailed && savedDailyVersion >= 0 && savedDailyVersion == runtimeDailyVersion;
            dailyVersionPending = runtimeDailyVersion >= 0 && savedDailyVersion > runtimeDailyVersion;
            chargeVersionPending = runtimeChargeVersion >= 0 && savedChargeVersion > runtimeChargeVersion;
            effectiveLevel = reading == null ? Double.NaN : reading.effectiveLevel;
            RpmReading rpm = !needsReload && source == Source.HOOK && reading != null ? reading.fanRpm : null;
            fanRpm = rpm == null ? Double.NaN : rpm.value;
            fanRpmWall = rpm == null ? -1L : rpm.wall;
            fanRpmLevel = rpm == null ? -1L : rpm.level;
            fanRpmQuality = needsReload ? "reload" : rpm == null ? "waiting_hook" : rpm.quality;
        }

        public DailyStatus dailyStatus(boolean configReadable, boolean dailyEnabled) {
            if (!configReadable) return DailyStatus.CONFIG_UNAVAILABLE;
            if (!dailyEnabled) return DailyStatus.DISABLED;
            if (power == Power.UNKNOWN) return DailyStatus.POWER_UNKNOWN;
            if (power != Power.BATTERY) return DailyStatus.EXTERNAL_POWER;
            if (needsReload) return DailyStatus.RELOAD;
            if (dailyConfigReadFailed) return DailyStatus.HOOK_READ_FAILED;
            if (!dailyConfigAcknowledged) return DailyStatus.WAITING_CONFIG;
            switch (dailyState) {
                case "kernel_control": return DailyStatus.KERNEL_CONTROL;
                case "kernel_blocked": return DailyStatus.KERNEL_BLOCKED;
                case "below_threshold": return DailyStatus.TOO_COOL;
                case "ready": return DailyStatus.WAITING_CANDIDATE;
                case "waiting_oem": return DailyStatus.OEM_ZERO;
                case "oem_level_sufficient": return DailyStatus.OEM_SUFFICIENT;
                case "oem_blocked": return DailyStatus.OEM_BLOCKED;
                case "scene_blocked": return DailyStatus.SCENE_BLOCKED;
                case "health_blocked": return DailyStatus.HEALTH_BLOCKED;
            case "thermal_blocked": return DailyStatus.THERMAL_BLOCKED;
            case "auto_target_unverified": return DailyStatus.AUTO_TARGET_UNVERIFIED;
            case "stop_candidate": return DailyStatus.STOP_CANDIDATE;
            case "stop_requested": return DailyStatus.STOP_REQUESTED;
                case "temperature_unavailable": return DailyStatus.NO_TEMPERATURE;
                case "cooling": return DailyStatus.COOLING;
                case "candidate_applied": return DailyStatus.CANDIDATE_ONLY;
                case "request_observed": return DailyStatus.REQUEST_OBSERVED;
                case "request_confirmed": return DailyStatus.REQUEST_CONFIRMED;
                case "oem_stopped": return DailyStatus.OEM_STOPPED;
            case "oem_override": return DailyStatus.OEM_OVERRIDE;
                case "oem_limited": return DailyStatus.OEM_LIMITED;
                default: return DailyStatus.WAITING_SAMPLE;
            }
        }

        /** Raw readings in battery/board/fan/mos order. A sticky battery value is tagged as fallback-only. */
        public double[] temperatures() { return reading == null ? emptyTemperatures() : reading.temperatures.clone(); }
    }

    public static final class Preview {
        /** Temperature from the supplied draft and one still-fresh observation, never a policy/runtime claim. */
        public final double fusedTemp;
        public final boolean dataFresh, hasTemperature;
        public final int contributorMask;
        /** Sum of the actual weight-bearing valid inputs (zero if none). Not the nominal weight sum. */
        public final double denominator;
        private final double[] proportions, usedProportions;

        private Preview(boolean dataFresh, DailyFanConfig config, DailyFanPolicy.Fusion fusion) {
            this.dataFresh = dataFresh; fusedTemp = fusion.celsius; contributorMask = fusion.contributorMask;
            hasTemperature = contributorMask != 0;
            proportions = config.proportions(); usedProportions = config.proportions(contributorMask);
            double total = 0.0;
            for (int i = 0; i < DailyFanConfig.CHANNEL_COUNT; i++)
                if ((contributorMask & (1 << i)) != 0) total += config.weight(i);
            denominator = total;
        }

        /** Configured shares (0–1) before excluding any readings. */
        public double[] proportions() { return proportions.clone(); }
        /** Shares (0–1) from this preview after excluding missing/invalid readings. */
        public double[] usedProportions() { return usedProportions.clone(); }
    }

    public static final class TrendPoint {
        public final long wallTimeMillis;
        public final Source source;
        /** Route reported in this historical sample, never inferred from today's live power or config. */
        public final Mode mode;
        /** Start a new path on true. Also end each individual series on NaN, even when this is false. */
        public final boolean breakBefore;
        public final double dailyFusedTemp, dailyStartTemp, chargeFusedTemp, effectiveLevel, fanRpm;
        public final long dailyVersion, fanRpmGeneration;
        public final String fanRpmQuality;
        private final Reading reading;

        private TrendPoint(Reading reading, boolean breakBefore) {
            this.reading = reading; this.breakBefore = breakBefore;
            wallTimeMillis = reading.wall; source = reading.source; mode = reading.mode;
            DailyReading daily = mode == Mode.DAILY ? reading.daily : null;
            dailyVersion = daily == null ? -1L : daily.version;
            dailyFusedTemp = daily == null ? Double.NaN : daily.fusedTemp;
            dailyStartTemp = daily == null ? Double.NaN : daily.startTemp;
            chargeFusedTemp = mode == Mode.CHARGE ? reading.chargeFusedTemp : Double.NaN;
            effectiveLevel = reading.effectiveLevel;
            fanRpm = reading.fanRpm == null ? Double.NaN : reading.fanRpm.value;
            fanRpmGeneration = reading.fanRpm == null ? -1L : reading.fanRpm.generation;
            fanRpmQuality = reading.fanRpm == null ? "waiting_hook" : reading.fanRpm.quality;
        }

        public double[] temperatures() { return reading.temperatures.clone(); }
    }

    public static String temperatureText(double value) {
        return finite(value) ? String.format(Locale.US, "%.1f", value) : MISSING;
    }

    public static String levelText(double value) {
        return validLevel(value) ? Integer.toString((int) value) : MISSING;
    }

    // A small reader interface keeps exact Provider field selection testable without Android's JSONObject.
    interface Fields { Object get(String key); }

    static Reading decode(Fields row, Source sourceOverride) {
        long wall = integer(row, "wall");
        if (wall <= 0L) return null; // Includes the Provider's empty `latest` sentinel.
        Source source = sourceOverride != null ? sourceOverride : source(text(row, "hook"));
        long plugged = integer(row, "plugged");
        Power power = power(plugged >= 0L && plugged <= Integer.MAX_VALUE ? (int) plugged : -1,
                flag(row, "wired"), flag(row, "charging"), flag(row, "batteryDischarging"));
        String route = text(row, "controlMode");
        Mode mode = Mode.OEM;
        DailyReading daily = null;
        Object nestedDaily = source == Source.HOOK && "daily".equals(route) ? row.get("daily") : null;
        if (nestedDaily instanceof Fields) {
            Fields dailyFields = (Fields) nestedDaily;
            long mask = integer(dailyFields, "contributorMask");
            daily = new DailyReading(number(dailyFields, "fusedTemp"), number(dailyFields, "startTemp"),
                    version(dailyFields, "version"), mask >= 0 && mask <= MAX_MASK ? (int) mask : -1,
                    dailyState(text(dailyFields, "state")));
            mode = Mode.DAILY;
        } else if (source == Source.HOOK && "charge".equals(route)) {
            mode = Mode.CHARGE;
        }
        double battery = Boolean.FALSE.equals(row.get("batteryValid")) ? Double.NaN : number(row, "battery");
        String hookVersion = text(row, "hookVersion");
        if (!hookVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) hookVersion = "";
        return new Reading(wall, integer(row, "elapsed"), text(row, "boot"), source, power, mode,
                batterySource(text(row, "batterySource")),
                new double[]{battery, number(row, "board"), number(row, "fan"), number(row, "mos")},
                number(row, "effective"), hookVersion, version(row, "configVersion"), number(row, "fused"),
                chargePhase(text(row, "phase")), flag(row, "active"), daily,
                source == Source.HOOK ? rpm(row, wall, integer(row, "elapsed")) : null,
                "内核控制".equals(text(row, "hook")));
    }

    static final class RpmReading {
        final double value;
        final long wall, level, generation;
        final String quality;
        RpmReading(double value, long wall, long level, long generation, String quality) {
            this.value = value; this.wall = wall; this.level = level; this.generation = generation; this.quality = quality;
        }
    }

    private static RpmReading rpm(Fields row, long rowWall, long rowElapsed) {
        Object nested = row.get("fanRpm");
        if (!(nested instanceof Fields)) return null; // Historical top-level rpm is deliberately ignored.
        Fields f = (Fields) nested;
        if (!FanRpm.SOURCE.equals(text(f, "source"))) return null;
        String q = text(f, "quality");
        switch (q) {
            case "held": case "waiting_event": case "fan_off": case "driver_unknown": case "debug_unknown":
            case "debug_override": case "driver_fault": case "zero_report": case "invalid_event":
            case "clock_changed": case "not_attached": case "unavailable": case "observer_unavailable": break;
            default: q = "invalid_event";
        }
        double value = number(f, "value"), level = number(f, "level");
        long wall = integer(f, "wall"), elapsed = integer(f, "elapsed");
        boolean valid = "held".equals(q) && "attached".equals(text(f, "observer"))
                && FanRpm.valid(value) && level > 0 && level <= 20 && level == number(row, "effective")
                && number(row, "state") == 1 && "NORMAL".equals(text(row, "fanStatus"))
                && wall > 0 && wall <= rowWall && elapsed >= 0 && elapsed <= rowElapsed
                && rowWall - wall >= 0 && Math.abs((rowWall - wall) - (rowElapsed - elapsed)) <= 10_000L;
        return new RpmReading(valid ? value : Double.NaN, valid ? wall : -1L,
                valid ? (long)level : -1L, integer(f, "generation"), valid ? "held" : "held".equals(q) ? "invalid_event" : q);
    }

    public static String rpmText(double value) { return FanRpm.valid(value) ? Long.toString((long)value) : MISSING; }

    public static String rpmStatus(String quality) {
        switch (quality) {
            case "held": return "本档位首次有效实测 · 保持至换档";
            case "reload": return "模块待重载 · 尚无本版测速回报";
            case "fan_off": return "风扇未启用 · 不把缺测当作 0 RPM";
            case "waiting_event": return "等待本档位的有效驱动测速";
            case "debug_override": return "驱动处于调试测速覆盖 · 不采用";
            case "debug_unknown": return "无法排除驱动调试覆盖 · 不采用";
            case "driver_fault": return "驱动状态异常 · 保持值已失效";
            case "zero_report": return "驱动报零 · 不作为有效测速";
            case "not_attached": case "unavailable": case "observer_unavailable": return "测速事件接口未接入";
            case "driver_unknown": return "档位或使能不可读 · 保持值已失效";
            case "clock_changed": case "invalid_event": return "测速证据无效 · 等待重新采样";
            default: return "尚无驱动测速回报";
        }
    }

    private static Source source(String marker) {
        switch (marker) {
            case "内核控制": // trusted controller category; not a claim that Xposed is injected
            case "已注入": return Source.HOOK;
            case "Root只读预览": return Source.ROOT_PREVIEW;
            case "未注入·App观测": return Source.APP_OBSERVATION;
            default: return Source.UNKNOWN;
        }
    }

    private static BatterySource batterySource(String marker) {
        switch (marker) {
            case "thermal:battery": return BatterySource.THERMAL;
            case "sysfs:battery/temp": return BatterySource.POWER_SUPPLY;
            case "battery_sticky": return BatterySource.STICKY;
            default: return BatterySource.UNKNOWN;
        }
    }

    private static String dailyState(String state) {
        switch (state) {
            case "disabled": case "config_unavailable": case "oem_blocked": case "temperature_unavailable":
            case "below_threshold": case "cooling": case "ready": case "waiting_oem": case "scene_blocked":
            case "health_blocked": case "candidate_applied": case "oem_level_sufficient": case "oem_stopped":
            case "oem_limited": case "request_confirmed": case "request_observed": case "thermal_blocked":
            case "auto_target_unverified": case "stop_candidate": case "stop_requested": case "oem_override":
            case "kernel_control": case "kernel_blocked": return state;
            default: return "";
        }
    }

    private static String chargePhase(String phase) {
        switch (phase) {
            case "oem": case "screen_off": case "locked_handoff": case "unlock_grace": case "kernel_control": return phase;
            default: return "";
        }
    }

    private static String text(Fields row, String key) {
        Object value = row.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static boolean flag(Fields row, String key) { return Boolean.TRUE.equals(row.get(key)); }

    private static double number(Fields row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static long integer(Fields row, String key) {
        Object value = row.get(key);
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1L;
    }

    private static long version(Fields row, String key) { return Math.max(-1L, integer(row, key)); }

    // Adapter inputs are immutable and deliberately contain no diagnostic free text or raw JSON.
    static final class DailyReading {
        final double fusedTemp, startTemp;
        final long version;
        final int contributorMask;
        final String state;
        DailyReading(double fusedTemp, double startTemp, long version, int contributorMask, String state) {
            this.contributorMask = contributorMask >= 0 && contributorMask <= MAX_MASK ? contributorMask : -1;
            this.fusedTemp = this.contributorMask != 0 && FusionTemp.valid(fusedTemp) ? fusedTemp : Double.NaN;
            this.startTemp = startTemp >= DailyFanConfig.MIN_START_C && startTemp <= DailyFanConfig.MAX_START_C
                    ? startTemp : Double.NaN;
            this.version = version; this.state = state;
        }
    }

    static final class Reading {
        final long wall, elapsed, chargeVersion;
        final String boot, hookVersion, chargePhase;
        final Source source;
        final Power power;
        final Mode mode;
        final BatterySource batterySource;
        private final double[] temperatures;
        final double effectiveLevel, chargeFusedTemp;
        final boolean chargeActive, kernel;
        final DailyReading daily;
        final RpmReading fanRpm;

        Reading(long wall, long elapsed, String boot, Source source, Power power, Mode mode, BatterySource batterySource,
                double[] temperatures, double effectiveLevel, String hookVersion, long chargeVersion,
                double chargeFusedTemp, String chargePhase, boolean chargeActive, DailyReading daily) {
            this(wall, elapsed, boot, source, power, mode, batterySource, temperatures, effectiveLevel,
                    hookVersion, chargeVersion, chargeFusedTemp, chargePhase, chargeActive, daily, null);
        }

        Reading(long wall, long elapsed, String boot, Source source, Power power, Mode mode, BatterySource batterySource,
                double[] temperatures, double effectiveLevel, String hookVersion, long chargeVersion,
                double chargeFusedTemp, String chargePhase, boolean chargeActive, DailyReading daily, RpmReading fanRpm) {
            this(wall, elapsed, boot, source, power, mode, batterySource, temperatures, effectiveLevel,
                    hookVersion, chargeVersion, chargeFusedTemp, chargePhase, chargeActive, daily, fanRpm, false);
        }

        Reading(long wall, long elapsed, String boot, Source source, Power power, Mode mode, BatterySource batterySource,
                double[] temperatures, double effectiveLevel, String hookVersion, long chargeVersion,
                double chargeFusedTemp, String chargePhase, boolean chargeActive, DailyReading daily, RpmReading fanRpm, boolean kernel) {
            this.kernel = kernel; this.fanRpm = fanRpm;
            this.wall = wall; this.elapsed = elapsed; this.boot = boot;
            this.source = source; this.power = power; this.batterySource = batterySource;
            this.mode = source != Source.HOOK || (mode == Mode.DAILY && daily == null) ? Mode.OEM : mode;
            this.temperatures = temperatures.clone();
            for (int i = 0; i < this.temperatures.length; i++) {
                double t = this.temperatures[i];
                if (!(t >= -20.0 && t <= 100.0)) this.temperatures[i] = Double.NaN;
            }
            this.effectiveLevel = validLevel(effectiveLevel) ? effectiveLevel : Double.NaN;
            this.hookVersion = hookVersion; this.chargeVersion = chargeVersion;
            this.chargeFusedTemp = FusionTemp.valid(chargeFusedTemp) ? chargeFusedTemp : Double.NaN;
            this.chargePhase = chargePhase; this.chargeActive = chargeActive; this.daily = daily;
        }
    }

    static Power power(int plugged, boolean wired, boolean charging, boolean discharging) {
        if (plugged > 0) return wired && charging ? Power.WIRED_CHARGING : Power.EXTERNAL;
        return plugged == 0 && discharging ? Power.BATTERY : Power.UNKNOWN;
    }

    private static boolean displayable(Reading reading, Power power, long wall, long elapsed, String boot) {
        return reading != null && reading.source != Source.UNKNOWN && reading.source != Source.NONE
                && (power == Power.UNKNOWN || reading.power == power)
                && fresh(reading, wall, elapsed, boot, DailyFanPolicy.FRESH_MS);
    }

    static Reading displayReading(Reading hook, Reading stored, Reading local,
            Power power, long wall, long elapsed, String boot) {
        if (displayable(hook, power, wall, elapsed, boot)) return hook;
        if (displayable(stored, power, wall, elapsed, boot)) return stored;
        return displayable(local, power, wall, elapsed, boot) ? local : null;
    }

    static Live live(Reading hook, Reading stored, Reading local, Power power, long wall, long elapsed,
            String boot, String expectedHookVersion, long savedDailyVersion, long savedChargeVersion) {
        Reading shown = displayReading(hook, stored, local, power, wall, elapsed, boot);
        boolean hookFresh = hook != null && hook.source == Source.HOOK && fresh(hook, wall, elapsed, boot, HOOK_FRESH_MS);
        boolean needsReload = hookFresh && !hook.kernel && !expectedHookVersion.equals(hook.hookVersion);
        Mode mode = Mode.OEM;
        if (hookFresh && !needsReload && shown == hook) {
            if (power == Power.BATTERY && hook.mode == Mode.DAILY) mode = Mode.DAILY;
            else if (power == Power.WIRED_CHARGING && hook.mode == Mode.CHARGE) mode = Mode.CHARGE;
        }
        return new Live(power, mode, shown, hookFresh ? hook : null, needsReload,
                mode == Mode.CHARGE && hook.chargeActive && ("screen_off".equals(hook.chargePhase)
                        || "locked_handoff".equals(hook.chargePhase) || "unlock_grace".equals(hook.chargePhase)
                        || "kernel_control".equals(hook.chargePhase)),
                savedDailyVersion, savedChargeVersion);
    }

    static Preview preview(DailyFanConfig config, Reading reading, long wall, long elapsed, String boot) {
        boolean fresh = fresh(reading, wall, elapsed, boot, DailyFanPolicy.FRESH_MS);
        double[] values = fresh ? reading.temperatures.clone() : emptyTemperatures();
        // A sticky broadcast has no verifiable measurement time here. The Hook has separate age evidence;
        // its actual reported fused temperature is displayed separately, not used to backfill this preview.
        if (!fresh || (reading.batterySource != BatterySource.THERMAL && reading.batterySource != BatterySource.POWER_SUPPLY))
            values[BATTERY] = Double.NaN;
        return new Preview(fresh, config, DailyFanPolicy.fuse(config, values));
    }

    static boolean freshRoot(Reading reading, long wall, long elapsed, String boot) {
        return reading != null && reading.kernel && reading.source == Source.HOOK
                && fresh(reading, wall, elapsed, boot, 11999L);
    }

    private static boolean fresh(Reading reading, long wall, long elapsed, String boot, long maximumAge) {
        return reading != null && ReportFreshness.fresh(reading.wall, reading.elapsed, wall, elapsed,
                reading.boot, boot, reading.kernel ? Math.min(maximumAge, 11999L) : maximumAge);
    }

    static long trendStart(long nowWall, int minutes) {
        if (minutes != 15 && minutes != 30 && minutes != 60) throw new IllegalArgumentException("trend minutes must be 15, 30 or 60");
        return nowWall - minutes * MINUTE_MS;
    }

    /** The Provider supplies rows ordered by wall time. Null/invalid rows break continuity. */
    static List<TrendPoint> trend(List<Reading> rows, long nowWall) {
        ArrayList<TrendPoint> out = new ArrayList<>();
        Reading prev = null;
        long start = trendStart(nowWall, 60);
        for (Reading reading : rows) {
            if (reading == null || reading.wall <= 0L || reading.wall < start || reading.wall > nowWall) {
                prev = null;
                continue;
            }
            out.add(new TrendPoint(reading, !continuous(prev, reading)));
            prev = reading;
        }
        return Collections.unmodifiableList(out);
    }

    static List<TrendPoint> trendWindow(List<TrendPoint> history, long nowWall, int minutes) {
        long start = trendStart(nowWall, minutes);
        if (minutes == 60) return history;
        int from = 0;
        while (from < history.size() && history.get(from).wallTimeMillis < start) from++;
        if (from == history.size()) return Collections.emptyList();
        if (history.get(from).breakBefore) return history.subList(from, history.size());
        ArrayList<TrendPoint> out = new ArrayList<>(history.subList(from, history.size()));
        out.set(0, new TrendPoint(out.get(0).reading, true));
        return Collections.unmodifiableList(out);
    }

    private static boolean continuous(Reading a, Reading b) {
        if (a == null || a.source != b.source || a.source == Source.UNKNOWN || a.source == Source.NONE
                || a.mode != b.mode || a.power != b.power || a.batterySource != b.batterySource || a.kernel != b.kernel
                || !a.hookVersion.equals(b.hookVersion) || a.boot.isEmpty() || !a.boot.equals(b.boot)
                || a.wall >= b.wall || a.elapsed >= b.elapsed || !fresh(a, b.wall, b.elapsed, b.boot, TREND_GAP_MS)) return false;
        return a.mode != Mode.DAILY || (a.daily.version == b.daily.version && a.daily.contributorMask == b.daily.contributorMask);
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static boolean validLevel(double value) {
        return value >= 0.0 && value <= Integer.MAX_VALUE && value == Math.rint(value);
    }
    private static double[] emptyTemperatures() { return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN}; }
}
