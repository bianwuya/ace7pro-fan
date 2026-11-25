package dev.eta.acefan;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.eta.acefan.FrontPresentation.*;

public final class FrontPresentationTest {
    private static final String BOOT = "01234567-abcd-1234-abcd-0123456789ab";
    private static final String OTHER_BOOT = "11234567-abcd-1234-abcd-0123456789ab";
    private static final String HOOK = "0.6.1-daily-backend";
    private static final long NOW = 1_710_000_000_000L, ELAPSED = 7_200_000L, MIN = 60_000L;

    public static void main(String[] args) {
        routingAndEvidenceAreSeparate();
        freshnessAndFallback();
        dailyTriggerExplanations();
        previewUsesOnlyFreshContributingChannels();
        trendsUseHistoricalFieldsAndBreaks();
        rpmHoldUsesOnlyVerifiedEventFields();
        System.out.println("FrontPresentationTest: OK");
    }

    private static void routingAndEvidenceAreSeparate() {
        Reading daily = sample(2_000L, "daily", dailyFields(6, 38.5, 34.0, 3, "request_confirmed"),
                "effective", null, "active", true, "candidate", 12, "fused", 50.0);
        Live d = live(daily, daily, null, Power.BATTERY);
        check(d.mode == Mode.DAILY && !d.chargeActive && d.hookFresh && !d.needsReload, "daily uses its own hook signal");
        check(d.hookVersion.equals(HOOK) && d.runtimeDailyVersion == 6 && d.dailyVersionPending,
                "running daily config and saved daily version are distinct");
        check(d.runtimeChargeVersion == 2 && !d.chargeVersionPending, "charge config version is separate");
        check(d.dailyState.equals("request_confirmed") && Double.isNaN(d.effectiveLevel),
                "confirmed request, candidate and floor cannot replace measured level");
        check(d.dailyFusedTemp == 38.5 && d.dailyStartTemp == 34.0 && d.dailyContributorMask == 3,
                "only nested daily values populate live daily evidence");
        check(levelText(d.effectiveLevel).equals(MISSING), "missing level has no false zero");
        check(Arrays.equals(d.temperatures(), new double[]{31.0, 33.0, 35.0, 37.0}), "battery/board/fan/mos order");
        d.temperatures()[BATTERY] = 0.0;
        check(d.temperatures()[BATTERY] == 31.0, "published readings are immutable");

        for (Power other : new Power[]{Power.UNKNOWN, Power.EXTERNAL, Power.WIRED_CHARGING}) {
            Live changed = live(daily, daily, null, other);
            check(changed.mode == Mode.OEM && Double.isNaN(changed.dailyFusedTemp) && changed.runtimeDailyVersion == -1,
                    "daily signal cannot survive a power mismatch: " + other);
        }
        Reading disabled = sample(3_000L, "daily", dailyFields(7, 33.0, 33.0, 1, "disabled"), "enabled", true);
        Live off = live(disabled, disabled, null, Power.BATTERY);
        check(off.mode == Mode.DAILY && off.dailyState.equals("disabled"), "daily route is not daily activity");
        Reading noFrame = sample(1_000L, "oem", null, "enabled", true, "active", true);
        check(live(noFrame, noFrame, null, Power.BATTERY).mode == Mode.OEM, "enabled is not a runtime signal");
        Reading old = sample(1_000L, "daily", dailyFields(5, 37.0, 33.0, 1, "ready"), "hookVersion", "0.6.0-fusion");
        Live oldHook = live(old, old, null, Power.BATTERY);
        check(oldHook.needsReload && oldHook.mode == Mode.OEM && Double.isNaN(oldHook.dailyFusedTemp), "old Hook is reload only");
        Reading noVersion = sample(1_000L, "daily", dailyFields(5, 37.0, 33.0, 1, "ready"), "hookVersion", null);
        check(live(noVersion, noVersion, null, Power.BATTERY).needsReload, "missing Hook version is reload only");

        Reading charge = sample(1_000L, "charge", null, "plugged", 1, "wired", true, "charging", true,
                "batteryDischarging", false, "active", true, "phase", "locked_handoff", "effective", 0, "configVersion", 1);
        Live c = live(charge, charge, null, Power.WIRED_CHARGING);
        check(c.mode == Mode.CHARGE && c.chargeActive && c.chargeVersionPending && c.chargePhase.equals("locked_handoff"),
                "wired charge and actual phase allow the legacy enhancement display");
        check(c.effectiveLevel == 0.0 && levelText(c.effectiveLevel).equals("0"), "measured zero is real data");
        check(live(charge, charge, null, Power.EXTERNAL).mode == Mode.OEM, "noncharging external power vetoes charge");
        check(power(0, false, false, false) == Power.UNKNOWN && power(8, false, true, false) == Power.EXTERNAL,
                "unknown or dock power is not daily");
    }

