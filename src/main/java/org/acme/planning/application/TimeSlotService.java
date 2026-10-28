package org.acme.planning.application;

import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TimeSlotService {
    private final PlanningRepository repo;
    private final CommonPlanningService common;

    public TimeSlotService(PlanningRepository repo, CommonPlanningService common) {
        this.repo = repo;
        this.common = common;
    }

    List<Map<String, Object>> conflicts(Plan p, Set<Long> excluded, List<Plan> finalPlans,
            List<Map<String, Object>> warnings) {
        return conflicts(p, excluded, finalPlans, warnings, new HashMap<>());
    }

    /** Every plan of one request against the others' final state, with one assignee lookup per member. */
    List<Map<String, Object>> conflicts(List<Plan> plans, Set<Long> excluded, List<Map<String, Object>> warnings) {
        var assignees = new HashMap<Integer, Assignee>();
        var out = new ArrayList<Map<String, Object>>();
        for (var p : plans) {
            out.addAll(conflicts(p, excluded, plans, warnings, assignees));
        }
        return out;
    }

    private List<Map<String, Object>> conflicts(Plan p, Set<Long> excluded, List<Plan> finalPlans,
            List<Map<String, Object>> warnings, Map<Integer, Assignee> assignees) {
        var out = new ArrayList<Map<String, Object>>();
        if (p.memberId() == null) {
            if (!p.slots().isEmpty()) {
                throw Problem.bad("Team plans cannot have slots");
            }
            return out;
        }
        var a = assignee(p, assignees);
        if (!a.enabled()) {
            for (var slot : p.slots()) {
                out.add(conflict(slot, slot.start(), slot.end(), "DISABLED_ASSIGNEE", null));
            }
            return out;
        }
        var shifts = Policy.union(repo.shifts(a, p.day()));
        var blocks = new ArrayList<>(repo.blocks(a.userId(), p.day(), false));
        blocks.addAll(repo.booked(a.userId(), p.day(), excluded));
        for (var q : finalPlans) {
            if (q.id() != p.id() && q.memberId() != null
                    && assignee(q, assignees).userId().equals(a.userId())) {
                for (var s : q.slots()) {
                    blocks.add(new Interval(s.id(), s.start(), s.end(), "BOOKED", q.workOrderId()));
                }
            }
        }
        var order = repo.order(p.workOrderId());
        var pending = repo.blocks(a.userId(), p.day(), true);
        var ids = new HashSet<Long>();
        for (int i = 0; i < p.slots().size(); i++) {
            var s = p.slots().get(i);
            if (s == null) {
                throw Problem.bad("Null slot");
            }
            if (s.id() != null && !ids.add(s.id())) {
                throw Problem.bad("Duplicate slot id");
            }
            Policy.grid(s.start(), s.end(), p.day(), common.zone());
            if (s.start().isBefore(order.dropoffAt()) || s.end().isAfter(order.pickupAt())) {
                out.add(conflict(s, s.start(), s.end(), "OUTSIDE_VEHICLE_WINDOW", null));
            }
            if (shifts.stream().noneMatch(x -> !s.start().isBefore(x.start()) && !s.end().isAfter(x.end()))) {
                out.add(conflict(s, s.start(), s.end(), "OUTSIDE_SHIFT", null));
            }
            for (var b : blocks) {
                if (Policy.overlap(s.start(), s.end(), b.start(), b.end())) {
                    out.add(conflict(s, s.start().isAfter(b.start()) ? s.start() : b.start(),
                            s.end().isBefore(b.end()) ? s.end() : b.end(), b.reason(), b.workOrderId()));
                }
            }
            for (int j = 0; j < i; j++) {
                var b = p.slots().get(j);
                if (Policy.overlap(s.start(), s.end(), b.start(), b.end())) {
                    out.add(conflict(s, b.start(), b.end(), "BOOKED", p.workOrderId()));
                }
            }
            for (var b : pending) {
                if (Policy.overlap(s.start(), s.end(), b.start(), b.end())) {
                    var w = new LinkedHashMap<String, Object>();
                    w.put("code", "SLOT_OVERLAPS_PENDING_ABSENCE");
                    w.put("slotId", s.id());
                    w.put("absenceId", b.id());
                    w.put("from", s.start().isAfter(b.start()) ? s.start() : b.start());
                    w.put("to", s.end().isBefore(b.end()) ? s.end() : b.end());
                    warnings.add(w);
                }
            }
        }
        return out;
    }

    private Assignee assignee(Plan p, Map<Integer, Assignee> assignees) {
        return assignees.computeIfAbsent(p.memberId(), m -> repo.assignee(p.teamId(), m));
    }

    private Map<String, Object> conflict(Slot s, Instant from, Instant to, String reason, Integer order) {
        var m = new LinkedHashMap<String, Object>();
        m.put("slotId", s.id());
        m.put("start", s.start());
        m.put("end", s.end());
        m.put("blockedFrom", from);
        m.put("blockedTo", to);
        m.put("reason", reason);
        m.put("workOrderId", order);
        return m;
    }

    List<Slot> requested(Plan p, List<Slot> slots) {
        if (slots == null || slots.size() > 100) {
            throw Problem.bad("slots required; at most 100");
        }
        var ids = new HashSet<Long>();
        p.slots().forEach(s -> ids.add(s.id()));
        for (var s : slots) {
            if (s == null || s.id() != null && !ids.contains(s.id())) {
                throw new Problem(404, "TIME_PLAN_NOT_FOUND", "Slot id does not belong to time plan");
            }
        }
        return List.copyOf(slots);
    }

    void validateRequestOverlaps(List<Plan> plans) {
        // A person can be a member of several teams, so slots are grouped by user, not by member.
        // One assignee lookup per member, however many plans and slots it has.
        var userOfMember = new HashMap<Integer, Integer>();
        var slotsByUser = new HashMap<Integer, List<Slot>>();
        for (var p : plans) {
            int user = userOfMember.computeIfAbsent(p.memberId(), m -> repo.assignee(p.teamId(), m).userId());
            for (var s : p.slots()) {
                Policy.grid(s.start(), s.end(), p.day(), common.zone());
                slotsByUser.computeIfAbsent(user, u -> new ArrayList<>()).add(s);
            }
        }
        // Sorted by start, two slots of a person overlap only if some neighbouring pair does.
        for (var slots : slotsByUser.values()) {
            slots.sort(Comparator.comparing(Slot::start));
            for (int i = 1; i < slots.size(); i++) {
                if (slots.get(i).start().isBefore(slots.get(i - 1).end())) {
                    throw new Problem(400, "SLOT_OVERLAP", "Request slots of the same person overlap");
                }
            }
        }
    }
}
