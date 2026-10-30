package com.moveinsync.gps.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Orchestration, tenant isolation, billing-freeze and recovery. Per-trip locks make concurrent late-ping
 * arrivals serialise; different trips never block each other. Storage is in-memory behind this class
 * (swap for a repository/DB; see README trade-offs).
 */
public final class TripService {
    /** Signed adjustment to an already-closed invoice. */
    public record Delta(long billedBeforeMeters, long newMeters, long deltaMeters, String ruleVersion) {
        public String signedKm() { return String.format(Locale.ROOT, "%+.3f km", deltaMeters / 1000.0); }
    }

    public static final class TripRecord {
        private TripEvidence evidence; private TripResult result;
        private Long billedMeters; private String billedRuleVersion;
        private final List<Delta> deltas = new CopyOnWriteArrayList<>();
        TripRecord(TripEvidence e, TripResult r) { evidence = e; result = r; }
        public TripEvidence evidence() { return evidence; }
        public TripResult result() { return result; }
        public Long billedMeters() { return billedMeters; }
        public String billedRuleVersion() { return billedRuleVersion; }
        public List<Delta> deltas() { return List.copyOf(deltas); }

        Optional<Delta> accept(TripEvidence e, TripResult r) {
            evidence = e; result = r;
            if (billedMeters == null || r.status() != TripResult.Status.OK) return Optional.empty(); // never bill off a DEGRADED estimate
            long effective = billedMeters + deltas.stream().mapToLong(Delta::deltaMeters).sum();
            long d = r.totalMeters() - effective;
            if (d == 0) return Optional.empty(); // idempotent
            Delta x = new Delta(effective, r.totalMeters(), d, r.ruleVersion());
            deltas.add(x);
            return Optional.of(x);
        }
    }

    private final DistanceEngine engine; private final EngineMetrics metrics;
    private final ConcurrentHashMap<String, TripRecord> trips = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> degraded = ConcurrentHashMap.newKeySet();

    public TripService(DistanceEngine engine, EngineMetrics metrics) { this.engine = engine; this.metrics = metrics; }

    private static String key(String tenant, String trip) { return tenant + '\u0000' + trip; }
    private Object lock(String k) { return locks.computeIfAbsent(k, x -> new Object()); }

    public Optional<TripRecord> get(String tenant, String tripId) { return Optional.ofNullable(trips.get(key(tenant, tripId))); }
    public int degradedQueueDepth() { return degraded.size(); }
    public CircuitBreaker.State circuitState() { return engine.gatewayBreakerState(); }

    public TripResult submit(TripEvidence e) { return mutate(e.tenantId(), e.tripId(), cur -> e); }

    /** Late / duplicate / out-of-order pings: merged as a set, recomputed from scratch, so replays are harmless. */
    public TripResult addPings(String tenant, String tripId, List<Ping> extra) {
        return mutate(tenant, tripId, cur -> {
            if (cur == null) throw new NoSuchElementException("trip");
            Set<Ping> merged = new LinkedHashSet<>(cur.pings());
            merged.addAll(extra);
            return cur.withPings(new ArrayList<>(merged));
        });
    }

    private TripResult mutate(String tenant, String tripId, UnaryOperator<TripEvidence> change) {
        String k = key(tenant, tripId);
        synchronized (lock(k)) {
            TripRecord r = trips.get(k);
            TripEvidence next = change.apply(r == null ? null : r.evidence());
            TripResult res = engine.compute(next);
            if (r == null) trips.put(k, new TripRecord(next, res));
            else if (r.accept(next, res).isPresent()) metrics.deltasIssued.increment();
            if (res.status() == TripResult.Status.DEGRADED) degraded.add(k); else degraded.remove(k);
            return res;
        }
    }

    /** Freeze the invoice at the current kilometres. Refused while DEGRADED. Idempotent. */
    public long closeInvoice(String tenant, String tripId) {
        String k = key(tenant, tripId);
        synchronized (lock(k)) {
            TripRecord r = trips.get(k);
            if (r == null) throw new NoSuchElementException("trip");
            if (r.billedMeters != null) return r.billedMeters;
            if (r.result.status() != TripResult.Status.OK) throw new IllegalStateException("trip is degraded");
            r.billedMeters = r.result.totalMeters(); r.billedRuleVersion = r.result.ruleVersion();
            return r.billedMeters;
        }
    }

    /** Called by the scheduler. Returns how many trips recovered. */
    public int recomputeDegraded() {
        int recovered = 0;
        for (String k : new ArrayList<>(degraded)) {
            synchronized (lock(k)) {
                TripRecord r = trips.get(k);
                if (r == null) { degraded.remove(k); continue; }
                TripResult res = engine.compute(r.evidence());
                metrics.recomputes.increment();
                if (r.accept(r.evidence(), res).isPresent()) metrics.deltasIssued.increment();
                if (res.status() == TripResult.Status.OK) { degraded.remove(k); recovered++; }
            }
        }
        return recovered;
    }
}