    private static void freshnessAndFallback() {
        Reading boundary = sample(15_000L, "daily", dailyFields(7, 37.0, 34.5, 1, "ready"));
        check(live(boundary, boundary, null, Power.BATTERY).dailyFusedTemp == 37.0, "15-second boundary is valid");
        Reading stale = sample(15_001L, "daily", dailyFields(7, 37.0, 34.5, 1, "ready"));
        Live notCurrent = live(stale, stale, null, Power.BATTERY);
        check(notCurrent.hookFresh && !notCurrent.telemetryFresh && notCurrent.mode == Mode.OEM,
                "recent injection evidence is not a 15-second measurement");
        check(Double.isNaN(notCurrent.temperatures()[0]) && temperatureText(notCurrent.dailyFusedTemp).equals(MISSING),
                "stale never becomes a number");
        Reading priorBoot = sample(5_000L, "daily", dailyFields(7, 37.0, 34.5, 1, "ready"), "boot", OTHER_BOOT);
        check(!live(priorBoot, priorBoot, null, Power.BATTERY).hookFresh, "prior boot cannot prove injection");
        Reading future = sample(-1L, "daily", dailyFields(7, 37.0, 34.5, 1, "ready"));
        check(!live(future, future, null, Power.BATTERY).telemetryFresh, "future clock cannot prove a live sample");
        Reading timeJump = sample(13_000L, "daily", null, "elapsed", ELAPSED - 1L);
        check(!live(timeJump, timeJump, null, Power.BATTERY).hookFresh, "wall and elapsed must agree");

        Reading root = sample(500L, "daily", dailyFields(7, 55.0, 39.0, 1, "ready"), "hook", "Root只读预览");
        Live r = live(stale, root, null, Power.BATTERY);
        check(r.fallback && r.source == Source.ROOT_PREVIEW && r.mode == Mode.OEM
                && Double.isNaN(r.dailyFusedTemp), "read-only stored source cannot claim daily runtime");
        Reading local = decode(fields("wall", NOW, "elapsed", ELAPSED, "boot", BOOT, "battery", 41.0,
                "batterySource", "battery_sticky", "board", "36.0", "fan", 0.0), Source.APP_LOCAL);
        Live a = live(null, null, local, Power.UNKNOWN);
        check(a.fallback && a.source == Source.APP_LOCAL && a.batterySource == BatterySource.STICKY
                && Double.isNaN(a.dailyFusedTemp), "raw battery fallback isn't daily fusion");
        check(Double.isNaN(a.temperatures()[BOARD]) && temperatureText(a.temperatures()[FAN]).equals("0.0"),
                "numeric strings are not telemetry; measured zero is valid");
        check(decode(fields("hook", "等待注入"), null) == null, "empty Provider sentinel has no observation");
    }

