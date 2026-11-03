package dev.eta.acefan;

/** Single source for build strings shared by the UI and the hook. */
public final class BuildInfo {
    /** Version of the whole APK / UI. */
    public static final String APP_VERSION = "0.3.3-report";
    /** Version of the hook policy that is actually loaded in the target process. */
    public static final String HOOK_VERSION = "0.3.2-report";

    private BuildInfo() { }
}
