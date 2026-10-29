package com.moveinsync.gps.core;

/** Geodesy helpers. StrictMath is used so results are bit-identical on every JVM/CPU (billing determinism). */
public final class Geo {
    private static final double EARTH_R_M = 6_371_008.8;
    private Geo() {}

    public static double meters(double la1, double lo1, double la2, double lo2) {
        double p1 = StrictMath.toRadians(la1), p2 = StrictMath.toRadians(la2);
        double dp = p2 - p1, dl = StrictMath.toRadians(lo2 - lo1);
        double s1 = StrictMath.sin(dp / 2), s2 = StrictMath.sin(dl / 2);
        double a = s1 * s1 + StrictMath.cos(p1) * StrictMath.cos(p2) * s2 * s2;
        return 2 * EARTH_R_M * StrictMath.asin(Math.min(1.0, StrictMath.sqrt(a)));
    }

    /** Null Island (0,0) is treated as invalid: it is what many devices send when they have no fix. */
    public static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                && !(lat == 0 && lon == 0);
    }
}