    private static void dailyTriggerExplanations() {
        Reading oldSettings = sample(3_000L, "daily", dailyFields(6, 39.0, 36.0, 1, "request_confirmed"));
        Live pending = live(oldSettings, oldSettings, null, Power.BATTERY);
        check(pending.dailyStatus(true, true) == DailyStatus.WAITING_CONFIG && !pending.dailyConfigAcknowledged,
                "old config cannot confirm a changed threshold or weights");
        check(pending.dailyStatus(true, false) == DailyStatus.DISABLED, "saved off is explicit and not a temperature failure");
        check(pending.dailyStatus(false, false) == DailyStatus.CONFIG_UNAVAILABLE, "unknown settings are not disabled");
        Reading failed = sample(3_000L, "daily", dailyFields(-1, null, 36.0, 0, "config_unavailable"));
        Live bad = live(failed, failed, null, Power.BATTERY);
        check(bad.dailyStatus(true, true) == DailyStatus.HOOK_READ_FAILED, "negative version preserves an explicit read failure");
        check(bad.dailyStatus(true, false) == DailyStatus.DISABLED, "saving while off cannot enable");
        check(bad.dailyStatus(true, true) != DailyStatus.WAITING_CONFIG, "a read failure is not pending");
        Reading idle = sample(19_000L, "daily", dailyFields(7, 36.0, 36.0, 1, "waiting_oem"));
        Live old = live(idle, idle, null, Power.BATTERY);
        check(old.dailyConfigAcknowledged && old.dailyStatus(true, true) == DailyStatus.WAITING_SAMPLE
                && Double.isNaN(old.dailyFusedTemp), "recent config acknowledgement survives without stale live decision");
        check(live(idle, idle, null, Power.EXTERNAL).dailyStatus(true, true) == DailyStatus.EXTERNAL_POWER,
                "external power is a different blocker from config sync");
        String[] states = {"below_threshold", "ready", "waiting_oem", "candidate_applied", "request_observed", "oem_stopped"};
        DailyStatus[] expected = {DailyStatus.TOO_COOL, DailyStatus.WAITING_CANDIDATE, DailyStatus.OEM_ZERO,
                DailyStatus.CANDIDATE_ONLY, DailyStatus.REQUEST_OBSERVED, DailyStatus.OEM_STOPPED};
        for (int i = 0; i < states.length; i++) {
            Reading evidence = sample(1000L, "daily", dailyFields(7, i == 0 ? 35.96 : 36.0, 36.0, 1, states[i]), "effective", 0);
            check(live(evidence, evidence, null, Power.BATTERY).dailyStatus(true, true) == expected[i],
                    "threshold (even when rounded), OEM, candidate and effective zero remain separate: " + states[i]);
        }
    }

    private static void previewUsesOnlyFreshContributingChannels() {
        DailyFanConfig draft = new DailyFanConfig(false, 33.0, 6, new double[]{5.0, 7.0, 0.0, 11.0});
        Reading sticky = sample(2_000L, "daily", dailyFields(1, 51.0, 34.0, 15, "ready"),
                "batterySource", "battery_sticky", "battery", 58.0, "board", 30.0, "mos", 40.0);
        Preview p = preview(draft, sticky, NOW, ELAPSED, BOOT);
        check(p.dataFresh && p.hasTemperature && p.contributorMask == 0b1010 && p.denominator == 18.0,
                "real denominator excludes sticky and zero-weight inputs");
        near(p.fusedTemp, (30.0 * 7.0 + 40.0 * 11.0) / 18.0, "draft fusion, not recorded fusion");
        near(p.proportions()[BOARD], 7.0 / 23.0, "nominal proportion");
        near(p.usedProportions()[BOARD], 7.0 / 18.0, "re-normalized proportion");
        p.usedProportions()[BOARD] = 0;
        near(p.usedProportions()[BOARD], 7.0 / 18.0, "immutable shares");

        Preview noInput = preview(DailyFanConfig.DEFAULT, sticky, NOW, ELAPSED, BOOT);
        check(noInput.dataFresh && !noInput.hasTemperature && Double.isNaN(noInput.fusedTemp)
                && noInput.contributorMask == 0 && noInput.denominator == 0, "fresh doesn't imply a usable input");
        Preview stale = preview(draft, sticky, NOW + 14_000L, ELAPSED + 14_000L, BOOT);
        check(!stale.dataFresh && !stale.hasTemperature && Double.isNaN(stale.fusedTemp), "a retained snapshot ages before preview");
        Reading invalid = sample(2_000L, "oem", null, "battery", 99.0, "board", 0.0, "mos", Double.POSITIVE_INFINITY);
        Preview zero = preview(draft, invalid, NOW, ELAPSED, BOOT);
        check(zero.hasTemperature && zero.contributorMask == 2 && zero.denominator == 7.0 && zero.fusedTemp == 0.0,
                "fusion clips by validity, not by defaulting missing readings to zero");
    }

