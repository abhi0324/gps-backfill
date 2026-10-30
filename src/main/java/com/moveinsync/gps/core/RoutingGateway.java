package com.moveinsync.gps.core;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Cost control for the routing provider: snap to cells -> LRU+TTL cache -> de-duplicate -> batch -> circuit breaker.
 * The provider is always asked about the snapped cell CENTRES, so a cached answer equals a fresh answer:
 * kilometres never depend on cache state (determinism), at the price of <= ~8 m endpoint error at 4 decimals.
 */
public final class RoutingGateway {
    public record Leg(int aLat, int aLon, int bLat, int bLon) {}
    public record Resolution(Map<Leg, Double> km, int requests, int legsSent, int hits) {}
    private record Entry(double km, long at) {}

    private static final int ATTEMPTS = 2; // 1 try + 1 retry per batch

    private final RoutingProvider provider; private final EngineConfig cfg; private final CircuitBreaker breaker;
    private final EngineMetrics metrics; private final LongSupplier clock; private final long ttlMs;
    private final Map<Leg, Entry> cache;

    public RoutingGateway(RoutingProvider provider, EngineConfig cfg, CircuitBreaker breaker,
                          int cacheMaxEntries, long ttlMs, LongSupplier clock, EngineMetrics metrics) {
        this.provider = provider; this.cfg = cfg; this.breaker = breaker; this.metrics = metrics;
        this.clock = clock; this.ttlMs = ttlMs;
        this.cache = new LinkedHashMap<>(1024, 0.75f, true) { // access-order => LRU
            @Override protected boolean removeEldestEntry(Map.Entry<Leg, Entry> e) { return size() > cacheMaxEntries; }
        };
    }

    public CircuitBreaker breaker() { return breaker; }

    Leg legOf(Pt a, Pt b) {
        double s = cfg.cellScale();
        return new Leg((int) Math.round(a.lat() * s), (int) Math.round(a.lon() * s),
                       (int) Math.round(b.lat() * s), (int) Math.round(b.lon() * s));
    }

    private synchronized Double cached(Leg l) {
        Entry e = cache.get(l);
        if (e == null) return null;
        if (clock.getAsLong() - e.at() > ttlMs) { cache.remove(l); return null; } // staleness bound = ttl
        return e.km();
    }
    private synchronized void store(Leg l, double km) { cache.put(l, new Entry(km, clock.getAsLong())); }
    public synchronized int cacheSize() { return cache.size(); }

    /** Legs that could not be resolved (provider down / circuit open) are simply absent from the result map. */
    public Resolution resolve(Collection<Leg> legs) {
        Map<Leg, Double> out = new HashMap<>();
        List<Leg> miss = new ArrayList<>();
        int hits = 0;
        for (Leg l : new LinkedHashSet<>(legs)) {
            Double v = cached(l);
            if (v != null) { out.put(l, v); hits++; } else miss.add(l);
        }
        metrics.cacheHits.add(hits); metrics.cacheMisses.add(miss.size());
        double s = cfg.cellScale();
        int requests = 0, sent = 0;
        for (int i = 0; i < miss.size(); i += cfg.batchSize()) {
            List<Leg> chunk = miss.subList(i, Math.min(miss.size(), i + cfg.batchSize()));
            List<RoutingProvider.Query> q = new ArrayList<>(chunk.size());
            for (Leg l : chunk) q.add(new RoutingProvider.Query(l.aLat() / s, l.aLon() / s, l.bLat() / s, l.bLon() / s));
            boolean done = false;
            for (int attempt = 0; attempt < ATTEMPTS && !done; attempt++) {
                if (!breaker.allowRequest()) { metrics.shortCircuited.increment(); break; }
                if (attempt > 0) metrics.providerRetries.increment();
                requests++; sent += chunk.size();
                metrics.providerRequests.increment(); metrics.providerLegs.add(chunk.size());
                try {
                    double[] r = provider.drivingKm(q);
                    if (r == null || r.length != chunk.size()) throw new RoutingProvider.ProviderException("bad response");
                    for (int k = 0; k < r.length; k++) { out.put(chunk.get(k), r[k]); store(chunk.get(k), r[k]); }
                    breaker.onSuccess(); done = true;
                } catch (RoutingProvider.ProviderException | RuntimeException e) {
                    breaker.onFailure(); metrics.providerFailures.increment();
                }
            }
        }
        return new Resolution(out, requests, sent, hits);
    }
}
