package com.moveinsync.gps.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Step I: invalid/low-accuracy filter -> sort -> de-duplicate -> teleport filter. O(n log n) worst, ~O(n) on near-sorted input. */
final class PingCleaner {
    record Cleaned(List<Ping> pings, int invalid, int lowAccuracy, int duplicates, int teleports) {}

    private static final Comparator<Ping> ORDER = Comparator.comparingLong(Ping::ts)
            .thenComparingDouble(Ping::lat).thenComparingDouble(Ping::lon).thenComparingDouble(Ping::accuracyM);

    private PingCleaner() {}

    static Cleaned clean(List<Ping> in, EngineConfig c) {
        List<Ping> ok = new ArrayList<>(in.size());
        int invalid = 0, low = 0;
        for (Ping p : in) {
            if (p == null || !Geo.valid(p.lat(), p.lon()) || p.ts() <= 0) { invalid++; continue; }
            if (Double.isNaN(p.accuracyM()) || p.accuracyM() > c.maxAccuracyM()) { low++; continue; }
            ok.add(p);
        }
        ok.sort(ORDER);
        List<Ping> out = new ArrayList<>(ok.size());
        int dup = 0, tele = 0;
        Ping prev = null, last = null;
        for (Ping p : ok) {
            if (prev != null && prev.ts() == p.ts() && prev.lat() == p.lat() && prev.lon() == p.lon()) { dup++; continue; }
            prev = p;
            if (last != null) {
                double dt = (p.ts() - last.ts()) / 1000.0;
                double d = Geo.meters(last.lat(), last.lon(), p.lat(), p.lon());
                double kmh = dt <= 0 ? (d > 0 ? Double.POSITIVE_INFINITY : 0) : d / dt * 3.6;
                if (kmh > c.speedLimitKmh()) { tele++; continue; } // compared with last ACCEPTED fix, so a burst is dropped whole
            }
            out.add(p);
            last = p;
        }
        return new Cleaned(out, invalid, low, dup, tele);
    }
}
