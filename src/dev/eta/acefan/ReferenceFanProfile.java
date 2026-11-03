package dev.eta.acefan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each OEM role method must still reference, derived from the local reference firmware.
 *
 * Obfuscation renames methods per build (the reference has q/c/s/i/b/d/g; the .152 build ships a
 * different mapping) and any recompile reshuffles registers and pool order. Member *references*
 * survive both: "controller health reads mLastUEvent" is meaning, not spelling. So this profile
 * stores required references only, and the instruction hash is demoted to a hint.
 *
 * No OEM bytecode is bundled.
 */
final class ReferenceFanProfile {
    /** Instruction hashes of the reference build: advisory, never a blocking gate. */
    static final Map<String,String> HASHES;
    /** References each role must still make, matched against canonicalised evidence strings. */
    static final Map<String,String[]> EVIDENCE;

    static {
        Map<String,String> m=new LinkedHashMap<String,String>();
        m.put("candidate","43178cfb3375f77f747a8aa593e88304ca0c82cd2a2f24314e0f1a1e73019c34");
        m.put("downstream","5db98e51e1260cff324bef0c040bb7109d1a26608ef165b5e449cbca0547e9cb");
        m.put("noise","8c174480b6d68001a1f048bf243b7363f03f1be0f9aff4c766f22bd60b697287");
        m.put("recalculate","8cfd1ab2fc0830a74276d77224a8c3db9d79d630ac4c4414f85ec25b556482b3");
        m.put("apply","5a80dcef0448843c0e11603ea495c30f94721c4cb7c81c39c9df862bf75ad1d6");
        m.put("selectorEnabled","795e98ff375687d9f290b8d9c8d722d3a44bb5204ae59d16c11259d2a01f334a");
        m.put("selectorUpdate","0331cbf9624329be08b4b7a6634bb9b2bd300440f496632bee97b28250d1fb4b");
        m.put("controllerSet","c7b91e134928a8628213ea2360540b05256e0106531acd82a2596b9f0fceebc1");
        m.put("controllerHealth","c3f34d50a8d2b2241ce5cec458e343807f8694741e2365354e6763b394cf78cb");
        m.put("maximum","fa1c2550441356602a66188a180d72f42fe5c7a3a65b9f801c395ef772388f78");
        m.put("refresh","df2fae8a0c954ab96d7ea2de439493f665d65fe33b66b7c20d42d05c45488bed");
        HASHES=Collections.unmodifiableMap(m);

        Map<String,String[]> e=new LinkedHashMap<String,String[]>();
        // Obfuscation-independent anchors: kept field names plus the structural collaborators.
        e.put("controllerHealth", new String[]{"mLastUEvent:I"});
        e.put("maximum", new String[]{"mSpeedLevelMax:I"});
        e.put("controllerSet", new String[]{"mCurrentSpeedLevel:I"});
        e.put("candidate", new String[]{"method:@getStrategy"});
        e.put("downstream", new String[]{"method:@candidate", "method:@noise"});
        e.put("apply", new String[]{"method:@controllerSet"});
        e.put("recalculate", new String[]{"method:@selectorUpdate"});
        // These three have no stable member-name anchor; the byte hash stays advisory for them.
        e.put("selectorEnabled", new String[]{});
        e.put("selectorUpdate", new String[]{});
        e.put("noise", new String[]{});
        e.put("refresh", new String[]{"method:@apply", "method:@recalculate"});
        EVIDENCE=Collections.unmodifiableMap(e);
    }

    private ReferenceFanProfile() { }
}