    private static void trendsUseHistoricalFieldsAndBreaks() {
        Fields d1 = dailyFields(7, 38.0, 34.0, 3, "ready");
        Fields d2 = dailyFields(8, 39.0, 36.0, 3, "candidate_applied");
        Reading a = sample(15 * MIN + 5_000L, "daily", d1, "fused", 52.0, "startTemp", 45, "effective", 5);
        Reading b = sample(15 * MIN, "daily", d1, "fused", 53.0, "startTemp", 46, "effective", 6);
        Reading c = sample(15 * MIN - 5_000L, "daily", d2, "candidate", 12, "request", 12, "effective", null);
        List<TrendPoint> all = trend(Arrays.asList(sample(60 * MIN + 1, "daily", d1), sample(60 * MIN, "daily", d1),
                sample(30 * MIN, "daily", d1), a, b, c, sample(-1L, "daily", d1)), NOW);
        check(all.size() == 5 && all.get(0).breakBefore && !all.get(3).breakBefore, "bounded and contiguous same-source history");
        check(trendWindow(all, NOW, 60).size() == 5 && trendWindow(all, NOW, 30).size() == 4, "60/30-minute windows");
        List<TrendPoint> shortWindow = trendWindow(all, NOW, 15);
        check(shortWindow.size() == 2 && shortWindow.get(0).breakBefore, "inclusive 15-minute boundary starts a new path");
        check(shortWindow.get(0).dailyFusedTemp == 38.0 && shortWindow.get(0).dailyStartTemp == 34.0
                && shortWindow.get(0).effectiveLevel == 6, "trend uses historical nested fields and measured level");
        check(shortWindow.get(1).dailyFusedTemp == 39.0 && shortWindow.get(1).dailyStartTemp == 36.0
                && shortWindow.get(1).breakBefore && Double.isNaN(shortWindow.get(1).effectiveLevel), "no interpolation across config changes");

        Reading prev = sample(10_000L, "daily", d1);
        Reading noDaily = sample(5_000L, "oem", null, "battery", 41.0, "fused", 39.0, "startTemp", 35.0);
        TrendPoint noDailyPoint = trend(Arrays.asList(prev, noDaily), NOW).get(1);
        check(noDailyPoint.breakBefore && Double.isNaN(noDailyPoint.dailyFusedTemp) && Double.isNaN(noDailyPoint.dailyStartTemp),
                "no top-level fusion, battery or current threshold backfill");
        check(breaks(prev, sample(5_000L, "daily", d1, "boot", OTHER_BOOT)), "reboot breaks the line");
        check(breaks(prev, sample(5_000L, "daily", d1, "boot", "")), "unproven boot continuity breaks the line");
        check(breaks(prev, sample(5_000L, "daily", d1, "hook", "Root只读预览")), "source changes break the line");
        check(breaks(prev, sample(5_000L, "daily", d1, "batterySource", "sysfs:battery/temp")), "battery source changes break the line");
        check(breaks(prev, sample(5_000L, "daily", dailyFields(7, 32.0, 34.0, 1, "ready"))), "contributor changes break the line");
        check(breaks(prev, sample(5_000L, "daily", d1, "elapsed", ELAPSED - 10_001L)), "elapsed reset breaks the line");
        check(breaks(sample(65_001L, "daily", d1), sample(5_000L, "daily", d1)), "sampling gap breaks the line");
        Reading nullFusion = sample(5_000L, "daily", dailyFields(7, null, 34.0, 0, "temperature_unavailable"));
        TrendPoint unknown = trend(Arrays.asList(prev, nullFusion), NOW).get(1);
        check(Double.isNaN(unknown.dailyFusedTemp) && unknown.dailyStartTemp == 34.0, "missing series coordinate stays NaN");
    }

