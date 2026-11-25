package dev.eta.acefan;

/** Tests the same pure code used by the runtime and the read-only preview API. */
public final class DailyFanPolicyTest {
    static int checks;
    static void expect(boolean b, String what) { checks++; if (!b) throw new AssertionError(what); }
    static void near(double actual, double expected) { expect(Double.isFinite(actual) && Math.abs(actual-expected)<1e-9, actual+" != "+expected); }
    static void rejects(Runnable r) { try { r.run(); throw new AssertionError("accepted invalid config"); } catch (IllegalArgumentException ok) { checks++; } }
    static DailyFanConfig cfg(double t, int l, double... ws) { return new DailyFanConfig(true,t,l,ws); }
    static DailyFanPolicy.Frame at(DailyFanPolicy p, DailyFanConfig c, long v, long when, double... readings) {
        return p.sample(p.epoch(),DailyFanPolicy.Power.BATTERY,c,v,readings,when,when,true,0);
    }
    static double[] batt(double t) { return new double[]{t,Double.NaN,Double.NaN,Double.NaN}; }
    static void demand(DailyFanPolicy.Frame f,int level) { expect(f!=null&&f.demand==level,"expected demand "+level+", got "+(f==null?"null":f.demand)); }

    static DailyFanPolicy.Frame atTemperature(DailyFanPolicy policy, DailyFanConfig config, long when, double temperature) {
        return at(policy,config,4,when,temperature,temperature,temperature,temperature);
    }

    static void stopsOnlyAfterSustainedCooling() {
        DailyFanPolicy policy = new DailyFanPolicy();
        DailyFanConfig config = cfg(36,10,1,1,1,1);
        demand(atTemperature(policy,config,1000,36.0),0);
        demand(atTemperature(policy,config,2000,36.1),10);
        demand(atTemperature(policy,config,3000,35.9),10);
        demand(atTemperature(policy,config,4000,35.0),10);
        demand(atTemperature(policy,config,9000,34.9),10);
        demand(atTemperature(policy,config,14000,35.1),10);
        DailyFanPolicy.Frame cooling = atTemperature(policy,config,19000,35.0);
        demand(cooling,10);
        expect("cooling".equals(cooling.state)&&cooling.hasTarget()&&DailyFanPolicy.candidate(0,20,cooling.demand)==10,
                "confirmed auto target must keep spinning during cooling");
        for(long time=24000;time<49000;time+=5000) demand(atTemperature(policy,config,time,34.9),10);
        demand(atTemperature(policy,config,48999,35.0),10);
        DailyFanPolicy.Frame stopped = atTemperature(policy,config,49000,35.0);
        demand(stopped,0);
        expect(stopped.hasTarget()&&DailyFanPolicy.candidate(8,20,stopped.demand)==0,"30 s of cooling actively stops the OEM target");
        demand(atTemperature(policy,config,50000,35.9),0);
        demand(atTemperature(policy,config,51000,36.0),0);
        demand(atTemperature(policy,config,52000,36.1),10);
    }

    static void coolingDoesNotSurviveInterruptedSamples() {
        DailyFanConfig config = cfg(36,10,1,0,1,0);
        for(int interruption=0;interruption<6;interruption++){
            DailyFanPolicy policy = new DailyFanPolicy();
            demand(atTemperature(policy,config,1000,37),10);
            demand(atTemperature(policy,config,6000,34),10);
            demand(atTemperature(policy,config,16000,34),10);
            long next = 22000;
            switch(interruption){
                case 0: next = 32000; break;
                case 1: expect(policy.powerEdge(true),"power edge releases cooling target"); policy.powerEdge(false); break;
                case 2: demand(at(policy,null,-1,20000,batt(34)),0); break;
                case 3: demand(at(policy,config,4,20000,34,34,Double.NaN,34),0); break;
                case 4:
                    DailyFanPolicy.Frame thermal = policy.sample(policy.epoch(),DailyFanPolicy.Power.BATTERY,
                            config,4,new double[]{34,34,34,34},20000,20000,true,1);
                    expect(!thermal.thermalClear,"thermal guard withdraws cooling continuity");
                    break;
                default: demand(at(policy,config,5,20000,34,34,34,34),0); break;
            }
            DailyFanPolicy.Frame after = atTemperature(policy,config,next,35.8);
            demand(after,0);
            expect("below_threshold".equals(after.state),"a new session cannot inherit an earlier cooling target");
        }
    }

