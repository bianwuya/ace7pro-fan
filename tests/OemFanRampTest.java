package dev.eta.acefan;

public final class OemFanRampTest {
    private static int checks;
    private static void eq(long wanted,long actual,String label){checks++;if(wanted!=actual)throw new AssertionError(label+": "+actual+" != "+wanted);}
    public static void main(String[] args){
        OemFanRamp ramp=new OemFanRamp(6,20);
        eq(6,ramp.update(20,100,true),"first start 0 -> minimum, not hot target");
        eq(7,ramp.update(20,105,false),"5 sec up");
        eq(8,ramp.update(20,110,false),"next up");
        eq(8,ramp.update(0,115,false),"down pending");eq(110,ramp.changedSeconds(),"pending time unchanged");
        eq(7,ramp.update(0,120,false),"10 sec down");
        eq(7,ramp.update(0,125,false),"next pending");
        eq(6,ramp.update(0,130,false),"stop approaches minimum");
        eq(0,ramp.update(0,135,false),"then off, not level zero write");
        eq(0,ramp.update(0,140,false),"off holds");eq(140,ramp.changedSeconds(),"off refreshes time");
        ramp.reset();eq(14,ramp.update(14,200,false),"ordinary uninitialized limiter adopts target");
        eq(14,ramp.update(14,500,false),"same target holds");
        eq(14,ramp.update(20,501,false),"idle credit not accumulated");eq(500,ramp.changedSeconds(),"new pending unchanged");
        eq(15,ramp.update(20,505,false),"5 sec after same target");
        eq(19,ramp.update(20,525,false),"delayed tick allows multiple levels");
        eq(20,ramp.update(500,530,false),"maximum clamp");
        eq(20,ramp.update(1,535,false),"positive minimum clamps but respects down limit");
        eq(19,ramp.update(1,540,false),"down to clamped target");
        eq(19,ramp.update(20,400,false),"reverse time no large step");
        ramp.reset();eq(6,ramp.update(1,600,false),"low positive request clamps");
        ramp.seed(8,700);
        int[] stop={8,7,7,6,0};
        for(int i=0;i<stop.length;i++)eq(stop[i],ramp.update(0,705+5*i,false),"audited stop sequence "+i);
        OemFanRamp hotGame=new OemFanRamp(6,20,0.3,0.1);
        eq(6,hotGame.update(20,100,true),"game first");
        eq(7,hotGame.update(20,105,false),"trunc 1.5");
        eq(8,hotGame.update(20,110,false),"no fractional carry");
        eq(10,hotGame.update(20,117,false),"7sec 0.3 ->2");
        ramp.seed(18,800);eq(18,ramp.level(),"software requested seed, not effective");
        eq(19,ramp.update(20,805,false),"preserve software state after driver derate");
        try{new OemFanRamp(6,5);throw new AssertionError("invalid profile accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("OemFanRampTest: "+checks+" assertions passed");
    }
}
