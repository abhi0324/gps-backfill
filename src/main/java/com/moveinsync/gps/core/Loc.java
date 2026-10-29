package com.moveinsync.gps.core;

/** A coordinate. toString is redacted on purpose: locations may be home addresses (PII) and must never reach logs. */
public record Loc(double lat, double lon) {
    public boolean valid() { return Geo.valid(lat, lon); }
    @Override public String toString() { return "Loc[redacted]"; }
}
