package com.moveinsync.gps.core;

/** Where a point came from. Ordinal is the tie-break when two points share a timestamp. */
public enum Source {
    TRIP_START, PING, EMPLOYEE_ACTUAL, EMPLOYEE_PROFILE, EMPLOYEE_PLANNED,
    NO_SHOW_ACTUAL, NO_SHOW_PLANNED, OFFICE_ACTUAL, OFFICE_PLANNED
}
