package com.moveinsync.gps.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rounding policy: each segment is rounded half-up to whole metres, totals are exact long sums,
 * km = metres / 1000 shown with 3 decimals. No floating-point accumulation => order-independent sums.
 */
public record TripResult(String tripId, String ruleVersion, Status status, List<Segment> segments,
                         long totalMeters, Stats stats) {
    public enum Status { OK, DEGRADED }

    public record Stats(int pingsIn, int invalid, int lowAccuracy, int duplicates, int teleports, int anchors,
                        int routableSegments, int providerRequests, int providerLegs, int cacheHits, int fallbacks) {}

    public double totalKm() { return totalMeters / 1000.0; }
    public String totalKmText() { return String.format(Locale.ROOT, "%.3f", totalKm()); }

    /** rule -> {segment count, metres} */
    public Map<Rule, long[]> ruleSummary() {
        Map<Rule, long[]> m = new EnumMap<>(Rule.class);
        for (Segment s : segments) {
            long[] a = m.computeIfAbsent(s.rule(), k -> new long[2]);
            a[0]++; a[1] += s.meters();
        }
        return m;
    }
    public boolean sameKilometresAs(TripResult o) {
        return totalMeters == o.totalMeters && status == o.status && segments.equals(o.segments);
    }
}
