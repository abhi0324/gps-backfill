package com.moveinsync.gps.web;

import com.moveinsync.gps.core.Geo;
import com.moveinsync.gps.core.RoutingProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stand-in for the paid API (no credentials in this repo): 1.25 x aerial. Outage can be toggled live for the demo. */
public final class SimulatedRoutingProvider implements RoutingProvider {
    private final AtomicBoolean outage = new AtomicBoolean(false);
    private final long latencyMs;
    public SimulatedRoutingProvider(long latencyMs) { this.latencyMs = latencyMs; }
    public void setOutage(boolean on) { outage.set(on); }

    @Override public double[] drivingKm(List<Query> batch) throws ProviderException {
        if (latencyMs > 0) try { Thread.sleep(latencyMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (outage.get()) throw new ProviderException("provider unavailable");
        double[] r = new double[batch.size()];
        for (int i = 0; i < r.length; i++) {
            Query q = batch.get(i);
            r[i] = Geo.meters(q.fromLat(), q.fromLon(), q.toLat(), q.toLon()) * 1.25 / 1000.0;
        }
        return r;
    }
}
