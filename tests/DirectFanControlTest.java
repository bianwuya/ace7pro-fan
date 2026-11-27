package dev.eta.acefan;

public final class DirectFanControlTest {
    static class Controller {
        public void i(int value){}
        static class Writer {
            private static boolean n(int value){return true;}
            private static void l(boolean value){}
            private static String i(){return "0";}
        }
        static class WrongWriter {
            private static int n(int value){return 1;}
            private static void l(boolean value){}
            private static String i(){return "0";}
        }
    }
    static class UnrelatedHal {
        private static boolean n(int value){return true;}
        private static void l(boolean value){}
        private static String i(){return "0";}
    }
    private static int checks;
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void eq(Object wanted,Object actual,String label){checks++;if(!wanted.equals(actual))throw new AssertionError(label+": "+actual+" != "+wanted);}
    private static DirectFanControl ready(FanNodeIoTest.Memory m){
        DirectFanControl control=new DirectFanControl(new FanNodeIo(m));yes(control.initialize(20),"initialize");return control;
    }
    private static void apply(DirectFanControl c,int request){
        if(request>0)yes(c.level(request),"level write");
        yes(c.state(request>0),"state edge");c.requested(request,true);
    }
    /** Fake original-handler queue; the worker never participates in advancing it. */
    static final class LeaseQueue {
        final DirectFanControl control;
        final java.util.List<DirectFanControl.Lease> timers=new java.util.ArrayList<DirectFanControl.Lease>();
        final java.util.List<DirectFanControl.SafetyStop> stops=new java.util.ArrayList<DirectFanControl.SafetyStop>();
        int nativeStops;
        LeaseQueue(DirectFanControl control){this.control=control;}
        void arm(){timers.add(control.lease());}
        void advance(long now){
            java.util.Iterator<DirectFanControl.Lease> iterator=timers.iterator();
            while(iterator.hasNext()){
                DirectFanControl.Lease lease=iterator.next();
                if(lease==null||now<lease.deadline)continue;
                iterator.remove();
                DirectFanControl.SafetyStop ticket=control.expireLease(lease,now);
                if(ticket!=null)stops.add(ticket);
            }
        }
        void drain(){
            for(DirectFanControl.SafetyStop ticket:stops){
                if(!control.beginSafetyStop(ticket))continue;
                nativeStops++;
                boolean syscall=control.state(false); // models the checked state endpoint inside native i(0)
                boolean off=control.stopConfirmed();
                control.finishSafetyStop(ticket,off,syscall?"":control.stopError());
            }
            stops.clear();
        }
    }
    public static void main(String[] args)throws Exception{
        DirectFanControl.Endpoints exact=DirectFanControl.endpoints(Controller.class,Controller.Writer.class);
        eq(Controller.Writer.class,exact.level.getDeclaringClass(),"local writer only");eq("n",exact.level.getName(),"exact n");
        eq("l",exact.state.getName(),"exact l");eq("i",exact.setter.getName(),"original i retained");
        try{DirectFanControl.endpoints(Controller.class,UnrelatedHal.class);throw new AssertionError("global HAL accepted");}
        catch(NoSuchMethodException expected){checks++;}
        try{DirectFanControl.endpoints(Controller.class,Controller.WrongWriter.class);throw new AssertionError("wrong signature accepted");}
        catch(NoSuchMethodException expected){checks++;}
        FanNodeIoTest.Memory m=new FanNodeIoTest.Memory();DirectFanControl c=ready(m);
        yes(!c.status().active,"probe not control success");yes(!c.owns(),"no policy yet");eq(0,m.writes.size(),"init no write");
        yes(c.prepare("daily",10,100_000),"daily ready");eq(6,c.target(100_000),"first start6");
        yes(c.owns(),"route through downstream");yes(!c.status().active,"getter target not physical confirmation");
        apply(c,6);yes(c.status().active,"request/readback active");eq("direct-sysfs",c.status().backend,"direct route");
        eq("[level=6, state=1]",m.writes.toString(),"level/state order");
        c.prepare("daily",10,101_000);eq(6,c.target(101_000),"fast resample not extra tick");
        c.prepare("daily",10,105_000);eq(7,c.target(105_000),"5secup");apply(c,7);
        m.effective=3;c.prepare("daily",10,110_000);eq(8,c.target(110_000),"derate not software feedback");apply(c,8);
        c.prepare("daily",0,115_000);eq(8,c.target(115_000),"zero target down pending");
        c.prepare("daily",0,120_000);eq(7,c.target(120_000),"zero down10sec");apply(c,7);
        c.prepare("daily",0,125_000);eq(7,c.target(125_000),"down pending2");
        c.prepare("daily",0,130_000);eq(6,c.target(130_000),"zero down minimum");apply(c,6);
        c.prepare("daily",0,135_000);eq(0,c.target(135_000),"zero target writable to getter");apply(c,0);
        eq(0,c.status().output,"zero software output");eq(0,m.state,"stopped");yes(c.status().active,"zero target can be owned/confirmed");
        yes(!m.writes.contains("level=0"),"never write level0");
        c.prepare("daily",20,140_000);eq(6,c.target(140_000),"thermal restart while still owning zero starts6, never1");
        apply(c,6);
        eq(-1,c.target(156_000),"stale target unavailable");
        c.release("owner disabled");yes(!c.owns(),"disable release immediate");eq(-1,c.target(151_000),"no slow down on disable");
        int writes=m.writes.size();c.release("manual/game/pocket/ANC handled downstream");eq(writes,m.writes.size(),"release never directly substitutes setter");
        c.prepare("charge",20,160_000);eq(6,c.target(160_000),"charge starts independently, no WakeHandoff");apply(c,6);
        // Real downstream ANC/joystick/enable stop goes through i(0), with no level or slow ramp.
        c.prepare("charge",20,165_000);yes(c.state(false),"OEM protected stop");c.requested(0,true);
        yes(!c.owns(),"protected downstream zero released");eq(0,m.state,"protected physical state");
        c.prepare("charge",20,170_000);eq(6,c.target(170_000),"after protected stop first restart6");
        apply(c,6); // downstream may also cap the automatic target; it must be kept, not repaired
        c.prepare("charge",20,175_000);eq(7,c.target(175_000),"cap plan7");apply(c,6);
        yes(c.status().active,"lower OEM cap is confirmed");eq(6,c.status().request,"OEM cap retained");eq(7,c.status().output,"plan not mistaken for request");
        writes=m.writes.size();m.effective=2;apply(c,6);eq(writes,m.writes.size(),"cap/derate not repaired");
        FanNodeIoTest.Memory existing=new FanNodeIoTest.Memory();existing.state=1;existing.level=18;existing.effective=12;
        DirectFanControl running=ready(existing);running.prepare("daily",20,200_000);eq(18,running.target(200_000),"seed requested18 not effective12");
        running.prepare("daily",20,205_000);eq(19,running.target(205_000),"running next slew");
        FanNodeIoTest.Memory externallyStopped=new FanNodeIoTest.Memory();externallyStopped.state=1;externallyStopped.level=18;externallyStopped.effective=12;
        DirectFanControl changedOwner=ready(externallyStopped);changedOwner.prepare("daily",20,200_000);
        externallyStopped.state=0;externallyStopped.effective=0;
        yes(!changedOwner.level(18),"external disable before first write not restarted");
        changedOwner.requested(18,false);yes(!changedOwner.available(),"external initial ownership conflict fails closed");
        eq(0,externallyStopped.writes.size(),"no positive write to fight external disable");
        FanNodeIoTest.Memory jitter=new FanNodeIoTest.Memory();DirectFanControl quantized=ready(jitter);
        quantized.prepare("daily",20,100_010);apply(quantized,6);
        quantized.prepare("daily",20,105_005);eq(7,quantized.target(105_005),"handler jitter respects native integer 5sec, not accidental10sec");
        FanNodeIoTest.Memory denied=new FanNodeIoTest.Memory();denied.writable=false;DirectFanControl unavailable=new DirectFanControl(new FanNodeIo(denied));
        yes(!unavailable.initialize(20),"permission fail closed");eq("legacy-oem",unavailable.status().backend,"honest legacy fallback");
        yes(!unavailable.prepare("charge",20,100_000),"no positive target on failed backend");yes(!unavailable.status().active,"not advertised active");
        FanNodeIoTest.Memory failing=new FanNodeIoTest.Memory();DirectFanControl fail=ready(failing);fail.prepare("daily",10,100_000);
        failing.failNode="level";failing.shortWrite=true;yes(!fail.level(6),"failed write");fail.requested(6,false);
        yes(!fail.available(),"failure disables direct backend");yes(!fail.owns(),"failure releases");eq("legacy-oem",fail.status().backend,"error route honest");
        yes(!fail.status().error.isEmpty(),"failure exposed");yes(!fail.state(true),"no enable after failed level");
        eq(1,failing.writes.size(),"no retry");
        FanNodeIoTest.Memory notApplied=new FanNodeIoTest.Memory();DirectFanControl missingWrite=ready(notApplied);
        missingWrite.prepare("daily",10,100_000);missingWrite.requested(6,true);
        yes(!missingWrite.available(),"missing actual write/readback fails closed");
        yes(!missingWrite.status().active,"setter call alone not advertised active");
        yes(!missingWrite.status().error.isEmpty(),"unconfirmed request error exposed");
        FanNodeIoTest.Memory normal=new FanNodeIoTest.Memory();DirectFanControl noEndpoints=new DirectFanControl(new FanNodeIo(normal));
        noEndpoints.unavailable("precise method unavailable");yes(!noEndpoints.available(),"missing endpoints disabled");eq(0,normal.writes.size(),"no guessed hooks/write");
        FanNodeIoTest.Memory stalledMemory=new FanNodeIoTest.Memory();DirectFanControl stalled=ready(stalledMemory);
        stalled.prepare("charge",20,100_000,100_000);apply(stalled,6);LeaseQueue handler=new LeaseQueue(stalled);handler.arm();
        DirectFanControl.Lease originalLease=stalled.lease();eq(115_000L,originalLease.deadline,"15sec source-sample lease");
        yes(stalled.fresh(114_999),"fresh just before deadline");yes(!stalled.fresh(115_000),"expires at exact15sec");
        handler.advance(114_999);eq(0,handler.stops.size(),"no early stop");
        int initialWrites=stalledMemory.writes.size();
        // No prepare, target read, worker sample or worker IPC occurs after initial arming.
        handler.advance(115_000);eq(1,handler.stops.size(),"handler schedules stop with worker permanently stalled");
        yes(!stalled.owns(),"expiry releases software target immediately");eq(1,stalledMemory.state,"queued stop not falsely treated as physically off");
        eq("queued",stalled.status().safetyStop,"queued diagnostic");handler.drain();
        eq(0,stalledMemory.state,"handler-only deadline stop");eq(initialWrites+1,stalledMemory.writes.size(),"one state0, no ramp/level write");
        eq("confirmed-off",stalled.status().safetyStop,"confirmed-off diagnostic");yes(stalled.status().reason.contains("state=0 confirmed"),"off claim backed by state readback");
        yes(stalled.queueSafetyStop("duplicate")==null,"finished generation cannot blindly repeat stop");
        handler.advance(999_000);handler.drain();eq(1,handler.nativeStops,"no watchdog retry loop");
        yes(stalled.expireLease(originalLease,999_000)==null,"old lease cannot reissue stop");

        FanNodeIoTest.Memory renewalMemory=new FanNodeIoTest.Memory();DirectFanControl renewal=ready(renewalMemory);
        renewal.prepare("daily",10,200_000,200_000);apply(renewal,6);handler=new LeaseQueue(renewal);handler.arm();
        DirectFanControl.Lease old=renewal.lease();renewal.prepare("daily",10,210_000,205_000);handler.arm();
        DirectFanControl.Lease current=renewal.lease();eq(220_000L,current.deadline,"deadline uses source sample, not later refresh");
        yes(current.generation!=old.generation,"renewal changes ownership generation");
        handler.advance(215_000);handler.drain();eq(0,handler.nativeStops,"old timer cannot expire renewed owner");
        yes(renewal.lease()==current,"old timer cannot cancel new lease");handler.advance(219_999);eq(0,handler.stops.size(),"new owner not early");
        handler.advance(220_000);handler.drain();eq(1,handler.nativeStops,"new deadline stops exactly once");

        FanNodeIoTest.Memory sameMemory=new FanNodeIoTest.Memory();DirectFanControl sameSample=ready(sameMemory);
        sameSample.prepare("charge",20,300_000,300_000);apply(sameSample,6);handler=new LeaseQueue(sameSample);handler.arm();
        sameSample.prepare("charge",20,314_000,300_000);handler.arm();eq(315_000L,sameSample.lease().deadline,"reused input cannot extend lease");
        DirectFanControl.Lease last=sameSample.lease();yes(!sameSample.prepare("charge",20,315_000,300_000),"stale sample rejected");
        yes(sameSample.lease()==last,"rejected stale sample retains watchdog");handler.advance(315_000);handler.drain();eq(0,sameMemory.state,"reused/stale sample still stopped");

        FanNodeIoTest.Memory manualMemory=new FanNodeIoTest.Memory();DirectFanControl manual=ready(manualMemory);
        manual.prepare("daily",10,400_000);apply(manual,6);handler=new LeaseQueue(manual);handler.arm();handler.advance(415_000);
        DirectFanControl.SafetyStop obsolete=handler.stops.get(0);int manualWrites=manualMemory.writes.size();
        manual.release("manual handoff");manualMemory.level=12;manualMemory.state=1;manualMemory.effective=12;
        handler.drain();eq(0,handler.nativeStops,"queued stop invalidated by later manual handoff");
        eq(manualWrites,manualMemory.writes.size(),"obsolete manual stop does not touch node");eq(1,manualMemory.state,"manual fan not disabled");
        eq("manual handoff",manual.status().reason,"obsolete finalizer cannot overwrite handoff reason");
        yes(!manual.finishSafetyStop(obsolete,true,"late result"),"obsolete finalization rejected");
        eq("cancelled-owner-changed",manual.status().safetyStop,"cancelled ticket reported accurately");

        FanNodeIoTest.Memory newOwnerMemory=new FanNodeIoTest.Memory();DirectFanControl newOwner=ready(newOwnerMemory);
        newOwner.prepare("daily",10,500_000);apply(newOwner,6);handler=new LeaseQueue(newOwner);handler.arm();handler.advance(515_000);
        DirectFanControl.SafetyStop expired=handler.stops.get(0);
        newOwner.prepare("charge",20,516_000);DirectFanControl.Lease newLease=newOwner.lease();handler.drain();
        eq(0,handler.nativeStops,"queued old-owner stop cannot stop later software owner");yes(newOwner.lease()==newLease,"new owner lease preserved");
        yes(!newOwner.finishSafetyStop(expired,false,"late failure"),"old failure cannot disable newer owner");yes(newOwner.available(),"late failure cannot poison backend");
        DirectFanControl.SafetyStop reentrant=newOwner.queueSafetyStop("test reentrant");yes(newOwner.beginSafetyStop(reentrant),"start one recovery");
        yes(!newOwner.beginSafetyStop(reentrant),"same recovery cannot start twice");newOwner.release("reentrant handoff");
        newOwner.prepare("daily",10,517_000);DirectFanControl.Lease afterReentry=newOwner.lease();
        yes(!newOwner.finishSafetyStop(reentrant,true,"late success"),"running old stop finalizer cannot release reentrant owner");
        yes(newOwner.lease()==afterReentry,"reentrant new lease unchanged");yes(newOwner.owns(),"reentrant owner remains active");

        for(String broken:new String[]{"level_eff","status","level"}){
            FanNodeIoTest.Memory partial=new FanNodeIoTest.Memory();DirectFanControl leasePartial=ready(partial);
            leasePartial.prepare("charge",20,600_000);apply(leasePartial,6);handler=new LeaseQueue(leasePartial);handler.arm();partial.failReadNode=broken;
            handler.advance(615_000);handler.drain();eq(0,partial.state,"lease disables despite unreadable "+broken);
            eq("confirmed-off",leasePartial.status().safetyStop,"lease physical confirmation ignores "+broken);
        }
        FanNodeIoTest.Memory cannotConfirm=new FanNodeIoTest.Memory();DirectFanControl uncertainStop=ready(cannotConfirm);
        uncertainStop.prepare("daily",10,700_000);apply(uncertainStop,6);handler=new LeaseQueue(uncertainStop);handler.arm();
        cannotConfirm.failReadNodeAfterWrite="state";initialWrites=cannotConfirm.writes.size();handler.advance(715_000);handler.drain();
        eq("unconfirmed",uncertainStop.status().safetyStop,"unknown readback not labelled stopped");
        yes(uncertainStop.status().reason.contains("UNCONFIRMED"),"reason explicitly states uncertainty");
        yes(!uncertainStop.status().reason.contains("state=0 confirmed"),"no false off claim");
        yes(!uncertainStop.status().safetyStopError.isEmpty(),"uncertain stop diagnostic");
        yes(!uncertainStop.available(),"uncertain stop fails closed");eq(-1,uncertainStop.status().state,"unverified state not cached zero");
        handler.advance(999_000);handler.drain();eq(initialWrites+1,cannotConfirm.writes.size(),"unknown stop never retried by watchdog");
        yes(uncertainStop.queueSafetyStop("try again")==null,"unknown stop no same-owner requeue");
        FanNodeIoTest.Memory stopFailed=new FanNodeIoTest.Memory();DirectFanControl unsuccessful=ready(stopFailed);
        unsuccessful.prepare("daily",10,800_000);apply(unsuccessful,6);handler=new LeaseQueue(unsuccessful);handler.arm();stopFailed.ignoreWrite=true;
        handler.advance(815_000);handler.drain();eq(1,stopFailed.state,"failed disable remains physically on in fixture");
        eq("unconfirmed",unsuccessful.status().safetyStop,"failed disable not success");yes(unsuccessful.status().reason.contains("UNCONFIRMED"),"failed disable accurate reason");
        yes(!unsuccessful.status().active,"failed disable cannot claim direct active");
        System.out.println("DirectFanControlTest: "+checks+" assertions passed");
    }
}
