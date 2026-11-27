/* Safe development build. This is an intentionally DISABLED manifest, not a
 * replacement payload or release identity. --apply always refuses it.
 * generate_manifest.py creates a separate pinned header ONLY from real inputs.
 * No runtime configuration, environment hash override, or shell evaluation. */
static const struct fan_manifest fan_release_manifest = {
    .present = 0,
    .filename = FAN_REPLACEMENT,
    .sha256 = "",
    .size = 0,
    .kernel_release = "",
    .kmi = "",
    .vermagic = "",
    .versions_sha256 = "",
    .versions_count = 0
};
