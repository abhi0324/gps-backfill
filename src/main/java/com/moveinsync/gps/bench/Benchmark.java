package com.moveinsync.gps.bench;

import com.moveinsync.gps.core.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 10,000 synthetic trips x ~1,500 pings. Trips follow one of 200 corridors (as real commutes do): a 22-minute
 * tunnel/basement hole with two employee drops inside, 5% duplicates, shuffled order, ~1% 9 km bad fixes.
 * Run: java -Xmx256m -cp target/classes com.moveinsync.gps.bench.Benchmark [trips] [threads]
 */
public final class Benchmark {
    static final long T0 = 1_700_000_000_000L;

    static TripEvidence synth(int tripNo, int corridors) {
        int cor = tripNo % corridors;
        Random corr = new Random(cor), rnd = new Random(tripNo * 31L + 7);
        double lat = 12.80 + corr.nextDouble() * 0.3, lon = 77.4 + corr.nextDouble() * 0.3;
        double dLat = 0.00005 + corr.nextDouble() * 0.00002, dLon = 0.00004; // ~7-8 m/s
        int holeStart = 600 + corr.nextInt(200), holeLen = 330; // 330 pings * 4 s = 22 min
        List<Ping> pings = new ArrayList<>(1700);
        for (int i = 0; i < 1500; i++) {
            long ts = T0 + i * 4000L;
            boolean inHole = i >= holeStart && i < holeStart + holeLen;
            double j = (rnd.nextDouble() - .5) * 2e-5; // ~+-1 m jitter
            if (!inHole) {
                pings.add(new Ping(lat + i * dLat + j, lon + i * dLon + j, ts, 5 + rnd.nextInt(20)));
                if (rnd.nextInt(20) == 0) pings.add(pings.get(pings.size() - 1)); // duplicate
                if (rnd.nextInt(100) == 0) pings.add(new Ping(lat + i * dLat + 0.081, lon + i * dLon, ts, 8)); // bad fix
            }
        }
        Collections.shuffle(pings, rnd);
        int d1 = holeStart + 110, d2 = holeStart + 220;
        Loc drop1 = new Loc(lat + d1 * dLat, lon + d1 * dLon), drop2 = new Loc(lat + d2 * dLat, lon + d2 * dLon);
        List<Stop> stops = List.of(
            new Stop("e1", Stop.Kind.DROP, Stop.RiderType.PLANNED, drop1, T0 + d1 * 4000L, null, null, null, null),
            new Stop("e2", Stop.Kind.DROP, Stop.RiderType.ADHOC, null, null, drop2, T0 + d2 * 4000L, null, null));
        Loc office = new Loc(lat + 1499 * dLat, lon + 1499 * dLon);
        return new TripEvidence("bench", "t" + tripNo, pings, null, null, stops, office, office, T0 + 1499 * 4000L);
    }

    /** Stand-in for the paid API; counts nothing itself, the gateway metrics do. */
    static final class SimProvider implements RoutingProvider {
        public double[] drivingKm(List<Query> b) {
            double[] r = new double[b.size()];
            for (int i = 0; i < r.length; i++) { Query q = b.get(i); r[i] = Geo.meters(q.fromLat(), q.fromLon(), q.toLat(), q.toLon()) * 1.25 / 1000; }
            return r;
        }
    }

    public static void main(String[] a) throws Exception {
        int trips = a.length > 0 ? Integer.parseInt(a[0]) : 10_000, threads = a.length > 1 ? Integer.parseInt(a[1]) : Runtime.getRuntime().availableProcessors();
        EngineConfig cfg = EngineConfig.defaults(); EngineMetrics m = new EngineMetrics();
        RoutingGateway gw = new RoutingGateway(new SimProvider(), cfg, new CircuitBreaker(5, 5000, System::currentTimeMillis), 200_000, 86_400_000L, System::currentTimeMillis, m);
        DistanceEngine engine = new DistanceEngine(cfg, gw, m);
        for (int i = 0; i < 200; i++) engine.compute(synth(100_000 + i, 200)); // JIT warm-up (own cache entries are cleared below)
        gw = new RoutingGateway(new SimProvider(), cfg, new CircuitBreaker(5, 5000, System::currentTimeMillis), 200_000, 86_400_000L, System::currentTimeMillis, m = new EngineMetrics());
        engine = new DistanceEngine(cfg, gw, m);

        long[] lat = new long[trips]; AtomicLong naive = new AtomicLong(), legs = new AtomicLong(), reqs = new AtomicLong(), hits = new AtomicLong(),
                pings = new AtomicLong(), meters = new AtomicLong(), degraded = new AtomicLong();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime(); List<Future<?>> fs = new ArrayList<>(); final DistanceEngine eng = engine;
        for (int i = 0; i < trips; i++) { final int n = i; fs.add(ex.submit(() -> {
            TripEvidence t = synth(n, 200); long s = System.nanoTime();
            TripResult r = eng.compute(t); lat[n] = System.nanoTime() - s;
            naive.addAndGet(r.stats().routableSegments()); legs.addAndGet(r.stats().providerLegs()); reqs.addAndGet(r.stats().providerRequests());
            hits.addAndGet(r.stats().cacheHits()); pings.addAndGet(r.stats().pingsIn()); meters.addAndGet(r.totalMeters());
            if (r.status() != TripResult.Status.OK) degraded.incrementAndGet();
        })); }
        for (Future<?> f : fs) f.get(); ex.shutdown();
        double secs = (System.nanoTime() - t0) / 1e9;
        Arrays.sort(lat);
        long peak = 0; for (var p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getType() == java.lang.management.MemoryType.HEAP) peak += p.getPeakUsage().getUsed();
        System.out.printf("machine: %d cores, %s, JVM %s, maxHeap=%d MB%n", Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"), System.getProperty("java.version"), Runtime.getRuntime().maxMemory() >> 20);
        System.out.printf("trips: %d (%d pings in), threads=%d%n", trips, pings.get(), threads);
        System.out.printf("wall time: %.2f s throughput: %.0f trips/s, %.2f M pings/s%n", secs, trips / secs, pings.get() / secs / 1e6);
        System.out.printf("latency / trip: p50=%.2f ms p99=%.2f ms max=%.2f ms%n", lat[trips / 2] / 1e6, lat[(int) (trips * 0.99)] / 1e6, lat[trips - 1] / 1e6);
        System.out.printf("peak heap used: ~%d MB (sum of heap-pool peaks, upper bound)%n", peak >> 20);
        System.out.printf("provider calls: BEFORE caching (1 per real gap) = %d | AFTER cache+dedupe+batching = %d legs in %d HTTP requests (cache hits %d)%n", naive.get(), legs.get(), reqs.get(), hits.get());
        System.out.printf("reduction: legs -%.1f%%, requests -%.1f%% degraded trips: %d%n", 100.0 * (1 - legs.get() / (double) naive.get()), 100.0 * (1 - reqs.get() / (double) naive.get()), degraded.get());
        System.out.printf("avg trip km: %.2f%n", meters.get() / 1000.0 / trips);
    }
}
