package com.moveinsync.gps.core;

import static com.moveinsync.gps.core.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AnchorTests {
    static Stop stop(Stop.Kind k, Stop.RiderType rt, Loc actual, Long aTs, Loc home, Long signIn, Loc planned, Long pTs) {
        return new Stop("emp", k, rt, actual, aTs, home, signIn, planned, pTs);
    }
    static TripEvidence withStops(List<Stop> stops, Loc actualEnd, long endTs) {
        Loc office = new Loc(12.94, 77.6);
        return new TripEvidence(TENANT, "A", List.of(p(12.90, 77.6, 0)), null, null, stops, office, actualEnd, endTs);
    }
    static long ms(int sec) { return T0 + sec * 1000L; }

    @Test void fallbackOrderActualThenProfileThenPlanned() {
        List<Stop> stops = List.of(
            stop(Stop.Kind.DROP, Stop.RiderType.PLANNED, new Loc(12.91, 77.6), ms(100), null, null, null, null),
            stop(Stop.Kind.DROP, Stop.RiderType.ADHOC, new Loc(0, 0), ms(200), new Loc(12.92, 77.6), ms(210), null, null),
            stop(Stop.Kind.DROP, Stop.RiderType.PLANNED, null, null, null, null, new Loc(12.93, 77.6), ms(300)));
        TripResult r = rig().engine().compute(withStops(stops, new Loc(12.94, 77.6), ms(600)));
        Set<Source> seen = new HashSet<>();
        r.segments().forEach(s -> { seen.add(s.from()); seen.add(s.to()); });
        assertTrue(seen.contains(Source.EMPLOYEE_ACTUAL));
        assertTrue(seen.contains(Source.EMPLOYEE_PROFILE));
        assertTrue(seen.contains(Source.EMPLOYEE_PLANNED));
    }
    @Test void noShowAnchorsOnlyBehindFlag() {
        Stop ns = stop(Stop.Kind.NO_SHOW, Stop.RiderType.PLANNED, null, null, null, null, new Loc(12.92, 77.6), ms(300));
        TripEvidence t = withStops(List.of(ns), new Loc(12.94, 77.6), ms(600));
        assertEquals(1, rig(EngineConfig.defaults()).engine().compute(t).stats().anchors()); // office only
        assertEquals(2, rig(EngineConfig.defaults().withNoShow(true)).engine().compute(t).stats().anchors());
    }
    @Test void officeAnchorActualWithin400mElsePlanned() {
        TripResult near = rig().engine().compute(withStops(List.of(), new Loc(12.9425, 77.6), ms(600))); // ~280 m
        TripResult far = rig().engine().compute(withStops(List.of(), new Loc(12.95, 77.6), ms(600))); // ~1.1 km
        assertEquals(Source.OFFICE_ACTUAL, near.segments().get(near.segments().size() - 1).to());
        assertEquals(Source.OFFICE_PLANNED, far.segments().get(far.segments().size() - 1).to());
        assertEquals(ms(600), far.segments().get(far.segments().size() - 1).toTs());
    }
    @Test void piiNeverAppearsInToString() {
        Stop s = stop(Stop.Kind.DROP, Stop.RiderType.ADHOC, null, null, new Loc(12.345678, 77.654321), ms(1), null, null);
        assertFalse(s.toString().contains("12.345678"));
        assertFalse(new Loc(12.345678, 77.654321).toString().contains("12.345678"));
    }
}