    static void replayDeviceWeights() {
        DailyFanPolicy policy = new DailyFanPolicy();
        double[] readings = {32.7,37.273,36.442,39.093};
        DailyFanPolicy.Frame batteryOnly = at(policy,cfg(36,10,1,0,0,0),3,1000,readings);
        near(batteryOnly.fusion.celsius,32.7);
        demand(batteryOnly,0);
        expect(batteryOnly.fusion.contributorMask==1&&batteryOnly.state.equals("below_threshold"),"single-channel profile misses the threshold");

        DailyFanConfig fourChannels = cfg(36,10,1,1,1,1);
        DailyFanPolicy.Frame fused = at(policy,fourChannels,4,2000,readings);
        near(fused.fusion.celsius,36.377);
        demand(fused,10);
        expect(fused.fusion.contributorMask==15&&fused.state.equals("ready"),"all four readings now count toward the threshold");
        expect(DailyFanPolicy.candidate(0,20,fused.demand)==10,"above threshold starts a stopped OEM auto target");
        expect(DailyFanPolicy.candidate(8,20,batteryOnly.demand)==0,"below threshold stops a positive OEM auto target");
        expect(DailyFanPolicy.candidate(6,20,fused.demand)==10,"an eligible lower positive candidate can be raised");
        expect(DailyFanPolicy.candidate(10,20,fused.demand)==10&&DailyFanPolicy.candidate(12,20,fused.demand)==12,"the floor does not change an adequate OEM level");
        DailyFanPolicy.Frame cooled = at(policy,fourChannels,4,2500,32.7,36.4,35.1,39.2);
        demand(cooled,10);
        expect(cooled.hasTarget()&&DailyFanPolicy.candidate(0,20,cooled.demand)==10,"same profile stays on during a small dip");
        expect(policy.sample(policy.epoch(),DailyFanPolicy.Power.EXTERNAL,fourChannels,4,readings,3000,3000,true,0)==null,
                "the same temperature cannot trigger the daily route on USB power");
    }