    private static void rpmHoldUsesOnlyVerifiedEventFields() {
        Fields held = fields("source", FanRpm.SOURCE, "observer", "attached", "quality", "held", "value", 12000,
                "wall", NOW - 3_600_000L, "elapsed", ELAPSED - 3_600_000L, "level", 10, "generation", 3);
        Reading current = sample(1_000L, "oem", null, "state", 1, "effective", 10, "fanStatus", "NORMAL", "fanRpm", held);
        Live l = live(current, current, null, Power.BATTERY);
        check(l.fanRpm == 12000 && l.fanRpmWall == NOW - 3_600_000L && l.fanRpmLevel == 10,
                "held event can be old but current driver observation must be fresh");
        check(rpmText(l.fanRpm).equals("12000") && rpmStatus(l.fanRpmQuality).contains("保持"), "held display is explicit");
        check(Double.isNaN(live(sample(16_000L, "oem", null, "state", 1, "effective", 10,
                "fanStatus", "NORMAL", "fanRpm", held), null, null, Power.BATTERY).fanRpm), "stale telemetry cannot keep RPM alive");
        for (Object[] override : new Object[][]{{"effective", 11}, {"state", 0}, {"fanStatus", "BLOCKED"},
                {"hookVersion", "old-version"}, {"hook", "Root只读预览"}, {"boot", OTHER_BOOT}}) {
            Reading bad = sample(1_000L, "oem", null, "state", 1, "effective", 10, "fanStatus", "NORMAL", "fanRpm", held,
                    override[0], override[1]);
            check(Double.isNaN(live(bad, bad, null, Power.BATTERY).fanRpm), "mismatch cannot be a held measurement: " + override[0]);
        }
        Reading legacy = sample(1_000L, "oem", null, "rpm", 12000, "effective", 10);
        check(Double.isNaN(live(legacy, legacy, null, Power.BATTERY).fanRpm), "legacy rpm or effective level never backfills measurement");
        Reading blank = sample(500L, "oem", null, "effective", 11);
        List<TrendPoint> chart = trend(Arrays.asList(current, blank), NOW);
        check(chart.get(0).fanRpm == 12000 && Double.isNaN(chart.get(1).fanRpm), "missing next-level sample breaks RPM series");
        check(chart.get(0).fanRpmGeneration == 3 && rpmText(0).equals(MISSING), "generation and false-zero rules");
    }

    private static boolean breaks(Reading a, Reading b) {
        return trend(Arrays.asList(a, b), NOW).get(1).breakBefore;
    }

    private static Live live(Reading hook, Reading stored, Reading local, Power power) {
        return FrontPresentation.live(hook, stored, local, power, NOW, ELAPSED, BOOT, HOOK, 7L, 2L);
    }

    private static Reading sample(long age, String route, Fields daily, Object... overrides) {
        Map<String, Object> m = map("wall", NOW - age, "elapsed", ELAPSED - age, "boot", BOOT,
                "hook", "已注入", "hookVersion", HOOK, "configVersion", 2, "plugged", 0,
                "batteryDischarging", true, "controlMode", route, "daily", daily,
                "batterySource", "thermal:battery", "batteryValid", true,
                "battery", 31.0, "board", 33.0, "fan", 35.0, "mos", 37.0);
        m.putAll(map(overrides));
        return decode(m::get, null);
    }

    private static Fields dailyFields(int version, Double fused, double start, int mask, String state) {
        return fields("version", version, "fusedTemp", fused, "startTemp", start, "contributorMask", mask, "state", state,
                "candidate", 8, "request", 8, "minLevel", 6);
    }

    private static Fields fields(Object... pairs) { return map(pairs)::get; }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static void check(boolean ok, String description) { if (!ok) throw new AssertionError(description); }
    private static void near(double actual, double expected, String description) {
        check(Math.abs(actual - expected) < 1e-10, description + ": " + actual + " != " + expected);
    }
}
