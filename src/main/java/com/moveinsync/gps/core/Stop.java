package com.moveinsync.gps.core;

/** One employee milestone on a trip, with every fallback source the spec allows. */
public record Stop(String employeeId, Kind kind, RiderType rider,
                   Loc actual, Long actualTs,
                   Loc home, Long signInTs,
                   Loc planned, Long plannedTs) {
    public enum Kind { PICKUP, DROP, NO_SHOW }
    public enum RiderType { ADHOC, PLANNED }
    @Override public String toString() { return "Stop[kind=" + kind + "]"; } // no ids, no locations
}
