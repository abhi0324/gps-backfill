package com.moveinsync.gps.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Test doubles + builders. */
final class Fixtures {
    static final long T0 = 1_700_000_000_000L;
    static final String TENANT = "acme";

    /** Deterministic fake provider: driving km = 1.25 x aerial. Can be told to fail. */
    static final class FakeProvider implements RoutingProvider {
        final AtomicInteger requests = new AtomicInteger(), okBudget = new AtomicInteger(Integer.MAX_VALUE);
        public double[] drivingKm(List<Query> b) throws ProviderException {
            requests.incrementAndGet();
            if (okBudget.getAndDecrement() <= 0) throw new ProviderException("down");
            double[] r = new double[b.size()];
            for (int i = 0; i < r.length; i++) {
                Query q = b.get(i);
                r[i] = Geo.meters(q.fromLat(), q.fromLon(), q.toLat(), q.toLon()) * 1.25 / 1000.0;
            }
            return r;
        }
        void heal() { okBudget.set(Integer.MAX_VALUE); }
        void breakNow() { okBudget.set(0); }
    }

    record Rig(DistanceEngine engine, FakeProvider provider, EngineMetrics metrics, AtomicLong clock, RoutingGateway gw) {}

    static Rig rig(EngineConfig cfg) {
        FakeProvider p = new FakeProvider(); EngineMetrics m = new EngineMetrics(); AtomicLong clock = new AtomicLong(1_000);
        RoutingGateway gw = new RoutingGateway(p, cfg, new CircuitBreaker(3, 1_000, clock::get), 100_000, 86_400_000L, clock::get, m);
        return new Rig(new DistanceEngine(cfg, gw, m), p, m, clock, gw);
    }
    static Rig rig() { return rig(EngineConfig.defaults()); }

    static Ping p(double lat, double lon, long sec) { return new Ping(lat, lon, T0 + sec * 1000, 5); }
    static Ping p(double lat, double lon, long sec, double acc) { return new Ping(lat, lon, T0 + sec * 1000, acc); }

    /** ~10 m/s northbound line, one ping per second. */
    static List<Ping> line(int seconds) {
        List<Ping> l = new ArrayList<>();
        for (int s = 0; s <= seconds; s++) l.add(p(12.9 + s * 0.00009, 77.6, s));
        return l;
    }
    static TripEvidence trip(String id, List<Ping> pings) {
        Ping last = pings.stream().max(Comparator.comparingLong(Ping::ts)).orElseThrow();
        Loc end = new Loc(last.lat(), last.lon());
        return new TripEvidence(TENANT, id, pings, null, null, List.of(), end, end, last.ts());
    }
    static TripEvidence trip(List<Ping> pings) { return trip("T1", pings); }

    static long sumAerial(List<Ping> pings) {
        long t = 0;
        for (int i = 1; i < pings.size(); i++)
            t += Math.round(Geo.meters(pings.get(i - 1).lat(), pings.get(i - 1).lon(), pings.get(i).lat(), pings.get(i).lon()));
        return t;
    }
}
