package com.moveinsync.gps.core;

/** One explained stretch of the trip. No coordinates on purpose: anchors can be home locations. */
public record Segment(int idx, long fromTs, long toTs, Source from, Source to, Rule rule, long meters) {}
