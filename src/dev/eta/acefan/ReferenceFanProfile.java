package dev.eta.acefan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Derived from the local OEM 1.4.6 APK; no OEM bytecode is bundled. Regenerate only after review. */
final class ReferenceFanProfile {
    static final Map<String,String> HASHES;
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
    }
    private ReferenceFanProfile() { }
}
