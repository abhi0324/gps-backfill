package com.moveinsync.gps.core;

/** Raw device fix. ts = epoch millis. */
public record Ping(double lat, double lon, long ts, double accuracyM) {}
