package dev.eta.acefan;

/** Synthetic schemas, not extra real firmware samples. */
public final class CompatibilityTest {
    interface Action { void run() throws Exception; }
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static void rejects(Action a)throws Exception {checks++;try{a.run();}catch(ReflectiveOperationException expected){return;}throw new AssertionError("must reject "+checks);}
    static class Ctx { } static class Hdl { }
    enum Strategy {
        A("Common"),B("KeyguardLocked"),C("ChargeWhileKeyguardLocked"),D("SuperCharging"),E("ShutDown"),F("PocketMode"),G("TelephonyInHeld");
        private final String label;Strategy(String s){label=s;}public String b(){return label;}
    }
    static class Selector { Strategy g(){return Strategy.A;} boolean i(){return true;} void j(){} }
    static abstract class Base { final Selector renamed=new Selector(); public void f(String s){} public void b(){} }
    static class V3 extends Base { V3(Ctx c,Hdl h){} private int renamedCandidate(){return 0;} protected void c(){} private int s(int i){return i;} }
    static class Ambiguous extends Base { Ambiguous(Ctx c,Hdl h){} private int q(){return 0;} private int other(){return 0;} protected void c(){} private int s(int i){return i;} }
    static class MissingProtection extends Base { MissingProtection(Ctx c,Hdl h){} private int q(){return 0;} protected void c(){} }
    enum Engine {
        INSTANCE;
        Base mOrchestrator=new V3(new Ctx(),new Hdl());
        public void renamedInit(Ctx c,Hdl h){} private synchronized void renamedRefresh(String s){}
    }
    enum Controller { INSTANCE; private boolean mCurrentSpeedLevelUpdated; private int mLastUEvent; public void i(int i){} public boolean s(){return true;} public boolean r(){return false;} }
    enum Config { INSTANCE; public int renamedMax(){return 20;} }
    enum BadController { INSTANCE; private int mCurrentSpeedLevelUpdated; private int mLastUEvent; public void i(int i){} public boolean s(){return true;} }
    /** Health getter renamed by obfuscation: shape is unique, so it must still resolve. */
    enum RenamedHealth { INSTANCE; private boolean mCurrentSpeedLevelUpdated; private int mLastUEvent; public void i(int i){} public boolean r(){return true;} }
    /** Two same-shaped boolean getters and no s(): picking either would be a guess, so reject. */
    enum AmbiguousHealth { INSTANCE; private boolean mCurrentSpeedLevelUpdated; private int mLastUEvent; public void i(int i){} public boolean r(){return true;} public boolean t(){return false;} }
    static FanServiceCompat resolve(FanServiceCompat.Bootstrap b,Class<?> controller)throws Exception {
        return FanServiceCompat.resolve(b,Engine.INSTANCE,controller,Config.class,Strategy.class,Ctx.class,Hdl.class);
    }
    public static void main(String[] args)throws Exception {
        ok(CompatibilityCatalog.hardwareSupported("PMS110","OP64F1L1"));
        ok(!CompatibilityCatalog.hardwareSupported("PMS110","unknown"));
        ok(!CompatibilityCatalog.hardwareSupported("other","OP64F1L1"));
        ok(!CompatibilityCatalog.bridgeSupported(81));ok(CompatibilityCatalog.bridgeSupported(82));
        ok(CompatibilityCatalog.bridgeSupported(93));ok(CompatibilityCatalog.bridgeSupported(100));ok(CompatibilityCatalog.bridgeSupported(102));
        FanServiceCompat.Bootstrap boot=FanServiceCompat.bootstrap(Engine.class,Ctx.class,Hdl.class);
        FanServiceCompat good=resolve(boot,Controller.class);
        ok(good.candidate.getName().equals("renamedCandidate"));
        ok(good.choice.getName().equals("renamed"));ok(good.evidence.size()==11);
        rejects(()->resolve(boot,BadController.class));
        FanServiceCompat renamed=resolve(boot,RenamedHealth.class);
        ok(renamed.healthy.getName().equals("r"));
        ok(renamed.evidence.get("controllerHealth").getName().equals("r"));
        rejects(()->resolve(boot,AmbiguousHealth.class));
        Engine.INSTANCE.mOrchestrator=new Ambiguous(new Ctx(),new Hdl());rejects(()->resolve(boot,Controller.class));
        Engine.INSTANCE.mOrchestrator=new MissingProtection(new Ctx(),new Hdl());rejects(()->resolve(boot,Controller.class));
        Engine.INSTANCE.mOrchestrator=null;rejects(()->resolve(boot,Controller.class));
        long now=1_800_000_000_000L, elapsed=100000;
        String boot1="11111111-1111-1111-1111-111111111111",boot2="22222222-2222-2222-2222-222222222222";
        ok(ReportFreshness.fresh(now-30000,elapsed-30000,now,elapsed,boot1,boot1,65000));
        ok(!ReportFreshness.fresh(now-30000,elapsed-30000,now,elapsed,boot1,boot2,65000));
        ok(!ReportFreshness.fresh(now-66000,elapsed-66000,now,elapsed,boot1,boot1,65000));
        ok(!ReportFreshness.fresh(now-1000,elapsed+1000,now,elapsed,boot1,boot1,65000));
        ok(!ReportFreshness.fresh(now+1000,elapsed-1000,now,elapsed,boot1,boot1,65000));
        ok(!ReportFreshness.fresh(now-50000,elapsed-1000,now,elapsed,boot1,boot1,65000));
        ok(CompatibilityCatalog.describe().contains("不是本模块的风扇实测范围"));
        System.out.println("Compatibility: "+checks+" hardware/API/schema/freshness/claim checks PASS (synthetic fixtures)");
    }
}
