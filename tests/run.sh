#!/bin/sh
set -eu
java_only=false
case "${1-}" in
    --java-only) java_only=true ;;
    "") ;;
    *) echo "usage: $0 [--java-only]" >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || { echo "usage: $0 [--java-only]" >&2; exit 2; }
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/test-classes \
    src/dev/eta/acefan/FusionTemp.java src/dev/eta/acefan/ChargeCurve.java src/dev/eta/acefan/WakeHandoff.java \
    src/dev/eta/acefan/DailyFanConfig.java src/dev/eta/acefan/DailyFanPolicy.java \
    src/dev/eta/acefan/Sampling.java src/dev/eta/acefan/CompatibilityCatalog.java \
    src/dev/eta/acefan/ReportFreshness.java src/dev/eta/acefan/FanServiceCompat.java \
    src/dev/eta/acefan/FrontPresentation.java src/dev/eta/acefan/FanRpm.java src/dev/eta/acefan/RootFgEvent.java src/dev/eta/acefan/FanDebugReport.java \
    src/dev/eta/acefan/KernelProtocol.java src/dev/eta/acefan/KernelReport.java src/dev/eta/acefan/BuildInfo.java \
    src/dev/eta/acefan/ControlCycle.java src/dev/eta/acefan/RuntimeEvidence.java src/dev/eta/acefan/DiagnosticReport.java \
    src/dev/eta/acefan/DirectFanControl.java src/dev/eta/acefan/OemFanRamp.java src/dev/eta/acefan/FanNodeIo.java tests/*.java
for name in RootFgEventTest KernelGateInputsTest KernelHardwareStatusTest DailyFanPolicyTest FusionTempTest ChargeCurveTest WakeHandoffTest SamplingTest CompatibilityTest DiagnosticReportTest RuntimeEvidenceTest ControlCycleTest FrontPresentationTest FanRpmTest DirectFanControlTest OemFanRampTest FanNodeIoTest FanNodeBridgeTest; do
    java -cp build/test-classes "dev.eta.acefan.$name"
done

if [ "$java_only" = false ]; then
    sh tests/test_horae_watchdog.sh
    sh tests/test_oem_fan_compat.sh
fi

# Root relay contract tests: pure JVM + SQLite/static contracts, NOT root/Android execution.
mkdir -p build/test-generated
cat > build/test-generated/KernelRelayContractTest.java <<'JAVA'
package dev.eta.acefan;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class KernelRelayContractTest {
    private static int checks;
    private interface Work { void run() throws Exception; }
    private static void ok(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Work work, String message) throws Exception {
        try { work.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("accepted: " + message);
    }
    private static KernelProtocol.Config config(int mask) {
        KernelProtocol.Settings daily = new KernelProtocol.Settings(true, 30, 12, new double[]{60, 0, 40, 0}, 2, 20);
        KernelProtocol.Settings charge = new KernelProtocol.Settings(true, 33, 6, new double[]{1, 0, 0, 0}, 2, 20);
        return new KernelProtocol.Config(daily, charge, new KernelProtocol.Gates(2, 1, 2, mask, 0), KernelProtocol.ZONES);
    }
    private static FrontPresentation.Fields fields(final Map<String, Object> map) {
        return key -> {
            Object value = map.get(key);
            if (value instanceof Map) return fields((Map<String, Object>)value);
            return value;
        };
    }
    private static Map<String, Object> output(KernelReport report, KernelProtocol.Config cfg) {
        return report.fields(1_710_000_000_000L, 100_000L, "test-boot", BuildInfo.HOOK_VERSION, 2, cfg, 6, 8, "HOST_MOCK_ONLY");
    }
    public static void main(String[] args) throws Exception {
        String[] missingSystem = {"cooling_fan_main_switch", "regular_charge_protection_status", "cooling_fan_charge_switch",
                "cooling_fan_mic_mutual", "rider_mode_main_switch", "super_powersave_mode_state"};
        for (String key : missingSystem) {
            ok("0".equals(KernelProtocol.oemSetting("system", key, null)), "source-proven missing System default: " + key);
            ok(!KernelProtocol.oemMissingEvidence("system", key).startsWith("required"), "exact OEM evidence recorded");
            ok("unexpected".equals(KernelProtocol.oemSetting("system", key, "unexpected")), "malformed read is not missing: " + key);
        }
        ok("false".equals(KernelProtocol.oemSetting("system", "sys_oplus_high_performance_spec", null)), "OEM null high-performance inactive");
        ok("false".equals(KernelProtocol.oemSetting("global", "cooling_fan_service_abort", null)), "OEM null abort inactive");
        ok("0".equals(KernelProtocol.oemSetting("global", "op_audio_mic_status", null)), "OEM Global mic default0");
        ok("0".equals(KernelProtocol.oemSetting("global", "gamemode_delayed", null)), "OEM Global game default0");
        for (String key : new String[]{"cooling_fan_main_switch_modify", "cooling_fan_mode_v3", "cooling_fan_mode_auto_control_switch", "unknown"})
            ok(KernelProtocol.oemSetting("system", key, null) == null, "required/unverified key never defaults: " + key);
        ok(KernelProtocol.oemSetting("global", "regular_charge_protection_status", null) == null, "wrong namespace never defaults");
        KernelProtocol.Config cfg = config(127);
        String line = cfg.line(12, true);
        String[] t = line.trim().split(" ");
        ok(t.length == 31, "v2 = 27 integers + four channels");
        ok(line.equals("2 12 1 5000 2 1 2 127 1 0 2 30000 12 600000 0 400000 0 2 33000 6 2000 20 10000 0 0 0 2 battery board_temp fan_temp svooc_mos_btb_usr\n"), "exact canonical encoding + Provider channel order");
        ok(KernelProtocol.snapshotRevision(line) == 12, "v2 snapshot revision");
        ok(KernelProtocol.snapshotRevision(line.replace(" 2 battery", " battery").replaceFirst("2 ", "1 ")) == 12, "v1 read compatibility");
        ok(KernelProtocol.snapshotRevision("UNCONFIGURED protocol=2 strategy=0 enabled=0\n") == 0, "unconfigured snapshot");
        ok(KernelProtocol.nextRevision(33, 99) == 100 && KernelProtocol.nextRevision(99, 33) == 100, "disk/kernel max + 1");
        rejects(() -> KernelProtocol.nextRevision(KernelProtocol.MAX_REVISION, 1), "revision overflow");
        rejects(() -> KernelProtocol.snapshotRevision(line.replaceFirst("2 ", "3 ")), "unknown wire version");
        rejects(() -> KernelProtocol.snapshotRevision(line + "extra"), "extra token");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace(" 5000 ", " -1 ")), "negative lease");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace(" 5000 ", " 5001 ")), "unbounded lease");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace("2 12 ", "2 4294967296 ")), "uint overflow");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace(" 600000 ", " 0 ").replace(" 400000 ", " 0 ")), "zero weights");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace("board_temp", "Battery_shell")), "fake thermal channel");
        rejects(() -> KernelProtocol.snapshotRevision(line.replace("board_temp", "battery")), "duplicate channels");
        rejects(() -> new KernelProtocol.Settings(true, Double.NaN, 1, new double[]{1,0,0,0}, 1, 20), "NaN parameter");
        rejects(() -> new KernelProtocol.Settings(true, 30, 21, new double[]{1,0,0,0}, 1, 20), "bad floor");
        rejects(() -> new KernelProtocol.Settings(true, 30, 1, new double[]{0,0,0,0}, 1, 20), "no contributing weight");
        for (String v : new String[]{null, "", "garbage", "true", "01", "-1"}) {
            ok(KernelProtocol.mainEnabled(v) == 0 && KernelProtocol.yesNo(v) == 0 && KernelProtocol.mode(v) == 0, "unknown enum refuses permission");
            ok(!KernelProtocol.zero(v) && !KernelProtocol.uiClear(v), "unknown gate never clear");
        }
        ok(!KernelProtocol.falseText(null) && KernelProtocol.falseText("false"), "abort missing isn't clear");
        ok(KernelProtocol.mainEnabled("3") == 2 && KernelProtocol.mainEnabled("0") == 1 && KernelProtocol.mode("1") == 2, "OEM main and manual mapping");
        for (int mask = 0; mask < 127; mask++) {
            final KernelProtocol.Config blocked = config(mask);
            ok(!blocked.allowed(), "every missing gate bit rejects");
            rejects(() -> blocked.line(1, true), "enabled incomplete gates");
            ok(KernelProtocol.snapshotRevision(blocked.line(1, false)) == 1, "disabled snapshots remain syntactically complete");
        }
        for (int status = -1; status <= 6; status++) ok(new KernelProtocol.Gates(2, 1, 2, 127, status).clear() == (status == 0), "thermal unknown or adverse blocks");
        for (int mode = 0; mode <= 3; mode++) ok(new KernelProtocol.Gates(2, mode, 2, 127, 0).clear() == (mode == 1), "manual or unknown blocks");
        KernelProtocol.Lease lease = new KernelProtocol.Lease(100, 200);
        final List<Long> persisted = new ArrayList<>();
        String disabled = lease.prepare(cfg, false, persisted::add);
        ok(persisted.equals(Arrays.asList(201L)), "initial disabled is durable revision max+1");
        ok(disabled.equals(lease.prepare(cfg, false, persisted::add)) && persisted.size() == 1, "unchanged disabled no revision churn");
        String active = lease.prepare(cfg, true, persisted::add);
        for (int i = 0; i < 50; i++) ok(active.equals(lease.prepare(config(127), true, persisted::add)), "same-revision renewals byte-identical");
        ok(persisted.equals(Arrays.asList(201L, 202L)), "50 renewals do not advance revision");
        String revoked = lease.prepare(config(126), false, persisted::add);
        ok(!active.equals(revoked) && lease.revision() == 203, "gates/config change advances revision");
        try { lease.prepare(cfg, true, revision -> { throw new java.io.IOException("disk blocked"); }); throw new AssertionError("journal failure accepted"); }
        catch (java.io.IOException expected) { checks++; }
        ok(lease.revision() == 203, "failed persistence does not publish a new revision");
        ok(KernelProtocol.writerFresh(10000, 10000, 10000, 10000, 11000), "all source lanes fresh");
        ok(!KernelProtocol.writerFresh(10000, 13500, 13500, 13500, 13500), "blocked Provider/safety worker cannot renew indefinitely");
        ok(!KernelProtocol.writerFresh(12000, 10000, 12000, 12000, 12000), "stuck callbacks revoke authorization");
        ok(!KernelProtocol.writerFresh(20000, 20000, 10000, 20000, 20000), "blocked status read expires");
        ok(!KernelProtocol.writerFresh(23000, 23000, 23000, 10000, 23000), "blocked root publication expires");
        ok(!KernelProtocol.writerFresh(10000, 10000, 0, 10000, 10000), "unknown source not fresh");
        ok(KernelProtocol.rootPublisher(0) && !KernelProtocol.rootPublisher(1000) && !KernelProtocol.rootPublisher(10123), "root UID boundary");
        ok(KernelProtocol.publisherFresh(10000, 21999) && !KernelProtocol.publisherFresh(10000, 22000)
                && !KernelProtocol.publisherFresh(0, 1) && !KernelProtocol.publisherFresh(10000, 9999), "publisher lease current/expired/boot boundary");

        // Literal copied from the stage3 C host-harness output, explicitly NOT a device sample.
        // Keep pure tests usable without rebuilding an ignored native candidate directory.
        String fixture = "NONRELEASE protocol=2 configProtocol=2 live=1 blocked=0 configured=1 owned=1 fault=0 leaseUntil=6000 configRevision=2 ownerEpoch=1 strategy=2 ready=1 target=12 output=6 requestedLevel=6 physicalState=1 effective=2 readbackMask=7 tempValidMask=15 temp0=31000 temp1=31000 temp2=31000 temp3=31000 sampleAtMs=1000 nowMs=1000 power=1 powerEpoch=1 sourcePresentMask=1 sourceOnlineMask=0 sourceFailedMask=0 batteryStatus=2 batteryPresent=1 reason=0";
        Path hostFixture = Paths.get("build/kernel-replace-audit/driver-relink/stage3-independent/relay-fixtures.txt");
        if (Files.isRegularFile(hostFixture)) for (String s : Files.readAllLines(hostFixture, StandardCharsets.UTF_8))
            if (s.startsWith("full_owner.status=")) fixture = s.substring("full_owner.status=".length());
        final String status = fixture.replaceAll(" (fusionMc|fusionValid|demand)=-?[0-9]+", "");
        KernelReport report = KernelReport.parse(status);
        ok(report.ready() && report.get("target") == 12 && report.get("effective") == 2, "target/output/effective remain separate");
        Map<String, Object> row = output(report, cfg);
        Map<String, Object> node = (Map<String, Object>)row.get("nodeControl");
        ok("内核控制".equals(row.get("hook")) && BuildInfo.HOOK_VERSION.equals(row.get("hookVersion")), "label and current build version");
        ok(((Number)row.get("battery")).doubleValue() == 31 && ((Number)row.get("board")).doubleValue() == 31, "four mC channels -> existing C fields");
        ok(row.get("fused") == null && ((Map<?, ?>)row.get("daily")).get("fusedTemp") == null, "not in status means no invented fused temperature");
        ok(node.get("target").equals(12L) && node.get("output").equals(6L) && row.get("effective").equals(2L), "actual feedback not target substituted");
        ok(!row.containsKey("rpm") && !row.containsKey("speed"), "no target-speed invention");
        FrontPresentation.Reading reading = FrontPresentation.decode(fields(row), null);
        FrontPresentation.Live live = FrontPresentation.live(reading, reading, null, FrontPresentation.Power.BATTERY,
                1_710_000_000_000L, 100_000, "test-boot", BuildInfo.HOOK_VERSION, 6, 8);
        ok(live.source == FrontPresentation.Source.HOOK && live.hookFresh && !live.needsReload && reading.kernel, "trusted controller category, not injection label");
        ok(live.dailyStatus(true, true) == FrontPresentation.DailyStatus.KERNEL_CONTROL && live.dailyConfigAcknowledged, "daily route/config ack intact");
        FrontPresentation.Live expired = FrontPresentation.live(reading, reading, null, FrontPresentation.Power.BATTERY,
                1_710_000_012_000L, 112_000, "test-boot", BuildInfo.HOOK_VERSION, 6, 8);
        ok(!expired.hookFresh && !expired.telemetryFresh, "root expired doesn't masquerade as long-lived Hook");
        ok(FrontPresentation.freshRoot(reading, 1_710_000_000_000L, 100_000, "test-boot")
                && !FrontPresentation.freshRoot(reading, 1_710_000_012_000L, 112_000, "test-boot"), "freshRoot source arbitration boundary");
        KernelReport charging = KernelReport.parse(status.replace("power=1", "power=2").replace("sourceOnlineMask=0", "sourceOnlineMask=1")
                .replace("batteryStatus=2", "batteryStatus=1"));
        FrontPresentation.Reading cr = FrontPresentation.decode(fields(output(charging, cfg)), null);
        FrontPresentation.Live cl = FrontPresentation.live(cr, cr, null, FrontPresentation.Power.WIRED_CHARGING,
                1_710_000_000_000L, 100_000, "test-boot", BuildInfo.HOOK_VERSION, 6, 8);
        ok(cl.mode == FrontPresentation.Mode.CHARGE && cl.chargeActive && !cl.chargeVersionPending, "charge route/current settings acknowledgement intact");
        Map<String, Object> distinct = output(KernelReport.parse(status.replace("temp1=31000", "temp1=33000")
                .replace("temp2=31000", "temp2=35000").replace("temp3=31000", "temp3=37000")), cfg);
        ok(distinct.get("board").equals(33.0) && distinct.get("fan").equals(35.0) && distinct.get("mos").equals(37.0), "distinct thermal channels cannot silently reorder");
        Map<String, Object> legacy = new LinkedHashMap<>(row); legacy.put("hook", "已注入");
        ok(FrontPresentation.decode(fields(legacy), null).source == FrontPresentation.Source.HOOK, "legacy marker preserved");
        legacy.put("hook", "内核控制-假冒");
        ok(FrontPresentation.decode(fields(legacy), null).source == FrontPresentation.Source.UNKNOWN, "new marker exact, not prefix");
        legacy.put("hook", "Root只读预览");
        ok(FrontPresentation.decode(fields(legacy), null).source == FrontPresentation.Source.ROOT_PREVIEW, "preview source preserved");
        Map<String, Object> mismatch = report.fields(1, 1, "b", "v", 99, cfg, 6, 8, "test");
        ok(mismatch.get("configVersion").equals(-1L) && ((Map<?, ?>)mismatch.get("daily")).get("version").equals(-1L), "revision mismatch cannot acknowledge today's settings");
        KernelReport stale = KernelReport.parse(status.replace("nowMs=1000", "nowMs=8000"));
        Map<String, Object> old = output(stale, cfg);
        ok(!stale.ready() && old.get("battery") == null && ((Map<?, ?>)old.get("nodeControl")).get("target") == null, "stale temps/target never filled with zero");
        Map<String, Object> off = output(KernelReport.parse(status.replace("physicalState=1", "physicalState=0").replace("output=6", "output=0")), cfg);
        ok(off.get("effective").equals(0L) && off.get("driver").equals(6L), "state0 not mistaken for positive retained level");
        Map<String, Object> extended = output(KernelReport.parse(status + " fusionMc=34678 fusionValid=1 demand=12"), cfg);
        Map<?, ?> extendedDay = (Map<?, ?>)extended.get("daily");
        ok(extendedDay.get("fusedTemp").equals(34.678) && extendedDay.get("demand").equals(12L), "optional kernel fusion/demand -> daily display without App calculation");
        Map<String, Object> chargeExtended = output(KernelReport.parse(status.replace("power=1", "power=2")
                .replace("sourceOnlineMask=0", "sourceOnlineMask=1") + " fusionMc=35890 fusionValid=1 demand=14"), cfg);
        ok(chargeExtended.get("fused").equals(35.89), "optional kernel fusion -> existing charge field/graph");
        ok(((Map<?, ?>)output(KernelReport.parse(status + " fusionMc=34678 fusionValid=0"), cfg).get("daily")).get("fusedTemp") == null,
                "invalid fusion stays missing");
        ok(((Map<?, ?>)output(KernelReport.parse(status + " fusionMc=34678"), cfg).get("daily")).get("fusedTemp") == null,
                "missing fusion-valid flag stays missing");
        Map<String, Object> rootOldApk = new LinkedHashMap<>(extended); rootOldApk.put("hookVersion", "previous-relay-apk");
        FrontPresentation.Reading oldRoot = FrontPresentation.decode(fields(rootOldApk), null);
        ok(!FrontPresentation.live(oldRoot, oldRoot, null, FrontPresentation.Power.BATTERY, 1_710_000_000_000L, 100_000,
                "test-boot", BuildInfo.HOOK_VERSION, 6, 8).needsReload, "current root controller is not an old injected Hook");
        rootOldApk.put("hook", "已注入");
        FrontPresentation.Reading oldHook = FrontPresentation.decode(fields(rootOldApk), null);
        ok(FrontPresentation.live(oldHook, oldHook, null, FrontPresentation.Power.BATTERY, 1_710_000_000_000L, 100_000,
                "test-boot", BuildInfo.HOOK_VERSION, 6, 8).needsReload, "legacy Hook version rule unchanged");
        rejects(() -> KernelReport.parse(status + " fusionValid=2"), "invalid optional fusion authority");
        ok(KernelReport.parse(status + " future=string-value").ready(), "future diagnostic keys allowed");
        rejects(() -> KernelReport.parse(status.replace("protocol=2", "protocol=3")), "unknown status protocol");
        rejects(() -> KernelReport.parse(status + " ready=1"), "duplicate authority");
        rejects(() -> KernelReport.parse(status.replace(" temp2=31000", "")), "missing thermal field");
        rejects(() -> KernelReport.parse(status.replace("effective=2", "effective=99")), "invalid level range");
        Path stage4 = Paths.get("build/kernel-replace-audit/driver-relink/stage4-first-control/relay-fixtures.txt");
        if (Files.isRegularFile(stage4)) {
            int actual = 0;
            for (String fixtureLine : Files.readAllLines(stage4, StandardCharsets.UTF_8)) {
                int equal = fixtureLine.indexOf(".status=");
                if (equal < 0) continue;
                String raw = fixtureLine.substring(equal + ".status=".length());
                KernelReport actualReport = KernelReport.parse(raw);
                ok(raw.contains("candidateLabel=CONTROL_TEST_PENDING"), "actual stage4 candidate label parsed, NONRELEASE prefix preserved");
                if (fixtureLine.startsWith("full_owner.status=")) {
                    Map<String,Object> actualRow = output(actualReport, cfg);
                    ok(actualReport.ready() && ((Map<?,?>)actualRow.get("daily")).get("fusedTemp").equals(31.0), "actual stage4 kernel fusion -> existing daily display");
                    ok(((Map<?,?>)actualRow.get("daily")).get("demand").equals(12L), "actual stage4 kernel demand mapping");
                } else ok(!actualReport.ready(), "actual stage4 disabled fixture not ready");
                actual++;
            }
            ok(actual == 2, "both real stage4 host-harness status fixtures exercised");
            System.out.println("Stage4ActualFixture: OK " + stage4 + "; HOST_MOCK_ONLY_NOT_DEVICE");
        }
        System.out.println("KernelRelayContractTest: OK " + checks + " assertions; host mocks/contracts only, NOT Android/root verification");
    }
}
JAVA
javac --release 8 -Xlint:-options -encoding UTF-8 -cp build/test-classes -d build/test-classes build/test-generated/KernelRelayContractTest.java
java -cp build/test-classes dev.eta.acefan.KernelRelayContractTest
python3 - <<'PY'
from pathlib import Path
import re, sqlite3, json
p=Path('src/dev/eta/acefan/DataProvider.java').read_text()
r=Path('src/dev/eta/acefan/KernelSettingsRelay.java').read_text()
# These are static/SQLite contracts, not an Android Binder/SQLiteOpenHelper emulator.
assert 'if (!KernelProtocol.rootPublisher(caller())) throw new SecurityException' in p
assert p.index('if (!KernelProtocol.rootPublisher(caller()))') < p.index('synchronized (pushLock)', p.index('if ("push".equals(method)'))
a=p.index('synchronized (pushLock)', p.index('if ("push".equals(method)'))
b=p.index('if ("setConfig".equals(method))', a)
block=p[a:b]
assert block.index('d.insertOrThrow') < block.index('rootPublisherAt = now')
assert block.index('KernelProtocol.publisherFresh') < block.index('validJson(json)')
assert 'dropped_by_kernel_backend' in block and 'kernel telemetry must use UID0 pushRoot' in block
assert 'notifyChange(CONFIG_URI, null)' in p and 'register.invoke(resolver, CONFIG, false, observer, 0)' in r
assert 'if (root) KernelReport.parse(node.optString("statusLine", ""));' in p
h=Path('src/dev/eta/acefan/FanHook.java').read_text()
assert 'KernelSettingsRelay.kernelPolicyPresent' not in h
assert 'if (!status.canRead()) return kernelBackendReported;' in h and 'KernelReport.parse(new String(b, 0, n))' in h
assert '"content"' not in r and 'Runtime.getRuntime().exec' not in r and 'ProcessBuilder' not in r
assert r.count('Os.write(fd, bytes, 0, bytes.length)') == 1
assert 'readProvider();' in r and 'meta = null; // A failed replacement read' in r
assert 'System.getInt(' not in r and 'Global.getInt(' not in r # no permissive missing-value defaults
assert not re.search(r'Settings\.(System|Global)\.put',r)
assert 'getVendorId() == 8921 && d.getProductId() == 14443' in r
assert 'getActiveRecordingConfigurations()' in r and 'addThermalStatusListener' in r
assert 'args.length == 1 && "--probe".equals(args[0])' in r
main=r[r.index('public static void main'):r.index('private static Context systemContext')]
assert main.index('if (probe || listeners)') < main.index('new KernelSettingsRelay(')
probe=r[r.index('private static Map<String, Object> probe(Context'):r.index('private KernelSettingsRelay(Context')]
for forbidden in ['PolicyFiles.discover', 'new RevisionStore', 'Os.write', 'read(files.', 'registerReceiver(', 'registerListener(', 'addThermalStatusListener(']:
    assert forbidden not in probe, forbidden
assert 'providers.call(API.getAuthority(), "config", null, null)' in probe and 'DailyFanContract.READ' in probe
assert 'System.exit(code); return;' in main

f=Path('src/dev/eta/acefan/FrontModel.java').read_text()
assert f.index('FrontPresentation.freshRoot(latest') < f.index('for (int i = rows.size() - 1;',f.index('FrontPresentation.freshRoot(latest'))
schema=re.search(r'CREATE_DAILY = "([^"]+)"',p).group(1)
db=sqlite3.connect(':memory:');db.execute(schema)
insert='INSERT INTO daily_config VALUES(1,1,7,30.0,12,60.0,0.0,40.0,0.0)'
db.execute(insert)
select=re.search(r'SELECT_DAILY = "([^"]+)"',p).group(1)
assert db.execute(select).fetchone()==(1,7,30.0,12,60.0,0.0,40.0,0.0)
for sql in ['UPDATE daily_config SET w_battery=0,w_fan=0', 'UPDATE daily_config SET min_level=21', 'UPDATE daily_config SET enabled=2']:
    try: db.execute(sql)
    except sqlite3.IntegrityError: pass
    else: raise AssertionError('invalid settings accepted')
db.execute('CREATE TABLE sample(id INTEGER PRIMARY KEY,wall INTEGER,elapsed INTEGER,json TEXT)')
db.execute('INSERT INTO sample(wall,elapsed,json) VALUES(?,?,?)',(999999,9000,json.dumps({'hook':'已注入'})))
db.execute('INSERT INTO sample(wall,elapsed,json) VALUES(?,?,?)',(10000,10000,json.dumps({'hook':'内核控制'})))
# Fresh root latest is insertion order, not an older Hook's future/skewed wall timestamp.
assert json.loads(db.execute('SELECT json FROM sample ORDER BY id DESC LIMIT 1').fetchone()[0])['hook']=='内核控制'
# After the short lease, the existing channel resumes and can become the latest normal record.
db.execute('INSERT INTO sample(wall,elapsed,json) VALUES(?,?,?)',(1000000,23000,json.dumps({'hook':'已注入'})))
assert json.loads(db.execute('SELECT json FROM sample ORDER BY wall DESC,id DESC LIMIT 1').fetchone()[0])['hook']=='已注入'
print('KernelProviderSqliteContract: OK; static UID/arbitration + real SQLite schema/order; NOT Binder/device verification')
PY
python3 tests/test_root_provider_access.py
