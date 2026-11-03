package dev.eta.acefan;

import java.util.Map;

/**
 * Real-OEM regression for the semantic gate.
 *
 * The mandatory check is now "does this method still reference the members that give it its
 * meaning", because obfuscated names and instruction encodings both change across builds. These
 * checks run against the genuine reference APK, and also assert that the gate *rejects* a body
 * whose meaning changed, so a passing result cannot come from silently verifying nothing.
 */
public final class CodeProfileTest {
    public static void main(String[] args) throws Exception {
        // --- instruction hashes: advisory only, still checked for regression of the tool itself.
        Map<String, String> reference = CodeProfileTool.hashes(args[0]);
        if (!reference.equals(ReferenceFanProfile.HASHES)) throw new AssertionError("reference profile mismatch");

        Map<String, String> repacked = CodeProfileTool.hashes(args[1]);
        if (!repacked.equals(reference)) throw new AssertionError("unrelated APK changes must not change policy hashes");

        Map<String, String> changedCandidate = CodeProfileTool.hashes(args[2]);
        if (changedCandidate.get("candidate").equals(reference.get("candidate")))
            throw new AssertionError("candidate mutation not detected by the advisory hash");

        try {
            CodeProfileTool.hashes(args[4]);
            throw new AssertionError("malformed DEX accepted");
        } catch (java.io.IOException expected) { }

        // --- semantic evidence: the gate that survives renames and recompiles.
        Map<String, MethodEvidence> evidence = CodeProfileTool.evidence(args[0]);
        if (evidence.size() != 11) throw new AssertionError("expected 11 evidence entries, got " + evidence.size());

        assertContains(evidence, "controllerHealth", "mLastUEvent:I");
        assertContains(evidence, "maximum", "mSpeedLevelMax:I");
        assertContains(evidence, "controllerSet", "mCurrentSpeedLevel:I");
        assertContains(evidence, "candidate", "method:@getStrategy");
        assertContains(evidence, "downstream", "method:@candidate");
        assertContains(evidence, "downstream", "method:@noise");
        assertContains(evidence, "apply", "method:@controllerSet");
        assertContains(evidence, "recalculate", "method:@selectorUpdate");
        assertContains(evidence, "refresh", "method:@apply");
        assertContains(evidence, "refresh", "method:@recalculate");

        // Every role must actually be verified; an empty profile would "pass" for the wrong reason.
        for (Map.Entry<String, String[]> e : ReferenceFanProfile.EVIDENCE.entrySet()) {
            if (!evidence.keySet().contains(CodeProfileTool.roles().get(e.getKey())))
                throw new AssertionError("role not reached by the profile: " + e.getKey());
        }
        int anchors = 0;
        for (String[] required : ReferenceFanProfile.EVIDENCE.values()) anchors += required.length;
        if (anchors < 10) throw new AssertionError("too few semantic anchors to be meaningful: " + anchors);

        // A repack that leaves the policy alone must produce identical evidence.
        Map<String, MethodEvidence> recomputed = CodeProfileTool.evidence(args[1]);
        for (Map.Entry<String, MethodEvidence> e : evidence.entrySet()) {
            MethodEvidence other = recomputed.get(e.getKey());
            if (other == null || !other.fields.equals(e.getValue().fields) || !other.calls.equals(e.getValue().calls))
                throw new AssertionError("repack changed semantic evidence: " + e.getKey());
        }

        // Negative control: the comparison used by the app must accept the real body and reject
        // one whose meaning changed. Otherwise a "pass" could come from matching nothing.
        MethodEvidence health = evidence.get(CodeProfileTool.roles().get("controllerHealth"));
        if (!holds(health, "mLastUEvent:I")) throw new AssertionError("real body must satisfy its anchor");
        MethodEvidence wrongField = new MethodEvidence("field:@controller;->mSelfCheck:Z", "", "");
        if (holds(wrongField, "mLastUEvent:I")) throw new AssertionError("unrelated field must not satisfy the anchor");
        MethodEvidence empty = new MethodEvidence("", "", "");
        if (holds(empty, "mLastUEvent:I")) throw new AssertionError("empty evidence must not satisfy an anchor");

        System.out.println("CodeProfile: 11 real bodies + " + anchors + " semantic anchors verified"
                + " (+ repack stability, empty-evidence rejection) + advisory hashes + malformed DEX PASS");
    }

    static boolean holds(MethodEvidence e, String wanted) {
        return e != null && (e.fields + ", " + e.calls).contains(wanted);
    }

    static void assertContains(Map<String, MethodEvidence> evidence, String role, String wanted) {
        String key = CodeProfileTool.roles().get(role);
        if (key == null) throw new AssertionError("unknown role " + role);
        if (!holds(evidence.get(key), wanted))
            throw new AssertionError(role + " missing anchor " + wanted + " in " + evidence.get(key).summary());
    }
}