    public static void main(String[] args) {
        stopsOnlyAfterSustainedCooling();
        coolingDoesNotSurviveInterruptedSamples();
        replayDeviceWeights();
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
        expect(DailyFanPolicy.candidate(6,20,hot.demand)==10 && DailyFanPolicy.candidate(0,20,hot.demand)==10,
                "weighted trigger also starts from zero");
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
        demand(at(p,battery,1,2000,batt(33)),0);
        demand(at(p,battery,1,7000,batt(33.1)),9);
        demand(at(p,battery,1,12000,batt(32.99)),0);
        demand(at(p,battery,1,17000,batt(33.01)),9);
        demand(at(p,battery,1,22000,batt(33)),0);
        demand(at(p,battery,1,47000,batt(31.9)),0);
        demand(at(p,battery,1,48000,batt(33.1)),9);
        demand(at(p,battery,1,49000,batt(31)),0);
        demand(at(p,battery,1,50000,batt(32)),0);
        demand(at(p,battery,1,51000,batt(31)),0);
        demand(at(p,battery,1,70000,batt(32.5)),0);
        demand(at(p,battery,1,75000,batt(33.1)),9);
        demand(at(p,cfg(40,10,1,0,0,0),2,80000,batt(39.5)),0); // revision resets latch
        demand(at(p,blend,3,81000,32,35,65,41),8);
        demand(at(p,blend,3,82000,32,Double.NaN,65,39),0);
        demand(at(p,blend,3,83000,32,35,65,41),8);
        demand(at(p,null,-1,84000,batt(60)),0); // missing provider reply is immediate passthrough
        demand(at(p,battery,4,85000,batt(38)),9);
        DailyFanPolicy.Frame blocked=p.sample(p.epoch(),DailyFanPolicy.Power.BATTERY,battery,4,batt(38),86000,86000,false,0);
        demand(blocked,0); expect(!blocked.hasTarget(),"OEM denial is not a stop request");
        demand(at(p,battery,4,87000,batt(32.5)),0);
        demand(at(p,battery,4,88000,batt(38)),9);
        expect(p.current(103000)!=null&&p.current(103001)==null,"stale frames denied without a new tick");
        long epoch=p.epoch();
        expect(p.powerEdge(true),"connection withdraws daily demand immediately");
        expect(p.current(88000)==null,"old daily frame no longer usable");
        expect(p.sample(epoch,DailyFanPolicy.Power.BATTERY,battery,4,batt(40),88500,88500,true,0)==null,"reject in-flight pre-connect read");
        p.powerReport(DailyFanPolicy.Power.BATTERY);
        expect(at(p,battery,4,89000,batt(40))==null,"stale discharge report cannot unblock a power connection");
        p.powerReport(DailyFanPolicy.Power.EXTERNAL);
        expect(at(p,battery,4,90000,batt(40))==null,"on electrical power even if fully charged/paused");
        p.powerEdge(false);
        expect(p.sample(p.epoch(),DailyFanPolicy.Power.EXTERNAL,battery,4,batt(40),91000,91000,true,0)==null,"unplug waits for explicit battery sample");
        p.powerReport(DailyFanPolicy.Power.UNKNOWN);
        expect(at(p,battery,4,91500,batt(40))==null,"reject contradictory/unknown report");
        p.powerReport(DailyFanPolicy.Power.BATTERY);
        demand(at(p,battery,4,92000,batt(32.5)),0);
        demand(at(p,battery,4,93000,batt(40)),9);
        expect(!p.powerReport(DailyFanPolicy.Power.BATTERY)&&p.current(93000)!=null,"same-power broadcast does not invalidate the decision");
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
        expect(DailyFanPolicy.matchesAutoTarget(0,0,1,true)&&DailyFanPolicy.matchesAutoTarget(8,8,1,true),
                "both directions require a matching OEM auto target during the refresh");
        expect(!DailyFanPolicy.matchesAutoTarget(0,8,1,true)&&!DailyFanPolicy.matchesAutoTarget(0,null,0,true)
                &&!DailyFanPolicy.matchesAutoTarget(0,0,2,true)&&!DailyFanPolicy.matchesAutoTarget(0,0,1,false)
                &&!DailyFanPolicy.matchesAutoTarget(-1,-1,1,true),"interrupts, ambiguity and failures are not normal auto targets");
        double[] normal={32,37,36,39};
        expect(DailyFanPolicy.thermalClear(0,normal)&&!DailyFanPolicy.thermalClear(1,normal)
                &&!DailyFanPolicy.thermalClear(-1,normal),"system heat or unknown thermal status hands control back to OEM");
        expect(!DailyFanPolicy.thermalClear(0,new double[]{45,32,33,34})
                &&!DailyFanPolicy.thermalClear(0,new double[]{32,55,33,34})
                &&!DailyFanPolicy.thermalClear(0,new double[]{32,33,34,Double.NaN}),
                "individual high or unavailable readings veto an override even with zero fusion weight");
        DailyFanPolicy stop=new DailyFanPolicy();
        DailyFanPolicy.Frame stopped=at(stop,battery,1,1000,normal);
        expect(stopped.hasTarget()&&stopped.demand==0,"cool temperature is an active stop, not absent demand");
        expect(stopped.sameDecision(at(stop,battery,1,2000,normal)),"a newer same-decision sample retains control evidence");
        DailyFanPolicy.Frame thermallyBlocked=stop.sample(stop.epoch(),DailyFanPolicy.Power.BATTERY,battery,1,normal,3000,3000,true,1);
        expect(!stopped.sameDecision(thermallyBlocked)&&!thermallyBlocked.thermalClear,"thermal changes invalidate prior stop evidence");
        expect(stop.powerEdge(true),"plugging in releases a zero target immediately too");
        for(int oem:new int[]{-1,0,1,6,8,20,23}) for(int max:new int[]{0,1,6,20,25}) for(int ask:new int[]{0,1,9,20,99}) {
            int actual=DailyFanPolicy.candidate(oem,max,ask);
            boolean valid=oem>=0&&max>0&&oem<=max;
            int expected=!valid?oem:ask==0?0:Math.max(oem,Math.min(Math.min(max,20),ask));
            expect(actual==expected,"valid targets obey the threshold and limits; invalid OEM values pass through");
        }
        expect(DailyFanPolicy.candidate(2,10,9)==9&&DailyFanPolicy.candidate(2,6,9)==6,"raise an already positive candidate");
        System.out.println("DailyFanPolicy: "+checks+" configuration / fusion / power separation / stale / threshold checks PASS");
    }
}
