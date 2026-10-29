package com.moveinsync.gps.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Framework-free counters; the Spring layer binds them to Micrometer. */
public final class EngineMetrics {
    public final LongAdder tripsComputed = new LongAdder(), tripsDegraded = new LongAdder(),
            recomputes = new LongAdder(), deltasIssued = new LongAdder(),
            pingsInvalid = new LongAdder(), pingsLowAccuracy = new LongAdder(),
            pingsDuplicate = new LongAdder(), pingsTeleport = new LongAdder(),
            providerRequests = new LongAdder(), providerLegs = new LongAdder(), providerFailures = new LongAdder(),
            providerRetries = new LongAdder(), shortCircuited = new LongAdder(),
            cacheHits = new LongAdder(), cacheMisses = new LongAdder();
    public final Map<Rule, LongAdder> segmentsByRule = new EnumMap<>(Rule.class);

    public EngineMetrics() { for (Rule r : Rule.values()) segmentsByRule.put(r, new LongAdder()); }
}
