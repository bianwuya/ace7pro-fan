package dev.eta.acefan;
public final class SamplingTest {
  static void eq(long got,long expected){if(got!=expected)throw new AssertionError(got+" != "+expected);}
  static void eqb(boolean got,boolean expected){if(got!=expected)throw new AssertionError(got+" != "+expected);}
  public static void main(String[] args){
    eq(Sampling.interval(true,true),5000);
    eq(Sampling.interval(true,false),5000);
    eq(Sampling.interval(false,true),10000);
    eq(Sampling.interval(false,false),30000);
    eq(Sampling.staleMs(5000),15000);
    eq(Sampling.staleMs(10000),25000);
    eq(Sampling.staleMs(30000),65000);
    eqb(Sampling.shouldPush(true,false,0,0),true);
    eqb(Sampling.shouldPush(false,true,0,0),true);
    eqb(Sampling.shouldPush(false,false,19999,0.1),false);
    eqb(Sampling.shouldPush(false,false,20000,0),true);
    eqb(Sampling.shouldPush(false,false,5000,0.2),true);
    eqb(Sampling.shouldPush(false,false,5000,0.19),false);
    eqb(Sampling.shouldPush(false,false,5000,Double.NaN),false);
    double m=Sampling.maxDelta(new double[]{35.0,Double.NaN,38.0,39.0},new double[]{35.1,37.0,38.0,Double.NaN});
    if(Math.abs(m-0.1)>1e-9)throw new AssertionError("maxDelta "+m);
    if(Sampling.maxDelta(null,new double[]{1})!=0)throw new AssertionError("null guard");
    System.out.println("Sampling: tiers, stale budget, push throttle and delta checks PASS");
  }
}
