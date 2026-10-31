package com.moveinsync.gps.web;

import com.moveinsync.gps.core.*;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class AppConfig {
    @Bean EngineMetrics engineMetrics() { return new EngineMetrics(); }
    @Bean EngineConfig engineConfig(@Value("${app.engine.include-no-show:false}") boolean noShow) {
        return EngineConfig.defaults().withNoShow(noShow);
    }
    @Bean SimulatedRoutingProvider routingProvider(@Value("${app.provider.latency-ms:0}") long ms) { return new SimulatedRoutingProvider(ms); }
    @Bean RoutingGateway gateway(SimulatedRoutingProvider p, EngineConfig cfg, EngineMetrics m) {
        return new RoutingGateway(p, cfg, new CircuitBreaker(3, 10_000, System::currentTimeMillis),
                500_000, 24L * 3600 * 1000, System::currentTimeMillis, m);
    }
    @Bean DistanceEngine engine(EngineConfig cfg, RoutingGateway gw, EngineMetrics m) { return new DistanceEngine(cfg, gw, m); }
    @Bean TripService tripService(DistanceEngine e, EngineMetrics m) { return new TripService(e, m); }

    /** The 3 AM dashboard: queue depth, circuit state, rejections by rule, provider errors/retries, cache efficiency. */
    @Bean MeterBinder gpsMeters(EngineMetrics m, TripService svc, RoutingGateway gw) {
        return reg -> {
            Gauge.builder("gps.degraded.queue.depth", svc, TripService::degradedQueueDepth).register(reg);
            Gauge.builder("gps.provider.circuit.state", svc, s -> s.circuitState().ordinal()).description("0=CLOSED 1=OPEN 2=HALF_OPEN").register(reg);
            Gauge.builder("gps.cache.size", gw, RoutingGateway::cacheSize).register(reg);
            counter(reg, "gps.trips.computed", m.tripsComputed); counter(reg, "gps.trips.degraded", m.tripsDegraded);
            counter(reg, "gps.recomputes", m.recomputes); counter(reg, "gps.deltas.issued", m.deltasIssued);
            counter(reg, "gps.provider.requests", m.providerRequests); counter(reg, "gps.provider.legs", m.providerLegs);
            counter(reg, "gps.provider.failures", m.providerFailures); counter(reg, "gps.provider.retries", m.providerRetries);
            counter(reg, "gps.provider.short_circuited", m.shortCircuited);
            counter(reg, "gps.cache.hits", m.cacheHits); counter(reg, "gps.cache.misses", m.cacheMisses);
            counter(reg, "gps.pings.invalid", m.pingsInvalid); counter(reg, "gps.pings.low_accuracy", m.pingsLowAccuracy);
            counter(reg, "gps.pings.duplicate", m.pingsDuplicate); counter(reg, "gps.pings.teleport", m.pingsTeleport);
            m.segmentsByRule.forEach((rule, adder) ->
                FunctionCounter.builder("gps.segments", adder, LongAdder::doubleValue).tag("rule", rule.name()).register(reg));
        };
    }
    private static void counter(io.micrometer.core.instrument.MeterRegistry reg, String name, LongAdder a) {
        FunctionCounter.builder(name, a, LongAdder::doubleValue).register(reg);
    }
}

/** Automatic recompute of DEGRADED trips once the provider is healthy again. */
@Component
class RecomputeJob {
    private final TripService svc;
    RecomputeJob(TripService svc) { this.svc = svc; }
    @Scheduled(fixedDelayString = "${app.recompute-interval-ms:5000}")
    void run() { svc.recomputeDegraded(); }
}
