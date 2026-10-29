package com.moveinsync.gps.core;

import java.util.List;

public record TripEvidence(String tenantId, String tripId, List<Ping> pings,
                           Loc start, Long startTs, List<Stop> stops,
                           Loc plannedOffice, Loc actualEnd, long endTs) {
    public TripEvidence {
        pings = pings == null ? List.of() : List.copyOf(pings);
        stops = stops == null ? List.of() : List.copyOf(stops);
    }
    public TripEvidence withPings(List<Ping> p) {
        return new TripEvidence(tenantId, tripId, p, start, startTs, stops, plannedOffice, actualEnd, endTs);
    }
}
