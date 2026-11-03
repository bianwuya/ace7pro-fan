package dev.eta.acefan;

/** Direct interface invocation fixtures. These are not extra real firmware samples. */
public final class CompatibilityTest {
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    interface Action{void run()throws Exception;}
    static void failsAt(String stage,Action a)throws Exception{
        checks++;try{a.run();}catch(ReflectiveOperationException e){if(!FanServiceCompat.stageOf(e).equals(stage))throw new AssertionError(e);return;}throw new AssertionError("expected stage "+stage);
    }
    static class Ctx{} static class Hdl{}
    // No full reference enum set, no selector enabled/update methods, no noise method.
    enum Strategy{A;public String changedName(){return "Common";}}
    static class Selector{Strategy renamed(){return Strategy.A;}}
    static class Base{Selector choice=new Selector();}
    static class V3 extends Base{private int candidateRenamed(){return 6;}}
    static class Multiple extends Base{private int candidateRenamed(){return 6;}private int unrelated(){return 2;}}
    // Engine and controller need not be enum singletons.
    static class Engine{Object mOrchestrator=new V3();public void initialize(Ctx c,Hdl h){}private void recompute(String s){}}
    enum Controller{ONE;private boolean mCurrentSpeedLevelUpdated=true;private int mLastUEvent=2,mCurrentSpeedLevel=12;public void request(int n){mCurrentSpeedLevel=n;}public boolean a(){return true;}public boolean b(){return false;}}
    enum RenamedHealth{ONE;private int mLastUEvent=2;public void request(int n){}public boolean r(){return true;}public boolean t(){return false;}}
    enum BadOptional{ONE;private int mCurrentSpeedLevelUpdated;public boolean r(){return true;}}
    enum Config{ONE;private int mSpeedLevelMax=30;public int extra(){return 100;}public int changedMax(){return mSpeedLevelMax;}}
    enum GetterOnly{ONE;public int renamed(){return 20;}}
    enum NotReady{ONE;private int mSpeedLevelMax=0;}
    static FanServiceCompat resolve(FanServiceCompat.Bootstrap b,Engine e,Class<?> c,Class<?> config)throws Exception{return FanServiceCompat.resolve(b,e,c,config,Strategy.class,Ctx.class,Hdl.class);}
    public static void main(String[] args)throws Exception{
        ok(CompatibilityCatalog.hardwareSupported("PMS110","changed-device"));
        ok(CompatibilityCatalog.hardwareSupported("unlisted-model","other","一加 Ace 7 Pro"));
        ok(CompatibilityCatalog.hardwareSupported("OnePlus ACE7","other",""));
        ok(CompatibilityCatalog.hardwareSupported("other","other","OnePlus Ace 7T"));
        ok(!CompatibilityCatalog.hardwareSupported("other","OP64F1L1",""));
        ok(!CompatibilityCatalog.hardwareSupported("other","other","Ace 70 Pro"));
        ok(CompatibilityCatalog.hardwareSupported("other","other","一加 Ace7V"));
        ok(!CompatibilityCatalog.hardwareSupported("other","other","OnePlus Ace 6"));
        ok(!CompatibilityCatalog.bridgeSupported(81));ok(CompatibilityCatalog.bridgeSupported(82));ok(CompatibilityCatalog.bridgeSupported(100));
        Engine e=new Engine();FanServiceCompat.Bootstrap boot=FanServiceCompat.bootstrap(Engine.class,Ctx.class,Hdl.class);
        FanServiceCompat f=resolve(boot,e,Controller.class,Config.class);
        ok(f.candidates.size()==1);ok(f.candidates.get(0).getName().equals("candidateRenamed"));
        ok(((Integer)f.candidates.get(0).invoke(f.orchestratorObject))==6);
        ok(f.readStrategy().equals("Common"));ok(f.readMaximum()==30); // No old <=20 gate.
        ok(f.event()==2);ok(Boolean.TRUE.equals(f.confirmed(f.controller)));ok(f.level(f.controller)==12);
        f.setters.get(0).invoke(f.controller,18);ok(f.level(f.controller)==18);
        // Ambiguous/renamed health getters never prevent interface use; we don't invoke them.
        FanServiceCompat renamed=resolve(boot,e,RenamedHealth.class,Config.class);ok(renamed.readMaximum()==30);ok(renamed.event()==2);
        FanServiceCompat partial=resolve(boot,e,BadOptional.class,Config.class);
        ok(partial.updated==null);ok(partial.event()==null);ok(partial.setters.isEmpty());ok(partial.readMaximum()==30);ok(partial.readStrategy().equals("Common"));
        FanServiceCompat absent=resolve(boot,e,null,GetterOnly.class);ok(absent.controller==null);ok(absent.readMaximum()==20);
        FanServiceCompat noConfig=resolve(boot,e,Controller.class,null);ok(noConfig.candidates.size()==1);
        checks++;try{noConfig.readMaximum();throw new AssertionError("must report missing config");}catch(NoSuchFieldException expected){}
        FanServiceCompat waiting=resolve(boot,e,Controller.class,NotReady.class);
        checks++;try{waiting.readMaximum();throw new AssertionError("zero isn't ready");}catch(IllegalStateException expected){}
        NotReady.ONE.mSpeedLevelMax=16;ok(waiting.readMaximum()==16); // Natural retry after configuration becomes ready.
        e.mOrchestrator=new Multiple();ok(resolve(boot,e,Controller.class,Config.class).candidates.size()==2);
        e.mOrchestrator=null;failsAt("读取编排器实例",()->resolve(boot,e,Controller.class,Config.class));
        e.mOrchestrator=new V3();failsAt("当前场景读取",()->FanServiceCompat.resolve(boot,e,Controller.class,Config.class,null,Ctx.class,Hdl.class));
        ok(FanServiceCompat.allowedScene("Common"));ok(!FanServiceCompat.allowedScene("PocketMode"));ok(!FanServiceCompat.allowedScene("new-unknown"));
        long now=1_800_000_000_000L,elapsed=100000;
        String b1="11111111-1111-1111-1111-111111111111",b2="22222222-2222-2222-2222-222222222222";
        ok(ReportFreshness.fresh(now-30000,elapsed-30000,now,elapsed,b1,b1,65000));
        ok(!ReportFreshness.fresh(now-30000,elapsed-30000,now,elapsed,b1,b2,65000));
        ok(!ReportFreshness.fresh(now-66000,elapsed-66000,now,elapsed,b1,b1,65000));
        System.out.println("Compatibility: "+checks+" direct invocation / partial capabilities / live readiness / family checks PASS (synthetic)");
    }
}
