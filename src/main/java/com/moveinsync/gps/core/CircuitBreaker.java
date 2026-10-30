package com.moveinsync.gps.core;

import java.util.function.LongSupplier;

/** CLOSED -> (N consecutive failures) -> OPEN -> (cooldown) -> HALF_OPEN (one probe) -> CLOSED | OPEN. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }
    private final int threshold; private final long cooldownMs; private final LongSupplier clock;
    private State state = State.CLOSED; private int failures; private long openedAt; private boolean probeOut;

    public CircuitBreaker(int threshold, long cooldownMs, LongSupplier clock) {
        this.threshold = threshold; this.cooldownMs = cooldownMs; this.clock = clock;
    }
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) return true;
        if (state == State.OPEN && clock.getAsLong() - openedAt >= cooldownMs) { state = State.HALF_OPEN; probeOut = false; }
        if (state == State.HALF_OPEN && !probeOut) { probeOut = true; return true; }
        return false;
    }
    public synchronized void onSuccess() { state = State.CLOSED; failures = 0; probeOut = false; }
    public synchronized void onFailure() {
        failures++;
        if (state == State.HALF_OPEN || failures >= threshold) { state = State.OPEN; openedAt = clock.getAsLong(); probeOut = false; }
    }
    public synchronized State state() { return state; }
}
