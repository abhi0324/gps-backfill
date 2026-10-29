package com.moveinsync.gps.core;

import java.util.Comparator;

record Pt(double lat, double lon, long ts, Source src) {
    /** Total order independent of input order => deterministic output. */
    static final Comparator<Pt> ORDER = Comparator.comparingLong(Pt::ts)
            .thenComparingInt(p -> p.src().ordinal()).thenComparingDouble(Pt::lat).thenComparingDouble(Pt::lon);
}
