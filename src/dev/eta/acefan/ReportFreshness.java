package dev.eta.acefan;

/** Never treat historical injection as an activation test after reboot or wall-clock jumps. */
public final class ReportFreshness {
    private ReportFreshness() { }
    public static boolean fresh(long wall,long elapsed,long nowWall,long nowElapsed,
            String boot,String currentBoot,long maximumAge) {
        if(wall<=0 || elapsed<0 || nowElapsed<elapsed || nowWall<wall)return false;
        long age=nowElapsed-elapsed, wallAge=nowWall-wall;
        if(age>maximumAge || wallAge>maximumAge || Math.abs(wallAge-age)>10000)return false;
        if(isBootId(boot) && isBootId(currentBoot) && !boot.equals(currentBoot))return false;
        return true;
    }
    private static boolean isBootId(String id){return id!=null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
}
