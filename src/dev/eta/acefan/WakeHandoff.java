package dev.eta.acefan;

/** Pure, bounded wake handoff for an already-applied screen-off charging session. */
public final class WakeHandoff {
    public static final int OEM=0, SCREEN_OFF=1, LOCKED=2, UNLOCK_GRACE=3;
    public static final long GRACE_MS=2000L;
    private boolean armed;
    private long unlockedAt=-1L;
    private long generation;

    public synchronized int evaluate(long now, Boolean interactive, Boolean locked, boolean allowed){
        if(!allowed || interactive==null){reset();return OEM;}
        if(!interactive.booleanValue()){unlockedAt=-1L;return SCREEN_OFF;}
        if(!armed)return OEM;
        if(locked==null){reset();return OEM;}
        if(locked.booleanValue() && unlockedAt<0L)return LOCKED;
        if(unlockedAt<0L)unlockedAt=now;
        if(now>=unlockedAt && now-unlockedAt<GRACE_MS)return UNLOCK_GRACE;
        reset();return OEM;
    }
    /** Called only after a whitelisted, healthy screen-off decision was really overridden. */
    public synchronized void confirmScreenOffApplied(int phase,int candidate){
        confirmScreenOffApplied(phase,candidate,generation);
    }
    public synchronized void confirmScreenOffApplied(int phase,int candidate,long expectedGeneration){
        if(expectedGeneration==generation && phase==SCREEN_OFF && candidate>0){armed=true;unlockedAt=-1L;}
    }
    public synchronized long generation(){return generation;}
    public synchronized long remaining(long now){
        if(!armed || unlockedAt<0L)return 0L;
        return Math.max(0L,GRACE_MS-Math.max(0L,now-unlockedAt));
    }
    public synchronized boolean armed(){return armed;}
    public synchronized void reset(){armed=false;unlockedAt=-1L;generation++;}
    public static String label(int phase){
        switch(phase){case SCREEN_OFF:return "screen_off";case LOCKED:return "locked_handoff";case UNLOCK_GRACE:return "unlock_grace";default:return "oem";}
    }
}
