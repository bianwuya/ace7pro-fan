package dev.eta.acefan;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Handler-confined policy coordinator. Synchronized immutable reports cross to the sampler.
 * It supplies an upstream auto target; every actual request still traverses OEM safety/side effects.
 */
final class DirectFanControl {
    static final long PERIOD_MS = 5_000L, FRESH_MS = 15_000L;
    static final class Endpoints {
        final Method setter, level, state, readState;
        Endpoints(Method setter, Method level, Method state, Method readState) {
            this.setter = setter; this.level = level; this.state = state; this.readState = readState;
        }
    }
    static Endpoints endpoints(Class<?> controller, Class<?> writer) throws ReflectiveOperationException {
        // The audited writer is Controller$b, NOT l9.b (an unrelated DCS Runnable).
        if (writer.getEnclosingClass() != controller) throw new NoSuchMethodException("not controller-local fan writer");
        Method setter = controller.getDeclaredMethod("i", int.class);
        Method level = writer.getDeclaredMethod("n", int.class);
        Method state = writer.getDeclaredMethod("l", boolean.class);
        Method read = writer.getDeclaredMethod("i");
        if (setter.getReturnType() != void.class || !Modifier.isPublic(setter.getModifiers()) || Modifier.isStatic(setter.getModifiers()))
            throw new NoSuchMethodException("fan i(int) signature");
        exactStatic(level, boolean.class); exactStatic(state, void.class); exactStatic(read, String.class);
        setter.setAccessible(true); level.setAccessible(true); state.setAccessible(true); read.setAccessible(true);
        return new Endpoints(setter, level, state, read);
    }
    private static void exactStatic(Method method, Class<?> returns) throws NoSuchMethodException {
        if (method.getReturnType() != returns || !Modifier.isPrivate(method.getModifiers()) || !Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException("fan endpoint signature: " + method);
    }
    interface OriginalCall {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }
    /** Exact fan-only methods already checked above; the caller must bypass our endpoint hooks. */
    static FanNodeIo.NodeBridge nodeBridge(final Endpoints exact, final OriginalCall original) {
        return new FanNodeIo.NodeBridge() {
            public boolean level(int value) throws java.io.IOException {
                Object result = call(exact.level, Integer.valueOf(value));
                if (!(result instanceof Boolean)) throw new java.io.IOException("OEM fan n(int) non-boolean result");
                return Boolean.TRUE.equals(result); // false is a failed write, even if sysfs happens to match
            }
            public void state(boolean on) throws java.io.IOException {
                if (call(exact.state, Boolean.valueOf(on)) != null) throw new java.io.IOException("OEM fan l(boolean) non-void result");
            }
            private Object call(Method method, Object argument) throws java.io.IOException {
                try { return original.invoke(method, new Object[] { argument }); }
                catch (Throwable error) { throw new java.io.IOException("OEM fan original " + method.getName() + " failed", error); }
            }
        };
    }
    /** Identity, not just a timestamp: stale callbacks must never cancel a later owner. */
    static final class Lease {
        final long generation, deadline;
        Lease(long generation, long deadline) { this.generation = generation; this.deadline = deadline; }
    }
    static final class SafetyStop {
        final long generation;
        final String cause;
        private boolean started;
        SafetyStop(long generation, String cause) { this.generation = generation; this.cause = cause; }
    }
    static final class Status {
        final boolean available, active, controlling;
        final long generation, leaseDeadline;
        final String safetyStop, safetyStopError;
        final String backend, transport, mode, reason, error;
        final int target, output, request, state, level, effective;
        final long at, writes;
        Status(boolean available, boolean active, boolean controlling, String mode, String reason, String error,
                int target, int output, int request, FanNodeIo.Reading reading, long at, long writes,
                long generation, long leaseDeadline, String safetyStop, String safetyStopError, String transport) {
            this.available = available; this.active = active; this.controlling = controlling;
            this.generation = generation; this.leaseDeadline = leaseDeadline;
            this.safetyStop = safetyStop; this.safetyStopError = safetyStopError;
            this.transport = transport;
            backend = available && controlling ? (FanNodeIo.OEM_TRANSPORT.equals(transport)
                    ? "software-oem-node-bridge" : "direct-sysfs") : "legacy-oem";
            this.mode = mode; this.reason = reason; this.error = error; this.target = target;
            this.output = output; this.request = request; this.at = at; this.writes = writes;
            state = reading == null ? -1 : reading.state; level = reading == null ? -1 : reading.level;
            effective = reading == null ? -1 : reading.effective;
        }
    }
    private final FanNodeIo nodes;
    private OemFanRamp ramp;
    private boolean installed, attempted, controlling, confirmed;
    private int maximum, target = -1, output = -1, request = -1;
    private long at, lastTick = -1, generation, stoppedGeneration = -1;
    private Lease lease;
    private SafetyStop pendingStop;
    private String safetyStop = "not-requested", safetyStopError = "";
    private String mode = "oem", reason = "waiting for verified fan interfaces", setupError = "";
    DirectFanControl(FanNodeIo nodes) { this.nodes = nodes; }
    synchronized boolean initialize(int maximum) {
        if (attempted) return available();
        attempted = true;
        if (!nodes.probe(maximum)) { reason = "node probe failed; legacy fallback"; return false; }
        this.maximum = maximum;
        ramp = new OemFanRamp(6, maximum);
        installed = true; reason = "verified fan endpoints; transport=" + nodes.transport() + "; readback checked, writes not yet verified; waiting for policy";
        return true;
    }
    synchronized void unavailable(String error) { setupError = error; installed = false; release("direct backend unavailable"); }
    synchronized boolean available() { return installed && setupError.isEmpty() && nodes.ready(); }
    synchronized boolean owns() { return available() && controlling; }
    synchronized boolean fresh(long now) { return owns() && lease != null && now >= at && now < lease.deadline; }

    synchronized boolean prepare(String nextMode, int wanted, long now) { return prepare(nextMode, wanted, now, now); }
    synchronized boolean prepare(String nextMode, int wanted, long now, long sampledAt) {
        // Re-reading an old input on the OEM handler MUST NOT extend its source-sample lease.
        if (sampledAt < 0 || now < sampledAt || now - sampledAt >= FRESH_MS) {
            reason = "software sample stale; lease not renewed";
            return false; // retain any existing watchdog rather than silently cancelling its safety stop
        }
        if (!available()) { release("direct backend unavailable; legacy fallback"); return false; }
        FanNodeIo.Reading reading = nodes.inspect();
        if (reading == null) { release("fan read/ownership failed; legacy fallback"); return false; }
        nodes.claim(); // detect an external disable/change even before our first necessary write
        wanted = Math.max(0, Math.min(maximum, wanted));
        boolean starting = !controlling;
        boolean changed = !nextMode.equals(mode) || target != wanted;
        if (starting) {
            ramp.reset();
            if (reading.state == 1) ramp.seed(reading.level, now / 1000L); // requested, NEVER effective
            confirmed = false;
        }
        generation++;
        if (pendingStop != null) safetyStop = "cancelled-owner-changed";
        pendingStop = null;
        lease = new Lease(generation, sampledAt + FRESH_MS);
        mode = nextMode; target = wanted; controlling = true; at = sampledAt;
        if (starting || changed || lastTick < 0 || now / 1000L - lastTick / 1000L >= PERIOD_MS / 1000L) {
            output = ramp.update(wanted, now / 1000L, reading.state == 0 && wanted > 0 && (starting || ramp.level() == 0));
            lastTick = now;
        }
        reason = "software demand + OEM normal slew; awaiting/retaining OEM downstream request";
        return true;
    }
    synchronized int target(long now) { return fresh(now) ? output : -1; }
    synchronized void release(String why) {
        generation++; lease = null;
        if (pendingStop != null) safetyStop = "cancelled-owner-changed";
        pendingStop = null;
        releaseState(why);
    }
    private void releaseState(String why) {
        controlling = false; confirmed = false; target = -1; output = -1; mode = "oem";
        reason = why; lastTick = -1;
        if (ramp != null) ramp.reset();
        nodes.relinquish();
    }
    synchronized boolean stopPending() { return pendingStop != null; }
    synchronized Lease lease() { return lease; }
    synchronized boolean leaseMatches(Lease expected) {
        return expected != null && lease == expected && generation == expected.generation && controlling;
    }
    synchronized SafetyStop expireLease(Lease expected, long now) {
        if (!leaseMatches(expected) || now < expected.deadline) return null;
        return queueSafetyStop("15s software sample lease expired");
    }
    synchronized SafetyStop queueSafetyStop(String cause) {
        if (pendingStop != null || stoppedGeneration == generation) return null;
        pendingStop = new SafetyStop(generation, cause);
        lease = null;
        releaseState(cause + "; safety stop queued; physical stop not yet confirmed");
        safetyStop = "queued"; safetyStopError = "";
        return pendingStop;
    }
    synchronized boolean beginSafetyStop(SafetyStop expected) {
        if (expected == null || pendingStop != expected || generation != expected.generation || expected.started) return false;
        expected.started = true; safetyStop = "in-progress";
        return true;
    }
    synchronized boolean finishSafetyStop(SafetyStop expected, boolean confirmedOff, String detail) {
        if (expected == null || pendingStop != expected || generation != expected.generation || !expected.started) return false;
        pendingStop = null; stoppedGeneration = generation;
        safetyStop = confirmedOff ? "confirmed-off" : "unconfirmed";
        safetyStopError = detail == null ? "" : detail;
        if (!confirmedOff && setupError.isEmpty()) setupError = "safety stop unconfirmed: " + safetyStopError;
        releaseState(expected.cause + (confirmedOff ? "; fan state=0 confirmed; ownership released"
                : "; ownership released, physical stop UNCONFIRMED")
                + (safetyStopError.isEmpty() ? "" : "; " + safetyStopError));
        return true;
    }
    synchronized boolean stopConfirmed() { return nodes.stopConfirmed(); }
    synchronized String stopError() { return nodes.stopError(); }
    synchronized boolean level(int level) { return nodes.level(level); }
    synchronized boolean state(boolean on) { return on ? nodes.state(true) : nodes.stop(); }
    synchronized String stateForSetter() {
        FanNodeIo.Reading reading = nodes.inspect();
        // Unknown => force an enable edge through the checked writer, never pretend enabled.
        return reading == null ? "0" : String.valueOf(reading.state);
    }
    synchronized boolean mustApply(int wanted) {
        FanNodeIo.Reading reading = nodes.inspect();
        return reading == null || (wanted > 0 ? reading.state != 1 || reading.level != wanted : reading.state != 0);
    }
    synchronized void requested(int wanted, boolean completed) {
        request = wanted;
        FanNodeIo.Reading reading = nodes.last();
        confirmed = completed && available() && reading != null && (wanted > 0
                ? reading.state == 1 && reading.level == wanted : reading.state == 0);
        if (!confirmed) {
            if (error().isEmpty()) setupError = "OEM setter did not complete with matching direct node readback";
            release("direct request not confirmed; legacy fallback"); return;
        }
        if (!controlling) { confirmed = false; return; } // final native disable on handoff retains its release reason
        if (wanted <= 0 && output > 0) { release("OEM downstream stopped/disabled/protected fan"); return; }
        reason = wanted == output ? "direct request/readback confirmed (not RPM)"
                : "OEM downstream limited request; safety result preserved";
    }
    synchronized String error() { return !setupError.isEmpty() ? setupError : nodes.error(); }
    synchronized Status status() {
        return new Status(available(), owns() && confirmed, owns(), mode, reason, error(),
                target, output, request, nodes.last(), at, nodes.writes(), generation,
                lease == null ? -1 : lease.deadline, safetyStop, safetyStopError, nodes.transport());
    }
}
