package com.moveinsync.gps.core;

import java.util.ArrayList;
import java.util.List;

/** Step II: turn trip evidence into anchor points using the defined fallback order. */
final class AnchorBuilder {
    private AnchorBuilder() {}

    private static boolean ok(Loc l, Long ts) { return l != null && l.valid() && ts != null && ts > 0; }

    static List<Pt> build(TripEvidence t, EngineConfig c) {
        List<Pt> a = new ArrayList<>();
        if (ok(t.start(), t.startTs())) a.add(new Pt(t.start().lat(), t.start().lon(), t.startTs(), Source.TRIP_START));

        for (Stop s : t.stops()) {
            switch (s.kind()) {
                case NO_SHOW -> {
                    if (!c.includeNoShow()) break; // behind the configuration flag
                    if (ok(s.actual(), s.actualTs())) add(a, s.actual(), s.actualTs(), Source.NO_SHOW_ACTUAL);
                    else if (ok(s.planned(), s.plannedTs())) add(a, s.planned(), s.plannedTs(), Source.NO_SHOW_PLANNED);
                }
                case PICKUP, DROP -> {
                    if (ok(s.actual(), s.actualTs())) add(a, s.actual(), s.actualTs(), Source.EMPLOYEE_ACTUAL);
                    else if (s.rider() == Stop.RiderType.ADHOC && ok(s.home(), s.signInTs()))
                        add(a, s.home(), s.signInTs(), Source.EMPLOYEE_PROFILE);
                    else if (s.rider() == Stop.RiderType.PLANNED && ok(s.planned(), s.plannedTs()))
                        add(a, s.planned(), s.plannedTs(), Source.EMPLOYEE_PLANNED);
                    // else: nothing valid -> no anchor (the gap stays a plain routed gap)
                }
            }
        }
        // Office: actual end only if within 400 m of the planned office, else planned office @ trip-end time.
        if (t.plannedOffice() != null && t.plannedOffice().valid() && t.endTs() > 0) {
            Loc end = t.actualEnd(), off = t.plannedOffice();
            boolean useActual = end != null && end.valid()
                    && Geo.meters(end.lat(), end.lon(), off.lat(), off.lon()) <= c.officeRadiusM();
            if (useActual) add(a, end, t.endTs(), Source.OFFICE_ACTUAL);
            else add(a, off, t.endTs(), Source.OFFICE_PLANNED);
        }
        return a;
    }

    private static void add(List<Pt> a, Loc l, long ts, Source s) { a.add(new Pt(l.lat(), l.lon(), ts, s)); }
}
