package com.moveinsync.gps.web;

import static com.moveinsync.gps.web.AuthProperties.Role.*;

import com.moveinsync.gps.core.*;
import com.moveinsync.gps.web.ApiExceptionHandler.ApiException;
import com.moveinsync.gps.web.AuthFilter.Principal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Thin controller: authz + input limits + mapping. All logic lives in the framework-free core. */
@RestController
@RequestMapping("/api/v1")
class TripController {
    record TripRequest(List<Ping> pings, Loc start, Long startTs, List<Stop> stops, Loc plannedOffice, Loc actualEnd, long endTs) {}
    record RuleStat(long segments, long meters) {}
    record Billing(Long billedMeters, String billedRuleVersion, int adjustments, long netAdjustmentMeters) {}
    record TripView(String tripId, String ruleVersion, String status, long totalMeters, String totalKm, TripResult.Stats stats,
                    Map<String, RuleStat> byRule, Billing billing, List<Segment> segments) {}
    record AdjustmentView(long billedBeforeMeters, long newMeters, long deltaMeters, String signedKm, String ruleVersion) {}

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_PINGS = 20_000;

    private final TripService svc; private final SimulatedRoutingProvider provider;
    TripController(TripService svc, SimulatedRoutingProvider provider) { this.svc = svc; this.provider = provider; }

    private static void require(Principal p, AuthProperties.Role... roles) {
        if (!Arrays.asList(roles).contains(p.role())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
    }
    private static String id(String s) {
        if (!ID.matcher(s).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "Invalid trip id");
        return s;
    }
    private static void limit(List<Ping> l) {
        if (l != null && l.size() > MAX_PINGS) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_MANY_PINGS", "Too many pings in one request");
    }

    @PutMapping("/trips/{tripId}")
    TripView compute(@RequestAttribute("principal") Principal p, @PathVariable String tripId, @RequestBody TripRequest r) {
        require(p, OPERATOR, ADMIN); limit(r.pings());
        TripResult res = svc.submit(new TripEvidence(p.tenant(), id(tripId), r.pings(), r.start(), r.startTs(),
                r.stops(), r.plannedOffice(), r.actualEnd(), r.endTs()));
        return view(res, svc.get(p.tenant(), tripId).orElseThrow(), "summary");
    }

    @PostMapping("/trips/{tripId}/pings")
    TripView latePings(@RequestAttribute("principal") Principal p, @PathVariable String tripId, @RequestBody List<Ping> pings) {
        require(p, OPERATOR, ADMIN); limit(pings);
        TripResult res = svc.addPings(p.tenant(), id(tripId), pings);
        return view(res, svc.get(p.tenant(), tripId).orElseThrow(), "summary");
    }

    /** detail=summary (default): non-trivial segments only. detail=full: every segment. */
    @GetMapping("/trips/{tripId}")
    TripView get(@RequestAttribute("principal") Principal p, @PathVariable String tripId, @RequestParam(defaultValue = "summary") String detail) {
        require(p, EMPLOYEE, OPERATOR, ADMIN);
        TripService.TripRecord rec = svc.get(p.tenant(), id(tripId)).orElseThrow();
        return view(rec.result(), rec, detail);
    }

    @GetMapping("/trips/{tripId}/adjustments")
    List<AdjustmentView> adjustments(@RequestAttribute("principal") Principal p, @PathVariable String tripId) {
        require(p, OPERATOR, ADMIN);
        return svc.get(p.tenant(), id(tripId)).orElseThrow().deltas().stream()
                .map(d -> new AdjustmentView(d.billedBeforeMeters(), d.newMeters(), d.deltaMeters(), d.signedKm(), d.ruleVersion())).toList();
    }

    @PostMapping("/trips/{tripId}/close-invoice")
    Map<String, Object> close(@RequestAttribute("principal") Principal p, @PathVariable String tripId) {
        require(p, ADMIN);
        long m = svc.closeInvoice(p.tenant(), id(tripId));
        return Map.of("billedMeters", m, "billedKm", String.format(Locale.ROOT, "%.3f", m / 1000.0));
    }

    @PostMapping("/admin/recompute-degraded")
    Map<String, Object> recompute(@RequestAttribute("principal") Principal p) {
        require(p, ADMIN);
        return Map.of("recovered", svc.recomputeDegraded(), "stillDegraded", svc.degradedQueueDepth());
    }

    /** Demo hook: simulate a routing-provider outage. */
    @PostMapping("/admin/provider-outage")
    Map<String, Object> outage(@RequestAttribute("principal") Principal p, @RequestParam boolean on) {
        require(p, ADMIN);
        provider.setOutage(on);
        return Map.of("outage", on);
    }

    private static TripView view(TripResult r, TripService.TripRecord rec, String detail) {
        Map<String, RuleStat> byRule = new LinkedHashMap<>();
        r.ruleSummary().forEach((k, v) -> byRule.put(k.name(), new RuleStat(v[0], v[1])));
        boolean full = "full".equals(detail);
        List<Segment> segs = full ? r.segments() : r.segments().stream().filter(s -> s.rule() != Rule.SHORT_GAP_AERIAL).toList();
        long net = rec.deltas().stream().mapToLong(TripService.Delta::deltaMeters).sum();
        return new TripView(r.tripId(), r.ruleVersion(), r.status().name(), r.totalMeters(), r.totalKmText(), r.stats(), byRule,
                new Billing(rec.billedMeters(), rec.billedRuleVersion(), rec.deltas().size(), net), segs);
    }
}
