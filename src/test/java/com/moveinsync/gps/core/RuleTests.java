package com.moveinsync.gps.core;

import static com.moveinsync.gps.core.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RuleTests {
    @Test void teleportBurstIsDroppedAndDistanceUnchanged() {
        List<Ping> clean = line(60), noisy = new ArrayList<>(clean);
        for (int s = 20; s < 24; s++) noisy.add(p(12.9 + 0.081, 77.6, s)); // 9 km away for 4 s
        TripResult a = rig().engine().compute(trip(clean)), b = rig().engine().compute(trip(noisy));
        assertEquals(4, b.stats().teleports());
        assertEquals(a.totalMeters(), b.totalMeters());
    }
    @Test void duplicatesOutOfOrderAndLowAccuracyAreCleaned() {
        List<Ping> base = line(60), messy = new ArrayList<>(base);
        messy.addAll(base.subList(10, 20)); // duplicates
        messy.add(p(12.9005, 77.6005, 30, 500)); // low accuracy
        Collections.shuffle(messy, new Random(7)); // out of order
        TripResult r = rig().engine().compute(trip(messy));
        assertEquals(10, r.stats().duplicates());
        assertEquals(1, r.stats().lowAccuracy());
        assertEquals(rig().engine().compute(trip(base)).totalMeters(), r.totalMeters());
    }
    @Test void shortGapsUseAerialAndNeverCallProvider() {
        Rig r = rig(); List<Ping> l = line(60);
        TripResult res = r.engine().compute(trip(l));
        assertTrue(res.segments().stream().allMatch(s -> s.rule() == Rule.SHORT_GAP_AERIAL));
        assertEquals(sumAerial(l), res.totalMeters());
        assertEquals(0, r.provider().requests.get());
    }
    @Test void parkedLongGapIsAerial() {
        TripResult res = rig().engine().compute(trip(List.of(p(12.9, 77.6, 0), p(12.9002, 77.6, 300))));
        assertEquals(Rule.PARKED, res.segments().get(0).rule());
    }
    @Test void realGapIsRouted() {
        Rig r = rig();
        TripResult res = r.engine().compute(trip(List.of(p(12.90, 77.6, 0), p(12.936, 77.6, 600))));
        Segment s = res.segments().get(0);
        assertEquals(Rule.ROUTED, s.rule());
        assertEquals(1, r.provider().requests.get());
        assertEquals(Geo.meters(12.90, 77.6, 12.936, 77.6) * 1.25, s.meters(), 60); // cell-snap tolerance
    }
    @Test void routedOver100kmIsRejected() {
        TripResult res = rig().engine().compute(trip(List.of(p(12.0, 77.6, 0), p(13.35, 77.6, 3 * 3600))));
        assertEquals(Rule.REJECTED_DISTANCE, res.segments().get(0).rule());
        assertEquals(0, res.segments().get(0).meters());
    }
    @Test void routedSpeedRuleRejectsOnlyWhenBothRoutedAndAerialX14ExceedLimit() {
        // aerial 100 km/h (ok), routed 125 km/h (>120), aerial*1.4 = 140 (>120) => rejected
        TripResult res = rig().engine().compute(trip(List.of(p(12.0, 77.6, 0), p(12.15, 77.6, 600))));
        assertEquals(Rule.REJECTED_SPEED, res.segments().get(0).rule());
        // aerial 80 km/h => routed 100, aerial*1.4 = 112 => ROUTED
        TripResult ok = rig().engine().compute(trip(List.of(p(12.0, 77.6, 0), p(12.12, 77.6, 600))));
        assertEquals(Rule.ROUTED, ok.segments().get(0).rule());
    }
}
