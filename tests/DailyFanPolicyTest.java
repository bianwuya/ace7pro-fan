package dev.eta.acefan;

/** Tests the same pure code used by the runtime and the read-only preview API. */
public final class DailyFanPolicyTest {
    static int checks;
    static void expect(boolean b, String what) { checks++; if (!b) throw new AssertionError(what); }
    static void near(double actual, double expected) { expect(Double.isFinite(actual) && Math.abs(actual-expected)<1e-9, actual+" != "+expected); }
    static void rejects(Runnable r) { try { r.run(); throw new AssertionError("accepted invalid config"); } catch (IllegalArgumentException ok) { checks++; } }
    static DailyFanConfig cfg(double t, int l, double... ws) { return new DailyFanConfig(true,t,l,ws); }
    static DailyFanPolicy.Frame at(DailyFanPolicy p, DailyFanConfig c, long v, long when, double... readings) {
        return p.sample(p.epoch(),DailyFanPolicy.Power.BATTERY,c,v,readings,when,when,true);
    }
    static double[] batt(double t) { return new double[]{t,Double.NaN,Double.NaN,Double.NaN}; }
    static void demand(DailyFanPolicy.Frame f,int level) { expect(f!=null&&f.demand==level,"expected demand "+level+", got "+(f==null?"null":f.demand)); }

