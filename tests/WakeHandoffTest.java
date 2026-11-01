package dev.eta.acefan;
public final class WakeHandoffTest {
    static void eq(long actual,long expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
    static void yes(boolean value){if(!value)throw new AssertionError("expected true");}
    public static void main(String[] args){
        WakeHandoff w=new WakeHandoff();
        // Merely opening a lit lockscreen must not start a new enhancement session.
        eq(w.evaluate(0,true,true,true),WakeHandoff.OEM);
        eq(w.evaluate(1,false,true,true),WakeHandoff.SCREEN_OFF);
        eq(w.evaluate(2,true,true,true),WakeHandoff.OEM); // no applied decision yet
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,20);yes(w.armed());
        eq(w.evaluate(3,true,true,true),WakeHandoff.LOCKED);
        eq(w.evaluate(600003,true,true,true),WakeHandoff.LOCKED); // may stay on lockscreen
        eq(w.evaluate(600004,true,false,true),WakeHandoff.UNLOCK_GRACE);
        eq(w.remaining(600004),2000);
        eq(w.evaluate(602003,true,false,true),WakeHandoff.UNLOCK_GRACE);
        eq(w.evaluate(602004,true,false,true),WakeHandoff.OEM);
        eq(w.evaluate(602005,true,true,true),WakeHandoff.OEM); // cannot rearm by relocking alone
        // Every hard-gate failure cancels the continuation immediately, even during grace.
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,18);
        eq(w.evaluate(700000,true,true,false),WakeHandoff.OEM);eq(w.armed()?1:0,0);
        eq(w.evaluate(700001,true,true,true),WakeHandoff.OEM);
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,18);
        eq(w.evaluate(700002,true,null,true),WakeHandoff.OEM);
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,18);
        eq(w.evaluate(700003,null,true,true),WakeHandoff.OEM);
        // Zero output cannot arm continuity; a new valid screen-off application can.
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,0);
        eq(w.evaluate(800000,true,true,true),WakeHandoff.OEM);
        eq(w.evaluate(800001,false,null,true),WakeHandoff.SCREEN_OFF);
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,14);
        eq(w.evaluate(800002,true,false,true),WakeHandoff.UNLOCK_GRACE);
        eq(w.evaluate(800003,false,true,true),WakeHandoff.SCREEN_OFF);
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,14);
        eq(w.evaluate(800004,true,true,true),WakeHandoff.LOCKED);
        // Repeated refreshes must not extend the unlocked grace deadline.
        eq(w.evaluate(900000,true,false,true),WakeHandoff.UNLOCK_GRACE);
        eq(w.evaluate(901000,true,false,true),WakeHandoff.UNLOCK_GRACE);eq(w.remaining(901000),1000);
        eq(w.evaluate(902001,true,false,true),WakeHandoff.OEM);
        // A hard reset between candidate and controller completion invalidates that stale completion.
        w.reset();
        eq(w.evaluate(910000,false,true,true),WakeHandoff.SCREEN_OFF);
        long previousGeneration=w.generation();
        w.reset();
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,20,previousGeneration);
        eq(w.armed()?1:0,0);
        eq(w.evaluate(910001,true,true,true),WakeHandoff.OEM);
        // A completely new validated screen-off request after recovery may start a new session.
        eq(w.evaluate(920000,false,true,true),WakeHandoff.SCREEN_OFF);
        w.confirmScreenOffApplied(WakeHandoff.SCREEN_OFF,18,w.generation());
        eq(w.evaluate(920001,true,true,true),WakeHandoff.LOCKED);
        System.out.println("WakeHandoff: continuity, bounded unlock, hard gates, unknown state, stale completion rejection and validated recovery PASS");
    }
}
