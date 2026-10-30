package com.moveinsync.gps.core;

import static com.moveinsync.gps.core.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ReliabilityTests {
    /** 7 pings 10 min / 3.3 km apart => 6 distinct real gaps. */
    static List<Ping> sixGaps() {
        List<Ping> l = new ArrayList<>();
        for (int i = 0; i <= 6; i++) l.add(p(12.9 + i * 0.03, 77.6, i * 600L));
        return l;
    }
    static TripService service(Rig r) { return new TripService(r.engine(), r.metrics()); }

    @Test void deterministicAcrossRunsCachesAndInputOrder() {
        List<Ping> a = sixGaps(), b = new ArrayList<>(a); Collections.shuffle(b, new Random(3));
        Rig warm = rig();
        TripResult cold = rig().engine().compute(trip(a));
        TripResult w1 = warm.engine().compute(trip(a)), w2 = warm.engine().compute(trip(b));
        assertTrue(cold.sameKilometresAs(w1));
        assertTrue(cold.sameKilometresAs(w2));
    }
    @Test void cacheCutsProviderCalls() {
        Rig r = rig();
        TripResult first = r.engine().compute(trip(sixGaps()));
        TripResult second = r.engine().compute(trip("T2", sixGaps()));
        assertEquals(6, first.stats().providerLegs());
        assertEquals(0, second.stats().providerRequests());
        assertEquals(6, second.stats().cacheHits());
        assertEquals(1, r.provider().requests.get()); // 6 legs, one batched request
    }
    @Test void cacheEntriesExpireAfterTtl() {
        Rig r = rig();
        r.engine().compute(trip(sixGaps()));
        r.clock().addAndGet(86_400_001L);
        assertEquals(1, r.engine().compute(trip("T2", sixGaps())).stats().providerRequests());
    }

    /** FAILURE INJECTION: provider dies halfway through a trip, then recovers. */
    @Test void providerFailsHalfwayThenRecoversAndTripIsRecomputed() {
        EngineConfig cfg = EngineConfig.defaults().withBatchSize(2);
        Rig r = rig(cfg); TripService svc = service(r);
        long healthy = rig(cfg).engine().compute(trip(sixGaps())).totalMeters();

        r.provider().okBudget.set(1); // 1st batch ok, rest fail
        TripResult res = svc.submit(trip(sixGaps()));
        assertEquals(TripResult.Status.DEGRADED, res.status());
        assertEquals(2, res.segments().stream().filter(s -> s.rule() == Rule.ROUTED).count());
        assertEquals(4, res.segments().stream().filter(s -> s.rule() == Rule.FALLBACK_AERIAL_X1_4).count());
        assertEquals(CircuitBreaker.State.OPEN, svc.circuitState());
        assertEquals(1, svc.degradedQueueDepth());

        assertEquals(0, svc.recomputeDegraded()); // still open: stays queued
        r.provider().heal(); r.clock().addAndGet(5_000); // recovery + cooldown elapsed
        assertEquals(1, svc.recomputeDegraded());
        TripResult fixed = svc.get(TENANT, "T1").orElseThrow().result();
        assertEquals(TripResult.Status.OK, fixed.status());
        assertEquals(healthy, fixed.totalMeters());
        assertEquals(0, svc.degradedQueueDepth());
    }
    @Test void degradedTripCannotBeInvoiced() {
        Rig r = rig(); TripService svc = service(r);
        r.provider().breakNow();
        svc.submit(trip(sixGaps()));
        assertThrows(IllegalStateException.class, () -> svc.closeInvoice(TENANT, "T1"));
    }
    @Test void lateGpsAfterBillingProducesSignedDeltaNotARewrite() {
        Rig r = rig(); TripService svc = service(r);
        List<Ping> full = new ArrayList<>(line(1200));
        List<Ping> early = new ArrayList<>(full.subList(0, 300)); early.addAll(full.subList(900, full.size())); // 10-min hole
        svc.submit(trip(early));
        long billed = svc.closeInvoice(TENANT, "T1");
        List<Ping> late = full.subList(300, 900);

        svc.addPings(TENANT, "T1", late);
        TripService.TripRecord rec = svc.get(TENANT, "T1").orElseThrow();
        assertEquals(billed, rec.billedMeters().longValue()); // closed invoice untouched
        assertEquals(1, rec.deltas().size());
        TripService.Delta d = rec.deltas().get(0);
        assertEquals(rec.result().totalMeters() - billed, d.deltaMeters());
        assertTrue(d.signedKm().startsWith("+") || d.signedKm().startsWith("-"));

        svc.addPings(TENANT, "T1", late); // replay: idempotent
        svc.addPings(TENANT, "T1", late);
        assertEquals(1, svc.get(TENANT, "T1").orElseThrow().deltas().size());
    }
    @Test void concurrentLatePingsConvergeToTheSingleThreadedAnswer() throws Exception {
        List<Ping> full = line(1200);
        List<Ping> base = new ArrayList<>(full.subList(0, 100)); base.addAll(full.subList(1100, full.size()));
        List<Ping> missing = full.subList(100, 1100);
        long expected = rig().engine().compute(trip(full)).totalMeters();

        Rig r = rig(); TripService svc = service(r);
        svc.submit(trip(base));
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            List<Ping> slice = new ArrayList<>(missing.subList(t * 125, (t + 1) * 125));
            Collections.shuffle(slice, new Random(t));
            for (int rep = 0; rep < 3; rep++) fs.add(ex.submit(() -> svc.addPings(TENANT, "T1", slice)));
        }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(expected, svc.get(TENANT, "T1").orElseThrow().result().totalMeters());
    }
    @Test void tenantsCannotSeeEachOther() {
        TripService svc = service(rig());
        svc.submit(trip(line(30)));
        assertTrue(svc.get("other-tenant", "T1").isEmpty());
        assertThrows(NoSuchElementException.class, () -> svc.addPings("other-tenant", "T1", List.of()));
    }
}
