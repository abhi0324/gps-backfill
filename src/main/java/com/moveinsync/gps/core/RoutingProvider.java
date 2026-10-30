package com.moveinsync.gps.core;

import java.util.List;

/** Port to the paid routing API. Implementations must return driving km per query, same order. */
public interface RoutingProvider {
    record Query(double fromLat, double fromLon, double toLat, double toLon) {}
    double[] drivingKm(List<Query> batch) throws ProviderException;

    class ProviderException extends Exception {
        public ProviderException(String m) { super(m); }
    }
}
