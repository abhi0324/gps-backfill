package com.moveinsync.gps.core;

/** Every tunable the spec names. ruleVersion is stamped on each result; change any rule => bump it. */
public record EngineConfig(String ruleVersion, double speedLimitKmh, double maxAccuracyM, long shortGapSec,
                           double parkedKmh, double officeRadiusM, double maxRouteKm, double detourFactor,
                           boolean includeNoShow, int cellDecimals, int batchSize) {
    public static EngineConfig defaults() {
        return new EngineConfig("rules-v1", 120, 100, 70, 5, 400, 100, 1.4, false, 4, 25);
    }
    public EngineConfig withNoShow(boolean b) {
        return new EngineConfig(ruleVersion, speedLimitKmh, maxAccuracyM, shortGapSec, parkedKmh, officeRadiusM,
                maxRouteKm, detourFactor, b, cellDecimals, batchSize);
    }
    public EngineConfig withBatchSize(int n) {
        return new EngineConfig(ruleVersion, speedLimitKmh, maxAccuracyM, shortGapSec, parkedKmh, officeRadiusM,
                maxRouteKm, detourFactor, includeNoShow, cellDecimals, n);
    }
    double cellScale() { return StrictMath.pow(10, cellDecimals); }
}
