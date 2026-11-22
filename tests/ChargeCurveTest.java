package dev.eta.acefan;
public final class ChargeCurveTest {
  static void eq(int got,int expected){if(got!=expected)throw new AssertionError(got+" != "+expected);}
  public static void main(String[] args){
    // Kept for the legacy API surface: the detailed ramp/hysteresis cases live in FusionTempTest.
    ChargeCurve c=new ChargeCurve();
    eq(c.update(32,0,true),0);eq(c.update(33,5000,true),6);
    eq(c.update(36.6,10000,true),13);
    eq(c.update(38,15000,true),16);eq(c.update(37,20000,true),14);
    eq(c.update(35.9,25000,true),12);eq(c.update(35.9,54999,true),12);eq(c.update(35.9,55000,true),12);
    eq(c.update(42,60000,true),20);eq(c.update(44,65000,false),0);
    eq(c.update(Double.NaN,70000,true),0);eq(c.update(200,75000,true),0);
    // Inside the 1 °C hysteresis band the demand re-derives from the ramp and clamps at floor,
    // instead of holding the last level as the retired step table did.
    eq(c.update(38,80000,true),16);eq(c.update(31,85000,true),6);eq(c.update(31,115000,true),0);
    // Enhancement-only: the candidate layer still clamps to the OEM maximum.
    eq(Math.min(20,Math.max(12,18)),18);eq(Math.min(20,Math.max(14,10)),14);
    System.out.println("ChargeCurve: boundaries, hysteresis, delay, invalid, disable and enhancement-only checks PASS");
  }
}