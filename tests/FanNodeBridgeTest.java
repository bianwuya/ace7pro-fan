package dev.eta.acefan;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** No Android, Xposed runtime, file IO, or hardware nodes: both transports are in-memory fakes. */
public final class FanNodeBridgeTest {
    static class Controller {
        public void i(int value) {}
        private static final class b {
            private static boolean n(int value) { return true; }
            private static void l(boolean value) {}
            private static String i() { return "0"; }
        }
        private static final class WrongState {
            private static boolean n(int value) { return true; }
            private static boolean l(boolean value) { return true; }
            private static String i() { return "0"; }
        }
        private static final class PublicLevel {
            public static boolean n(int value) { return true; }
            private static void l(boolean value) {}
            private static String i() { return "0"; }
        }
        private static final class InstanceLevel {
            private boolean n(int value) { return true; }
            private static void l(boolean value) {}
            private static String i() { return "0"; }
        }
        private static final class WrongRead {
            private static boolean n(int value) { return true; }
            private static void l(boolean value) {}
            private static int i() { return 0; }
        }
    }
    static final class Files implements FanNodeIo.Access {
        final FanNodeIoTest.Memory hardware = new FanNodeIoTest.Memory();
        boolean levelWritable = true, stateWritable = true, failCapability;
        int capabilities, attempts;
        public String read(String name) throws IOException { return hardware.read(name); }
        public boolean writable(String name) {
            capabilities++;
            if (failCapability) throw new SecurityException("test capability denied");
            if ("level".equals(name)) return levelWritable;
            if ("state".equals(name)) return stateWritable;
            throw new AssertionError("unexpected file capability " + name);
        }
        public int write(String name, String value) throws IOException {
            attempts++;
            return hardware.write(name, value);
        }
    }
    static final class Original implements DirectFanControl.OriginalCall {
        final DirectFanControl.Endpoints exact;
        final FanNodeIoTest.Memory hardware;
        final List<String> calls = new ArrayList<String>();
        boolean reject, applyLevel = true, applyState = true, failBefore, failAfter, wrongLevelResult, wrongStateResult;
        boolean nullLevelResult;
        Original(DirectFanControl.Endpoints exact, FanNodeIoTest.Memory hardware) { this.exact = exact; this.hardware = hardware; }
        public Object invoke(Method method, Object[] arguments) throws Throwable {
            if (arguments.length != 1) throw new AssertionError("not exact fan argument list");
            if (method.equals(exact.level)) {
                if (!(arguments[0] instanceof Integer)) throw new AssertionError("not int level");
                calls.add("n=" + arguments[0]);
                if (failBefore) throw new IOException("test native level exception before write");
                if (applyLevel) hardware.write("level", String.valueOf(arguments[0]));
                if (failAfter) throw new IOException("test native level exception after write");
                return nullLevelResult ? null : wrongLevelResult ? Integer.valueOf(1) : Boolean.valueOf(!reject);
            }
            if (method.equals(exact.state)) {
                if (!(arguments[0] instanceof Boolean)) throw new AssertionError("not boolean state");
                calls.add("l=" + arguments[0]);
                if (failBefore) throw new IOException("test native state exception before write");
                if (applyState) hardware.write("state", Boolean.TRUE.equals(arguments[0]) ? "1" : "0");
                if (failAfter) throw new IOException("test native state exception after write");
                return wrongStateResult ? Boolean.TRUE : null; // the audited endpoint really returns void
            }
            throw new AssertionError("non-fan/original read method invoked: " + method);
        }
    }
    static final class Setup {
        final Files files = new Files();
        final Original original;
        final FanNodeIo.RoutedAccess route = new FanNodeIo.RoutedAccess(files);
        final FanNodeIo nodes = new FanNodeIo(route);
        final DirectFanControl control = new DirectFanControl(nodes);
        Setup(DirectFanControl.Endpoints exact, boolean writable) {
            files.levelWritable = files.stateWritable = writable;
            original = new Original(exact, files.hardware);
            route.bindBridge(DirectFanControl.nodeBridge(exact, original));
        }
        void probe() { yes(nodes.probe(20), "node probe"); }
        void init() { yes(control.initialize(20), "control initialized"); }
    }
    interface Throwing { void run() throws Exception; }
    private static int checks;
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object expected, Object actual, String label) {
        checks++; if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    private static void denied(Throwing call, String label) throws Exception {
        try { call.run(); throw new AssertionError(label); }
        catch (IOException expected) { checks++; }
    }
    private static void noBind(FanNodeIo.RoutedAccess route, FanNodeIo.NodeBridge bridge) {
        try { route.bindBridge(bridge); throw new AssertionError("transport rebound"); }
        catch (IllegalStateException expected) { checks++; }
    }
    private static void apply(Setup setup, int level) {
        yes(setup.control.level(level), "level through original transport");
        yes(setup.control.state(true), "state through original transport");
        setup.control.requested(level, true);
        yes(setup.control.status().active, "active only after full readback");
    }
    public static void main(String[] args) throws Exception {
        final DirectFanControl.Endpoints exact = DirectFanControl.endpoints(Controller.class, Controller.b.class);
        for (Class<?> wrong : new Class<?>[] { Controller.WrongState.class, Controller.PublicLevel.class, Controller.InstanceLevel.class, Controller.WrongRead.class }) {
            try { DirectFanControl.endpoints(Controller.class, wrong); throw new AssertionError("loosened exact signature: " + wrong); }
            catch (NoSuchMethodException expected) { checks++; }
        }
        Setup direct = new Setup(exact, true);
        eq("unselected", direct.route.transport(), "status does not select route");
        eq(0, direct.files.capabilities, "binding is not capability probing");
        eq(0, direct.original.calls.size(), "binding invokes no native writer");
        direct.init();
        eq(FanNodeIo.DIRECT_TRANSPORT, direct.control.status().transport, "direct transport preferred");
        eq(2, direct.files.capabilities, "one level/state capability pass");
        eq(0, direct.files.attempts, "no file write probe");
        eq(0, direct.original.calls.size(), "no original-method write probe");
        yes(!direct.control.status().active, "probe does not report actual write success");
        yes(direct.control.status().reason.contains("writes not yet verified"), "readiness distinguished from completed write");
        yes(direct.control.initialize(20), "idempotent initialize");
        yes(direct.route.writable("level") && direct.route.writable("state"), "selected route stays usable");
        eq(2, direct.files.capabilities, "no repeated capability polling");
        noBind(direct.route, DirectFanControl.nodeBridge(exact, direct.original));
        yes(direct.control.prepare("daily", 20, 100_000), "direct policy");
        apply(direct, 6);
        eq("direct-sysfs", direct.control.status().backend, "only writable file route is direct-sysfs");
        eq(2, direct.files.attempts, "direct level plus state writes");
        eq(0, direct.original.calls.size(), "preferred direct route never tries bridge");

        for (String deniedNode : new String[] { "level", "state", "both" }) {
            Setup bridged = new Setup(exact, true);
            if (!"state".equals(deniedNode)) bridged.files.levelWritable = false;
            if (!"level".equals(deniedNode)) bridged.files.stateWritable = false;
            bridged.init();
            eq(FanNodeIo.OEM_TRANSPORT, bridged.control.status().transport, "bridge selected when " + deniedNode + " denied");
            eq("legacy-oem", bridged.control.status().backend, "no ownership merely from route selection");
            eq(2, bridged.files.capabilities, "exactly one route decision");
            eq(0, bridged.files.attempts, "bridge init makes no file write attempt");
            eq(0, bridged.original.calls.size(), "bridge init makes no native write attempt");
            bridged.files.levelWritable = bridged.files.stateWritable = true;
            yes(bridged.control.prepare("charge", 20, 200_000), "bridged software upstream policy");
            eq(6, bridged.control.target(200_000), "OEM ramp still starts at6");
            apply(bridged, 6);
            eq("software-oem-node-bridge", bridged.control.status().backend, "honest software backend label");
            eq(FanNodeIo.OEM_TRANSPORT, bridged.control.status().transport, "transport reported independently");
            eq("[n=6, l=true]", bridged.original.calls.toString(), "exact originals once, in level/state order");
            eq(0, bridged.files.attempts, "never mix file and bridge writes");
            eq(2, bridged.files.capabilities, "permission change does not reselect");
            yes(bridged.nodes.level(6), "same requested level no duplicate");
            yes(bridged.nodes.state(true), "same state no duplicate");
            eq(2, bridged.original.calls.size(), "no duplicate original writes");
            bridged.files.hardware.effective = 2;
            yes(bridged.nodes.inspect() != null, "native driver derate is accepted");
            eq(2, bridged.nodes.last().effective, "real derated effective level");
            eq(2, bridged.original.calls.size(), "never backfill driver derate");
            yes(bridged.nodes.stop(), "bridge stop");
            eq("[n=6, l=true, l=false]", bridged.original.calls.toString(), "stop uses state only");
            eq(6, bridged.nodes.last().level, "stop keeps prior verified request as diagnostic");
            eq(-1, bridged.nodes.last().effective, "state-only stop cannot invent effective level");
            yes(bridged.nodes.stop(), "already off only reads state");
            eq(3, bridged.original.calls.size(), "no duplicate native disable");
        }

        Files unavailableFiles = new Files(); unavailableFiles.levelWritable = unavailableFiles.stateWritable = false;
        FanNodeIo.RoutedAccess unavailableRoute = new FanNodeIo.RoutedAccess(unavailableFiles);
        FanNodeIo unavailable = new FanNodeIo(unavailableRoute);
        yes(!unavailable.probe(20), "no bridge and file denied fails closed");
        unavailableFiles.levelWritable = unavailableFiles.stateWritable = true;
        yes(!unavailable.probe(20), "failed probe not retried");
        yes(!unavailableRoute.writable("level"), "unavailable route remains unavailable");
        eq("unavailable", unavailableRoute.transport(), "no mislabelled direct transport");
        eq(2, unavailableFiles.capabilities, "unavailable selection only once");
        eq(0, unavailableFiles.attempts, "unavailable path writes nothing");
        noBind(unavailableRoute, DirectFanControl.nodeBridge(exact, direct.original));

        Setup capabilityException = new Setup(exact, true); capabilityException.files.failCapability = true;
        yes(!capabilityException.nodes.probe(20), "uncertain capability check fails closed");
        capabilityException.files.failCapability = false;
        yes(!capabilityException.nodes.probe(20), "capability exception not a bridge retry trigger");
        eq(0, capabilityException.original.calls.size(), "no bridge on failed capability query");
        eq(0, capabilityException.files.attempts, "capability failure never probes by writing");

        for (String failure : new String[] { "short", "exception", "mismatch", "readback" }) {
            Setup failedFile = new Setup(exact, true); failedFile.probe();
            failedFile.files.hardware.failNode = "level";
            failedFile.files.hardware.shortWrite = "short".equals(failure);
            failedFile.files.hardware.throwWrite = "exception".equals(failure);
            failedFile.files.hardware.applyOnError = true;
            failedFile.files.hardware.ignoreWrite = "mismatch".equals(failure);
            failedFile.files.hardware.failAfterWrite = "readback".equals(failure);
            int reads = failedFile.files.hardware.reads;
            yes(!failedFile.nodes.level(6), "failed file " + failure + " latched");
            yes(failedFile.files.hardware.reads > reads + 4, "file failure still attempts real readback");
            yes(!failedFile.nodes.level(6), "no blind repeat of failed file " + failure);
            yes(!failedFile.nodes.state(true), "no enable after failed file " + failure);
            eq(1, failedFile.files.attempts, "only one direct file attempt");
            eq(0, failedFile.original.calls.size(), "never retry failed file through OEM bridge");
            eq(1L, failedFile.nodes.writes(), "one recorded attempt, not both transports");
            eq(FanNodeIo.DIRECT_TRANSPORT, failedFile.route.transport(), "failed direct route remains selected");
            if ("readback".equals(failure)) yes(failedFile.nodes.last() == null, "failed readback clears stale pre-write values");
        }

        for (boolean applied : new boolean[] { false, true }) {
            Setup rejected = new Setup(exact, false); rejected.probe();
            rejected.original.reject = true; rejected.original.applyLevel = applied;
            int reads = rejected.files.hardware.reads;
            yes(!rejected.nodes.level(6), "native boolean false fails even when matching=" + applied);
            yes(rejected.files.hardware.reads >= reads + 8, "native false always reads physical nodes back");
            eq(applied ? 6 : 10, rejected.nodes.last().level, "actual readback retained despite native false");
            yes(rejected.nodes.error().contains("OEM fan level rejected"), "false-result diagnostic");
            yes(!rejected.nodes.level(6), "native false not blindly retried");
            yes(!rejected.nodes.state(true), "native false cannot enable");
            eq(1, rejected.original.calls.size(), "native false only one original call");
            eq(0, rejected.files.attempts, "native false never retried through files");
        }
        for (boolean after : new boolean[] { false, true }) {
            Setup threw = new Setup(exact, false); threw.probe();
            threw.original.failBefore = !after; threw.original.failAfter = after;
            int reads = threw.files.hardware.reads;
            yes(!threw.nodes.level(6), "native exception fails, after-write=" + after);
            yes(threw.files.hardware.reads >= reads + 8, "native exception still reads physical nodes");
            eq(after ? 6 : 10, threw.nodes.last().level, "native exception readback is real");
            yes(!threw.nodes.level(6), "native exception not retried");
            eq(1, threw.original.calls.size(), "one native exception attempt");
            eq(0, threw.files.attempts, "native exception no direct fallback write");
            eq(FanNodeIo.OEM_TRANSPORT, threw.route.transport(), "native exception cannot change route");
        }
        for (boolean nullResult : new boolean[] { false, true }) {
            Setup wrong = new Setup(exact, false); wrong.probe();
            wrong.original.wrongLevelResult = !nullResult; wrong.original.nullLevelResult = nullResult;
            yes(!wrong.nodes.level(6), "unexpected native boolean return is not success");
            yes(wrong.nodes.error().contains("non-boolean"), "return type failure explicit");
            eq(6, wrong.nodes.last().level, "invalid native return still reads actual level");
            eq(1, wrong.original.calls.size(), "invalid native return one call");
            eq(0, wrong.files.attempts, "invalid native return no second transport");
        }
        Setup ignored = new Setup(exact, false); ignored.probe(); ignored.original.applyLevel = false;
        yes(!ignored.nodes.level(6), "native boolean true alone not physical success");
        eq(10, ignored.nodes.last().level, "ignored native write real request");
        eq(1, ignored.original.calls.size(), "native mismatch no repeat");
        eq(0, ignored.files.attempts, "native mismatch no file retry");

        for (String failure : new String[] { "mismatch", "readback", "exception", "wrong-return" }) {
            Setup state = new Setup(exact, false); state.probe();
            yes(state.nodes.level(6), "prepare positive level before state failure");
            if ("mismatch".equals(failure)) state.original.applyState = false;
            if ("readback".equals(failure)) state.files.hardware.failReadNodeAfterWrite = "state";
            if ("exception".equals(failure)) state.original.failAfter = true;
            if ("wrong-return".equals(failure)) state.original.wrongStateResult = true;
            yes(!state.nodes.state(true), "void state " + failure + " cannot falsely confirm");
            yes(!state.nodes.state(true), "failed state enable never blindly retried");
            eq("[n=6, l=true]", state.original.calls.toString(), "one native enable only");
            eq(0, state.files.attempts, "failed void state no file fallback");
            if ("readback".equals(failure)) {
                yes(state.nodes.last() == null, "failed void state readback is unknown, not cached state0");
                yes(!state.nodes.stop(), "unreadable state permits one best-effort safety stop, remains unconfirmed");
                yes(!state.nodes.stopConfirmed(), "unknown void stop readback cannot mean off");
                eq(-1, state.nodes.last().state, "unreadable native stop state cleared");
                yes(!state.nodes.stop(), "unknown native disable cannot blindly retry");
                eq("[n=6, l=true, l=false]", state.original.calls.toString(), "single state-only safety disable, not another level write");
            }
        }

        for (boolean after : new boolean[] { false, true }) {
            Setup stopException = new Setup(exact, false); stopException.probe();
            yes(stopException.nodes.level(6), "stop-exception fixture positive level");
            yes(stopException.nodes.state(true), "stop-exception fixture enabled");
            stopException.original.failBefore = !after; stopException.original.failAfter = after;
            yes(!stopException.nodes.stop(), "native disable exception never disguised as successful syscall");
            eq(after, stopException.nodes.stopConfirmed(), "physical state0 confirmation separate from native exception");
            eq(after ? 0 : 1, stopException.nodes.last().state, "native disable exception retains real state readback");
            yes(!stopException.nodes.stop(), "uncertain native disable exception never blindly retried");
            eq("[n=6, l=true, l=false]", stopException.original.calls.toString(), "one native disable despite exception");
            eq(0, stopException.files.attempts, "native disable exception no file fallback");
        }
        Setup stopMismatch = new Setup(exact, false); stopMismatch.probe();
        yes(stopMismatch.nodes.level(6), "stop-mismatch fixture positive level");
        yes(stopMismatch.nodes.state(true), "stop-mismatch fixture enabled");
        stopMismatch.original.applyState = false;
        yes(!stopMismatch.nodes.stop(), "void disable return cannot hide still-running physical state");
        yes(!stopMismatch.nodes.stopConfirmed(), "mismatched native disable not confirmed off");
        yes(!stopMismatch.nodes.stop(), "mismatched native disable not retried");
        eq("[n=6, l=true, l=false]", stopMismatch.original.calls.toString(), "one mismatched native disable");
        eq(0, stopMismatch.files.attempts, "mismatched native disable no second route");

        for (String auxiliary : new String[] { "level_eff", "status", "level" }) {
            Setup lease = new Setup(exact, false); lease.init();
            yes(lease.control.prepare("daily", 20, 300_000), "leased bridge owner"); apply(lease, 6);
            DirectFanControl.Lease ticket = lease.control.lease();
            eq(315_000L, ticket.deadline, "bridge preserves15sec lease");
            lease.files.hardware.failReadNode = auxiliary;
            yes(lease.control.expireLease(ticket, 314_999) == null, "no early lease stop");
            DirectFanControl.SafetyStop stop = lease.control.expireLease(ticket, 315_000);
            yes(stop != null && lease.control.beginSafetyStop(stop), "handler stop ticket starts once");
            yes(lease.control.state(false), "state-only native safety stop survives bad " + auxiliary);
            yes(lease.control.stopConfirmed(), "native stop physical state0 verified");
            yes(lease.control.finishSafetyStop(stop, true, ""), "native stop ticket completes");
            eq("confirmed-off", lease.control.status().safetyStop, "honest native safety-stop status");
            eq("[n=6, l=true, l=false]", lease.original.calls.toString(), "native lease expiry never writes level0");
            yes(lease.control.expireLease(ticket, 999_000) == null, "stale lease cannot reissue native stop");
            yes(!lease.control.beginSafetyStop(stop), "finished stop ticket cannot run again");
            eq(0, lease.files.attempts, "native safety stop does not switch transports");
        }

        Setup boot = new Setup(exact, false);
        boot.files.hardware.level = boot.files.hardware.state = boot.files.hardware.effective = 0;
        boot.init();
        eq(0, boot.control.status().state, "boot physical state0 valid");
        eq(0, boot.control.status().level, "boot physical level0 valid only while stopped");
        eq(0, boot.control.status().effective, "boot effective0 read, not assumed");
        eq(0, boot.original.calls.size(), "boot probe is read-only");
        yes(boot.control.prepare("daily", 20, 400_000), "software policy may take over legal boot0");
        eq(6, boot.control.target(400_000), "boot0 first software ramp output6");
        apply(boot, 6);
        boot.control.prepare("daily", 20, 405_000);
        eq(7, boot.control.target(405_000), "normal next ramp tick7 after boot0");
        apply(boot, 7);
        eq(7, boot.control.status().level, "raw next request7 read back");
        eq(7, boot.control.status().request, "completed native request7 retained separately from demand20");
        boot.files.hardware.effective = 3;
        yes(boot.nodes.inspect() != null, "boot takeover allows physical derate");
        eq(3, boot.control.status().effective, "actual effective3 never replaced with request7");
        eq("[n=6, l=true, n=7]", boot.original.calls.toString(), "no补写 or double enable after boot");
        eq("software-oem-node-bridge", boot.control.status().backend, "boot takeover transport label honest");

        for (int state : new int[] { 0, 1 }) {
            Setup zero = new Setup(exact, false);
            zero.files.hardware.state = state; zero.files.hardware.level = 0; zero.files.hardware.effective = 0;
            eq(state == 0, zero.nodes.probe(20), "level0 allowed iff state0");
            if (state == 0) yes(!zero.nodes.state(true), "boot0 cannot enable before positive level prepared");
            eq(0, zero.original.calls.size(), "invalid/unprepared level0 causes no original write");
            eq(0, zero.files.attempts, "no level0 file write");
        }
        Setup negative = new Setup(exact, false); negative.files.hardware.level = -1;
        yes(!negative.nodes.probe(20), "negative physical level always invalid");
        eq(0, negative.original.calls.size(), "negative initial request never corrected by writing");
        Setup negativeWrite = new Setup(exact, false); negativeWrite.probe();
        yes(!negativeWrite.nodes.level(-1), "negative software write blocked");
        eq(0, negativeWrite.original.calls.size(), "negative request no original call");
        Setup bounded = new Setup(exact, false); bounded.files.hardware.level = 6;
        yes(bounded.nodes.probe(6), "known smaller maximum");
        yes(!bounded.nodes.level(7), "actual probed maximum remains enforced");
        eq(0, bounded.original.calls.size(), "above actual max never forwarded to original");

        final Setup whitelist = new Setup(exact, false);
        denied(new Throwing() { public void run() throws Exception { whitelist.route.write("level", "6"); } }, "write cannot select transport");
        eq(0, whitelist.files.capabilities, "write never performs route decision");
        yes(!whitelist.route.writable("status"), "read-only capability false without selecting");
        eq("unselected", whitelist.route.transport(), "unknown capability does not select transport");
        whitelist.probe();
        for (final String name : new String[] { "rpm", "level_eff", "status", "enable", "../../../tmp/node", "" }) {
            denied(new Throwing() { public void run() throws Exception { whitelist.route.write(name, "1"); } }, "unknown/read-only node rejected");
        }
        for (final String value : new String[] { "0", "-1", "21", "+6", "06", " 6", "6\n", "", "n/a" }) {
            denied(new Throwing() { public void run() throws Exception { whitelist.route.write("level", value); } }, "invalid level rejected before original");
        }
        for (final String value : new String[] { "-1", "2", "true", "", "01" }) {
            denied(new Throwing() { public void run() throws Exception { whitelist.route.write("state", value); } }, "invalid state rejected before original");
        }
        denied(new Throwing() { public void run() throws Exception { whitelist.route.read("rpm"); } }, "unknown read node rejected");
        eq(0, whitelist.files.attempts, "whitelist rejections never touch direct writer");
        eq(0, whitelist.original.calls.size(), "whitelist rejections never touch native writer");
        eq(2, whitelist.files.capabilities, "validation cannot trigger reselection");
        System.out.println("FanNodeBridgeTest: " + checks + " assertions passed (synthetic fan-only bridge, readback, one-shot transport, boot0)");
    }
}
