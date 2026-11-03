package dev.eta.acefan;

/**
 * Semantic fingerprint of one OEM method: the members it reads/writes and calls, plus the
 * string constants it uses. Obfuscation renames members between ROM builds and any recompile
 * shifts registers and pool indices, but "this method reads mLastUEvent" survives both.
 *
 * Instruction-level hashing is deliberately NOT the primary gate here; see
 * {@link DexFingerprint} for the auxiliary bytecode signature.
 */
public final class MethodEvidence {
    /** Sorted, de-duplicated member references in canonical form. */
    public final String fields, calls, strings;

    MethodEvidence(String fields, String calls, String strings) {
        this.fields = fields;
        this.calls = calls;
        this.strings = strings;
    }

    /** Human-readable single line for reports. */
    public String summary() {
        return "fields=[" + fields + "] calls=[" + calls + "]";
    }
}
