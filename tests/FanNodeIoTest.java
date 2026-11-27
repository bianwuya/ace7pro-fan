package dev.eta.acefan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FanNodeIoTest {
    static final class Memory implements FanNodeIo.Access {
        int state=0,level=10,effective=0,reads;
        String status="NORMAL", failNode="", failReadNode="", failReadNodeAfterWrite="";
        int failStateReads;
        boolean writable=true,shortWrite,throwWrite,applyOnError,ignoreWrite,failRead,mutateState,failAfterWrite,invalidStateAfterWrite;
        final List<String> writes=new ArrayList<String>();
        public String read(String name)throws IOException{
            reads++;if(failRead||name.equals(failReadNode))throw new IOException("test unreadable "+name);
            if("state".equals(name)&&failStateReads>0){failStateReads--;throw new IOException("test transient state read");}
            if("state".equals(name))return invalidStateAfterWrite&&!writes.isEmpty()?"invalid":String.valueOf(state);
            if("level".equals(name))return String.valueOf(level);
            if("level_eff".equals(name))return String.valueOf(effective);
            if("status".equals(name))return status;
            throw new AssertionError("forbidden read "+name);
        }
        public boolean writable(String name){if(!"state".equals(name)&&!"level".equals(name))throw new AssertionError(name);return writable;}
        public int write(String name,String value)throws IOException{
            if(!"state".equals(name)&&!"level".equals(name))throw new AssertionError("forbidden write "+name);
            writes.add(name+"="+value);
            boolean fails=name.equals(failNode);
            if(!ignoreWrite&&(!fails||applyOnError)){
                if("state".equals(name)){state=Integer.parseInt(value);effective=state==0?0:level;}
                else level=Integer.parseInt(value);
            }
            if(mutateState)state=1-state;
            if(failAfterWrite)failRead=true;
            if(!failReadNodeAfterWrite.isEmpty())failReadNode=failReadNodeAfterWrite;
            if(fails&&throwWrite)throw new IOException("test write failure");
            return fails&&shortWrite?0:value.length();
        }
    }
    private static int checks;
    static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void eq(Object wanted,Object actual,String label){checks++;if(!wanted.equals(actual))throw new AssertionError(label+": "+actual+" != "+wanted);}
    static FanNodeIo ready(Memory memory){FanNodeIo node=new FanNodeIo(memory);yes(node.probe(20),"probe");return node;}
    public static void main(String[] args){
        Memory m=new Memory();FanNodeIo node=ready(m);
        eq(0,m.writes.size(),"probe read only");
        yes(node.level(6),"positive level first");yes(node.state(true),"enable after level");
        eq("[level=6, state=1]",m.writes.toString(),"startup order");
        yes(node.level(6),"same level");yes(node.state(true),"same state");
        eq(2,m.writes.size(),"no duplicate write");
        m.effective=3;yes(node.level(6),"driver derate accepted");eq(2,m.writes.size(),"never correct effective level");
        yes(node.state(false),"stop");eq("state=0",m.writes.get(2),"stop state only");eq(6,m.level,"stop preserves request");
        yes(node.state(false),"repeat off");eq(3,m.writes.size(),"no duplicate disable");
        yes(node.level(6),"re-enable prepares even equal stopped request");yes(node.state(true),"re-enable");
        eq("[level=6, state=1, state=0, level=6, state=1]",m.writes.toString(),"enable edge only");
        Memory unprepared=new Memory();FanNodeIo n=ready(unprepared);
        yes(!n.state(true),"enable without positive level refused");eq(0,unprepared.writes.size(),"no unsafe enable");
        Memory denied=new Memory();denied.writable=false;n=new FanNodeIo(denied);
        yes(!n.probe(20),"permission denied");denied.writable=true;yes(!n.probe(20),"no permission retry");
        yes(!n.level(6),"no failed-probe writes");eq(0,denied.writes.size(),"no permission bypass");
        Memory shortM=new Memory();n=ready(shortM);shortM.failNode="level";shortM.shortWrite=true;
        int reads=shortM.reads;yes(!n.level(6),"short write fails");yes(shortM.reads>reads+4,"readback after short write");
        yes(!n.state(true),"short write cannot enable");yes(!n.level(6),"no blind level retry");eq(1,shortM.writes.size(),"only one attempt");
        Memory uncertain=new Memory();n=ready(uncertain);uncertain.failNode="level";uncertain.throwWrite=true;uncertain.applyOnError=true;
        yes(!n.level(6),"exception remains uncertain even matching readback");eq(6,n.last().level,"exception readback retained");
        yes(!n.state(true),"matching uncertain level not enabled");
        Memory mismatch=new Memory();n=ready(mismatch);mismatch.ignoreWrite=true;
        yes(!n.level(6),"readback mismatch");yes(n.error().contains("readback"),"error evidence");
        Memory readFailure=new Memory();n=ready(readFailure);readFailure.failAfterWrite=true;
        yes(!n.level(6),"readback failure");yes(!n.level(6),"readback failure latched");eq(1,readFailure.writes.size(),"not repeated");
        Memory enabled=new Memory();enabled.state=1;enabled.level=18;enabled.effective=12;n=ready(enabled);
        yes(n.level(18),"existing request retained");eq(0,enabled.writes.size(),"18/12 no rewrite");
        yes(n.level(19),"changed request written");enabled.effective=10;yes(n.inspect()!=null,"effective movement not conflict");
        enabled.level=20;yes(n.inspect()==null,"other writer conflict");yes(!n.level(19),"do not fight other writer");eq(1,enabled.writes.size(),"conflict no compensating writes");
        for(String status:new String[]{"BLOCKED","DAMAGED","OCP_ISSUE","RPM_LOW","NOT_PRESENT","PRESENT","unknown",""}){
            Memory fault=new Memory();fault.status=status;n=new FanNodeIo(fault);
            yes(!n.probe(20),"unsafe/unknown status "+status);eq(0,fault.writes.size(),"fault no activation");
        }
        Memory faultRunning=new Memory();faultRunning.state=1;faultRunning.effective=10;n=ready(faultRunning);
        faultRunning.status="BLOCKED";yes(n.inspect()==null,"live fault");yes(n.stop(),"fault immediate disable without ramp");
        eq("[state=0]",faultRunning.writes.toString(),"fault stop only");
        Memory badStop=new Memory();badStop.state=1;badStop.effective=10;n=ready(badStop);
        badStop.failNode="state";badStop.shortWrite=true;badStop.ignoreWrite=true;
        yes(!n.stop(),"uncertain stop");yes(!n.stop(),"no blind stop retry");eq(1,badStop.writes.size(),"single stop attempt");
        Memory race=new Memory();n=ready(race);race.mutateState=true;
        yes(!n.level(6),"unrelated state changed mid write");yes(!n.state(true),"race does not trigger enable");
        Memory invalid=new Memory();n=ready(invalid);yes(!n.level(0),"never write level zero");eq(0,invalid.writes.size(),"zero no syscall");
        Memory invalidMax=new Memory();n=new FanNodeIo(invalidMax);yes(!n.probe(21),"unknown max fail closed");
        for(String auxiliary:new String[]{"level_eff","status","level"}){
            Memory partial=new Memory();partial.state=1;partial.effective=10;n=ready(partial);
            partial.failReadNode=auxiliary;yes(n.inspect()==null,"auxiliary read fails "+auxiliary);
            int beforeReads=partial.reads;
            yes(n.stop(),"state-only stop survives "+auxiliary+" failure");
            yes(n.stopConfirmed(),"off state confirmed despite bad "+auxiliary);
            eq("[state=0]",partial.writes.toString(),"exactly one disable for "+auxiliary);
            eq(beforeReads+2,partial.reads,"stop reads only pre/post state for "+auxiliary);
            eq(0,n.last().state,"verified off state");eq(-1,n.last().effective,"no invented effective readback");
            yes(n.stop(),"already off remains read-only");eq(1,partial.writes.size(),"no repeat disable");
        }
        Memory unknownBefore=new Memory();unknownBefore.state=1;unknownBefore.effective=10;n=ready(unknownBefore);
        unknownBefore.failStateReads=1;yes(n.stop(),"unknown pre-state permits one safety disable");
        yes(n.stopConfirmed(),"post-state confirms unknown-pre stop");eq(1,unknownBefore.writes.size(),"one unknown-pre attempt");
        Memory unknownAfter=new Memory();unknownAfter.state=1;unknownAfter.effective=10;n=ready(unknownAfter);
        unknownAfter.failReadNodeAfterWrite="state";
        yes(!n.stop(),"unknown post-state not success");yes(!n.stopConfirmed(),"no false confirmation");
        eq(-1,n.last().state,"unknown post-state clears cached state");yes(!n.stop(),"unknown post-state no blind retry");
        eq(1,unknownAfter.writes.size(),"unknown post-state exactly one write");yes(!n.stopError().isEmpty(),"stop diagnostic retained");
        Memory unknownBoth=new Memory();unknownBoth.state=1;unknownBoth.effective=10;n=ready(unknownBoth);unknownBoth.failReadNode="state";
        yes(!n.stop(),"unreadable pre/post still one best effort");eq(1,unknownBoth.writes.size(),"both unknown single write");
        yes(!n.stop(),"both unknown no retry");eq(1,unknownBoth.writes.size(),"still single write");
        Memory malformed=new Memory();malformed.state=1;malformed.effective=10;n=ready(malformed);malformed.invalidStateAfterWrite=true;
        yes(!n.stop(),"malformed stop readback unconfirmed");yes(!n.stop(),"malformed readback no retry");eq(1,malformed.writes.size(),"malformed single write");
        Memory threwButOff=new Memory();threwButOff.state=1;threwButOff.effective=10;n=ready(threwButOff);
        threwButOff.failNode="state";threwButOff.throwWrite=true;threwButOff.applyOnError=true;
        yes(!n.stop(),"write exception not disguised as syscall success");yes(n.stopConfirmed(),"exception with explicit state0 records physical confirmation separately");
        eq(0,n.last().state,"exception off readback retained");yes(!n.stop(),"uncertain syscall not retried");eq(1,threwButOff.writes.size(),"exception single write");
        System.out.println("FanNodeIoTest: "+checks+" assertions passed");
    }
}
