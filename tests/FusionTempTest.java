package dev.eta.acefan;

/** Fusion normalization and the continuous ramp: boundaries, hysteresis, clamping. */
public final class FusionTempTest {
  static void eq(Object got, Object expected) {
    String a = String.valueOf(got), b = String.valueOf(expected);
    if (!a.equals(b)) throw new AssertionError(a + " != " + b);
  }
  static void near(double got, double expected) {
    if (Double.isNaN(expected)) { if (!Double.isNaN(got)) throw new AssertionError(got + " != NaN"); return; }
    if (Math.abs(got - expected) > 1e-9) throw new AssertionError(got + " !~ " + expected);
  }

  public static void main(String[] args) {
    // Weighted mean normalizes over usable weighted channels only.
    near(FusionTemp.fuse(new double[]{30, 40, 50, 20}, new double[]{0.5, 0.5, 0, 0}), 35.0);
    near(FusionTemp.fuse(new double[]{30, Double.NaN, 50, 20}, new double[]{0.5, 0.5, 0, 0}), 30.0);
    // A channel that is out of band is unknown, not cold.
    near(FusionTemp.fuse(new double[]{30, -5, 50, 20}, new double[]{0.5, 0.5, 0, 0}), 30.0);
    near(FusionTemp.fuse(new double[]{30, 200, 50, 20}, new double[]{0.5, 0.5, 0, 0}), 30.0);
    // Zero weights contribute nothing: the battery-only default must reproduce battery.
    near(FusionTemp.fuse(new double[]{34.5, 38.0, 35.4, 36.1}), 34.5);
    // No usable evidence at all yields NaN, never a fabricated zero.
    near(FusionTemp.fuse(new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN}), Double.NaN);
    near(FusionTemp.fuse(new double[]{30, 40, 50, 20}, new double[]{0, 0, 0, 0}), Double.NaN);
    near(FusionTemp.fuse(null, null), Double.NaN);
    // Mismatched array lengths never read past the shorter one.
    near(FusionTemp.fuse(new double[]{30}, new double[]{1.0, 1.0}), 30.0);
    eq(FusionTemp.contributors(new double[]{34.5, Double.NaN, 35.4, 36.1}), 1);
    eq(FusionTemp.contributors(new double[]{34.5, 38.0, 35.4, 36.1}, new double[]{1, 1, 0, 0}), 2);
    eq(FusionTemp.valid(-0.1), false);
    eq(FusionTemp.valid(0.0), true);
    eq(FusionTemp.valid(65.0), true);
    eq(FusionTemp.valid(65.1), false);

    // Default weights are battery-only and sum to 1.
    near(FusionTemp.WEIGHTS[0], 1.0);
    near(FusionTemp.WEIGHTS[1] + FusionTemp.WEIGHTS[2] + FusionTemp.WEIGHTS[3], 0.0);
    eq(FusionTemp.CHANNELS.length, FusionTemp.WEIGHTS.length);

    // Ramp: the retired table's anchor points stay reachable.
    ChargeCurve c = new ChargeCurve();
    eq(c.update(32.9, 0, true), 0);
    eq(c.update(33.0, 5000, true), 6);        // start → floor
    eq(c.update(35.0, 10000, true), 10);      // +2 °C, gain 2.0
    eq(c.update(37.0, 15000, true), 14);
    eq(c.update(39.0, 20000, true), 18);
    eq(c.update(40.0, 25000, true), 20);      // ceiling
    eq(c.update(50.0, 30000, true), 20);      // clamped, no overflow
    // Continuous ramp: a value between anchors yields an intermediate level.
    eq(c.update(34.0, 35000, true), 8);
    eq(c.update(33.3, 40000, true), 7);
    // Hysteresis: inside the 1 °C band the demand holds even though the ramp clamps to floor.
    eq(c.update(32.5, 45000, true), 6);       // 33.0-1.0 <= 32.5: still enabled
    eq(c.update(31.9, 50000, true), 6);       // outside the band: cooling timer starts
    eq(c.update(31.9, 79999, true), 6);       // 29999 ms elapsed: still holding
    eq(c.update(31.9, 80000, true), 0);       // 30 s reached: demand drops to zero
    // Recovery re-arms immediately without waiting for the confirm window.
    eq(c.update(33.0, 80000, true), 6);
    // Disallowed or invalid input stops the demand outright.
    eq(c.update(45.0, 85000, false), 0);
    eq(c.update(Double.NaN, 90000, true), 0);
    eq(c.update(200, 95000, true), 0);
    eq(c.update(-1, 100000, true), 0);

    // Re-configure takes effect on the next evaluation, not at the next restart.
    ChargeCurve d = new ChargeCurve();
    eq(d.update(34.0, 0, true), 8);
    d.configure(30.0, 2.0, 6, 20);
    eq(d.update(34.0, 5000, true), 14);       // (34-30)*2 + 6
    eq(d.start(), 30.0);
    // Out-of-range settings are rejected, keeping the previous value.
    d.configure(99.0, 0.0, 0, 99);
    eq(d.start(), 30.0);
    eq(d.gain(), 2.0);
    eq(d.floor(), 6);
    eq(d.ceiling(), 20);
    // floor above ceiling is corrected rather than producing an inverted clamp.
    d.configure(33.0, 2.0, 18, 6);
    eq(d.ceiling(), 18);
    eq(d.level() <= d.ceiling(), true);

    System.out.println("FusionTemp: normalization, missing-channel, zero-weight, band, ramp, hysteresis, clamping and configure PASS");
  }
}