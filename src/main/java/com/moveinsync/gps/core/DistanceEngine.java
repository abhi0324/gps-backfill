package com.moveinsync.gps.core;

import java.util.*;

/**
 * Pure, deterministic trip-distance reconstruction. Rule order per consecutive pair (first match wins):
 *  1. dt < 70 s         : plausible speed -> SHORT_GAP_AERIAL, else REJECTED_SPEED
 *  2. aerial speed < 5  : PARKED (aerial)
 *  3. aerial speed > lim: REJECTED_SPEED  (no provider call: a route is never shorter than the aerial line)
 *  4. otherwise         : ROUTED, then validated (>100 km => REJECTED_DISTANCE; speed rule => REJECTED_SPEED)
 *  5. provider unavailable: FALLBACK_AERIAL_X1_4 and the trip is DEGRADED.
 * Rejected segments contribute 0 m and are flagged for review instead of being silently invented.
 */
public final class DistanceEngine {
    private final EngineConfig cfg; private final RoutingGateway gateway; private final EngineMetrics metrics;

    public DistanceEngine(EngineConfig cfg, RoutingGateway gateway, EngineMetrics metrics) {
        this.cfg = cfg; this.gateway = gateway; this.metrics = metrics;
    }
    public EngineConfig config() { return cfg; }
    public CircuitBreaker.State gatewayBreakerState() { return gateway.breaker().state(); }

    private static double kmh(double meters, double sec) {
        return sec <= 0 ? (meters > 0 ? Double.POSITIVE_INFINITY : 0) : meters / sec * 3.6;
    }

    public TripResult compute(TripEvidence t) {
        PingCleaner.Cleaned cl = PingCleaner.clean(t.pings(), cfg);
        List<Pt> anchors = AnchorBuilder.build(t, cfg);
        List<Pt> pts = new ArrayList<>(cl.pings().size() + anchors.size());
        for (Ping p : cl.pings()) pts.add(new Pt(p.lat(), p.lon(), p.ts(), Source.PING));
        pts.addAll(anchors);
        pts.sort(Pt.ORDER);

        int n = Math.max(0, pts.size() - 1);
        Rule[] rule = new Rule[n]; long[] metres = new long[n]; double[] aerial = new double[n];
        double[] dtSec = new double[n]; RoutingGateway.Leg[] leg = new RoutingGateway.Leg[n];
        List<RoutingGateway.Leg> pending = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            Pt a = pts.get(i), b = pts.get(i + 1);
            double d = Geo.meters(a.lat(), a.lon(), b.lat(), b.lon());
            double dt = (b.ts() - a.ts()) / 1000.0, v = kmh(d, dt);
            aerial[i] = d; dtSec[i] = dt;
            if (dt < cfg.shortGapSec()) {
                if (v <= cfg.speedLimitKmh()) { rule[i] = Rule.SHORT_GAP_AERIAL; metres[i] = Math.round(d); }
                else rule[i] = Rule.REJECTED_SPEED;
            } else if (v < cfg.parkedKmh()) { rule[i] = Rule.PARKED; metres[i] = Math.round(d); }
            else if (v > cfg.speedLimitKmh()) rule[i] = Rule.REJECTED_SPEED;
            else { leg[i] = gateway.legOf(a, b); pending.add(leg[i]); }
        }

        RoutingGateway.Resolution res = pending.isEmpty()
                ? new RoutingGateway.Resolution(Map.of(), 0, 0, 0) : gateway.resolve(pending);
        int fallbacks = 0;
        for (int i = 0; i < n; i++) {
            if (leg[i] == null) continue;
            Double km = res.km().get(leg[i]);
            if (km == null) { rule[i] = Rule.FALLBACK_AERIAL_X1_4; metres[i] = Math.round(aerial[i] * cfg.detourFactor()); fallbacks++; continue; }
            double hours = dtSec[i] / 3600.0;
            if (km > cfg.maxRouteKm()) rule[i] = Rule.REJECTED_DISTANCE;
            else if (km / hours > cfg.speedLimitKmh() && (aerial[i] / 1000.0) * cfg.detourFactor() / hours > cfg.speedLimitKmh())
                rule[i] = Rule.REJECTED_SPEED;
            else { rule[i] = Rule.ROUTED; metres[i] = Math.round(km * 1000.0); }
        }

        List<Segment> segs = new ArrayList<>(n);
        long total = 0;
        for (int i = 0; i < n; i++) {
            segs.add(new Segment(i, pts.get(i).ts(), pts.get(i + 1).ts(), pts.get(i).src(), pts.get(i + 1).src(), rule[i], metres[i]));
            total += metres[i];
            metrics.segmentsByRule.get(rule[i]).increment();
        }
        TripResult.Status status = fallbacks > 0 ? TripResult.Status.DEGRADED : TripResult.Status.OK;
        metrics.tripsComputed.increment();
        if (status == TripResult.Status.DEGRADED) metrics.tripsDegraded.increment();
        metrics.pingsInvalid.add(cl.invalid()); metrics.pingsLowAccuracy.add(cl.lowAccuracy());
        metrics.pingsDuplicate.add(cl.duplicates()); metrics.pingsTeleport.add(cl.teleports());
        return new TripResult(t.tripId(), cfg.ruleVersion(), status, segs, total,
                new TripResult.Stats(t.pings().size(), cl.invalid(), cl.lowAccuracy(), cl.duplicates(), cl.teleports(),
                        anchors.size(), pending.size(), res.requests(), res.legsSent(), res.hits(), fallbacks));
    }
}