    public static void main(String[] args) {
        // Reproduce the saved 36 C / level 10 profile with one valid value above the threshold
        // that is not assigned any weight. The explicit enable action must preserve every setting.
        DailyFanPolicy replay = new DailyFanPolicy();
        DailyFanConfig savedOff = cfg(36,10,1,0,0,0).withEnabled(false);
        DailyFanConfig optedIn = savedOff.withEnabled(true);
        expect(!savedOff.enabled && optedIn.enabled && optedIn.withEnabled(false).sameSettings(savedOff),"opt-in preserves parameters");
        expect(!optedIn.sameSettings(savedOff)&&!optedIn.sameSettings(cfg(36,10,1,0,1,0)),"readback must confirm switch and all weights");
        double[] sample={31.6,36.357,36.928,38.715};
        DailyFanPolicy.Frame off = at(replay,savedOff,2,1000,sample);
        demand(off,0); expect(off.state.equals("disabled"),"stored parameters alone do not arm control");
        DailyFanPolicy.Frame cool = at(replay,optedIn,3,2000,sample);
        near(cool.fusion.celsius,31.6); demand(cool,0); expect(cool.state.equals("below_threshold"),"zero-weight channels cannot trip threshold");
        DailyFanPolicy.Frame hot = at(replay,cfg(36,10,0,1,1,1),4,3000,sample);
        demand(hot,10); expect(hot.fusion.contributorMask==14,"valid weighted channels reach threshold");
        expect(DailyFanPolicy.candidate(6,20,hot.demand)==10 && DailyFanPolicy.candidate(0,20,hot.demand)==0,
                "weighted trigger raises positive OEM candidate only");
        expect(!DailyFanConfig.DEFAULT.enabled,"new daily configuration must be opt-in");
        near(DailyFanConfig.DEFAULT.startC,33.0);
        expect(DailyFanConfig.DEFAULT.floor==6,"default floor");
        rejects(()->cfg(Double.NaN,6,1,0,0,0)); rejects(()->cfg(50.01,6,1,0,0,0)); rejects(()->cfg(19.99,6,1,0,0,0));
        rejects(()->cfg(33,0,1,0,0,0)); rejects(()->cfg(33,21,1,0,0,0));
        rejects(()->cfg(33,6,0,0,0,0)); rejects(()->cfg(33,6,0,1)); rejects(()->cfg(33,6,1,0,0,Double.NaN));
        rejects(()->cfg(33,6,1,0,0,Double.POSITIVE_INFINITY)); rejects(()->cfg(33,6,1,0,0,-0.1)); rejects(()->cfg(33,6,1,0,0,101));
        double[] weights={50,20,0,30};
        DailyFanConfig blend=cfg(35,8,weights);
        weights[0]=0; blend.weights()[1]=0;
        near(blend.weight(0),50); near(blend.weight(1),20); near(blend.proportions()[3],.3);
        DailyFanPolicy.Fusion f=DailyFanPolicy.fuse(blend,new double[]{32,35,65,40});
        near(f.celsius,35); expect(f.contributorMask==11,"weighted mask excludes fan");
        f=DailyFanPolicy.fuse(blend,new double[]{Double.NaN,35,65,40});
        near(f.celsius,38); expect(f.contributorMask==10,"renormalize, not replace missing with 0");
        near(blend.proportions(f.contributorMask)[3],0.6);
        f=DailyFanPolicy.fuse(blend,new double[]{Double.NaN,-1,40,80});
        expect(Double.isNaN(f.celsius)&&f.contributorMask==0,"no valid weighted value");
        f=DailyFanPolicy.fuse(cfg(35,8,Double.MIN_VALUE,Double.MIN_VALUE,0,0),new double[]{40,30,50,60});
        near(f.celsius,35);
        near(DailyFanPolicy.fuse(blend,new double[]{65,65,65,65}).celsius,65);

        expect(DailyFanPolicy.power(-1,true)==DailyFanPolicy.Power.UNKNOWN,"missing plugged is not battery");
        expect(DailyFanPolicy.power(0,false)==DailyFanPolicy.Power.UNKNOWN,"unknown/not discharging is not battery");
        expect(DailyFanPolicy.power(0,true)==DailyFanPolicy.Power.BATTERY,"confirmed discharging");
        for(int plug:new int[]{1,2,4,8,5}) expect(DailyFanPolicy.power(plug,true)==DailyFanPolicy.Power.EXTERNAL,"all external power");

        DailyFanConfig battery=cfg(33,9,1,0,0,0);
        DailyFanPolicy p=new DailyFanPolicy();
        demand(at(p,DailyFanConfig.DEFAULT,0,0,batt(45)),0);
        demand(at(p,battery,1,1000,batt(32.9)),0);
        demand(at(p,battery,1,2000,batt(33)),9);
        demand(at(p,battery,1,7000,batt(54)),9); // only floor, not an additional fan curve
        demand(at(p,battery,1,12000,batt(32.0)),9); // exact boundary not cooling
        demand(at(p,battery,1,17000,batt(31.9)),9);
        for(long t=22000;t<=42000;t+=5000) demand(at(p,battery,1,t,batt(31.9)),9);
        demand(at(p,battery,1,46999,batt(31.9)),9);
        demand(at(p,battery,1,47000,batt(31.9)),0);
        demand(at(p,battery,1,48000,batt(33.1)),9);
        demand(at(p,battery,1,49000,batt(31)),9);
        demand(at(p,battery,1,50000,batt(32)),9); // interruption resets cooling timer
        demand(at(p,battery,1,51000,batt(31)),9);
        demand(at(p,battery,1,70000,batt(32.5)),0); // no continuity across a stale gap
        demand(at(p,battery,1,75000,batt(33.1)),9);
        demand(at(p,cfg(40,10,1,0,0,0),2,80000,batt(39.5)),0); // revision resets latch
        demand(at(p,blend,3,81000,32,35,65,40),8);
        demand(at(p,blend,3,82000,32,Double.NaN,65,39),0); // mixture changed; do not inherit a latched threshold
        demand(at(p,blend,3,83000,32,35,65,40),8);
        demand(at(p,null,-1,84000,batt(60)),0); // missing provider reply is immediate passthrough
        demand(at(p,battery,4,85000,batt(38)),9);
        demand(p.sample(p.epoch(),DailyFanPolicy.Power.BATTERY,battery,4,batt(38),86000,86000,false),0);
        demand(at(p,battery,4,87000,batt(32.5)),0);
        demand(at(p,battery,4,88000,batt(38)),9);
        expect(p.current(103000)!=null&&p.current(103001)==null,"stale frames denied without a new tick");
        long epoch=p.epoch();
        expect(p.powerEdge(true),"connection withdraws daily demand immediately");
        expect(p.current(88000)==null,"old daily frame no longer usable");
        expect(p.sample(epoch,DailyFanPolicy.Power.BATTERY,battery,4,batt(40),88500,88500,true)==null,"reject in-flight pre-connect read");
        p.powerReport(DailyFanPolicy.Power.BATTERY);
        expect(at(p,battery,4,89000,batt(40))==null,"stale discharge report cannot unblock a power connection");
        p.powerReport(DailyFanPolicy.Power.EXTERNAL);
        expect(at(p,battery,4,90000,batt(40))==null,"on electrical power even if fully charged/paused");
        p.powerEdge(false);
        expect(p.sample(p.epoch(),DailyFanPolicy.Power.EXTERNAL,battery,4,batt(40),91000,91000,true)==null,"unplug waits for explicit battery sample");
        p.powerReport(DailyFanPolicy.Power.UNKNOWN);
        expect(at(p,battery,4,91500,batt(40))==null,"reject contradictory/unknown report");
        p.powerReport(DailyFanPolicy.Power.BATTERY);
        demand(at(p,battery,4,92000,batt(32.5)),0);
        demand(at(p,battery,4,93000,batt(40)),9);
        expect(!p.powerReport(DailyFanPolicy.Power.BATTERY)&&p.current(93000)!=null,"same-power broadcast does not clear latch");
        expect(p.invalidate()&&p.current(93001)==null,"config notification withdraws stale authorization");

        expect(DailyFanPolicy.ownerAllows("3","1","0","1",null,null),"OEM auto allowed");
        expect(!DailyFanPolicy.ownerAllows("3","2","0","1",null,null),"OEM pause preserved");
        expect(!DailyFanPolicy.ownerAllows("0","1","0","1",null,null),"main disabled");
        expect(!DailyFanPolicy.ownerAllows(null,"1","0","1",null,null),"unknown main false");
        expect(!DailyFanPolicy.ownerAllows("1","1","1","1",null,null),"OEM manual respected");
        expect(!DailyFanPolicy.ownerAllows("1","1","0","0",null,null),"OEM auto switch off");
        expect(!DailyFanPolicy.ownerAllows("1","1","0",null,null,null),"unknown auto false");
        expect(!DailyFanPolicy.ownerAllows("1","1","0","1","1",null),"OEM protection");
        expect(!DailyFanPolicy.ownerAllows("1","1","0","1",null,"true"),"OEM abort");
        expect(DailyFanPolicy.allowedScene("Common")&&!DailyFanPolicy.allowedScene("PocketMode")
                &&!DailyFanPolicy.allowedScene("SuperCharging")&&!DailyFanPolicy.allowedScene("KeyguardLocked"),"normal scene only");
        expect(DailyFanPolicy.healthy("NORMAL",null)&&!DailyFanPolicy.healthy("",1)&&!DailyFanPolicy.healthy("NORMAL",3)
                &&!DailyFanPolicy.healthy("FAULT",0),"health must be known normal with no recognized fault");
        for(int oem:new int[]{-1,0,1,6,8,20,23}) for(int max:new int[]{0,1,6,20,25}) for(int ask:new int[]{0,1,9,20,99}) {
            int actual=DailyFanPolicy.candidate(oem,max,ask);
            expect(actual>=oem,"never decrease");
            expect(oem>0&&max>0&&ask>0||actual==oem,"no new start and no actuation without a bound");
            expect(actual==oem||actual<=Math.min(max,20),"new value limited by OEM maximum and family limit");
        }
        expect(DailyFanPolicy.candidate(2,10,9)==9&&DailyFanPolicy.candidate(2,6,9)==6,"raise an already positive candidate");
        System.out.println("DailyFanPolicy: "+checks+" configuration / fusion / power separation / stale / hysteresis / OEM-floor checks PASS");
    }
}
