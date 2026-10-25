package org.acme.planning.application;

import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TimePlanMoveService {
    private final PlanningRepository repo;
    private final PlanningLockRepository locks;
    private final CommonPlanningService common;
    private final TimeSlotService slots;
    private final TimePlanLogService logs;

    public TimePlanMoveService(PlanningRepository repo, PlanningLockRepository locks, CommonPlanningService common,
            TimeSlotService slots, TimePlanLogService logs) {
        this.repo = repo;
        this.locks = locks;
        this.common = common;
        this.slots = slots;
        this.logs = logs;
    }

    private record Evaluation(Plan source, Plan target, Plan sourceAfter, Plan targetAfter, Assignee assignee,
            int moved, boolean full, List<Map<String, Object>> conflicts, List<Map<String, Object>> warnings) {
    }

    private Evaluation evaluate(long id, Move m, Long slotId) {
        if (m == null) {
            throw Problem.bad("Body required");
        }
        var p = repo.find(id);
        var a = common.valid(p.workOrderId(), m.targetTeamId(), m.targetMemberId());
        if (p.teamId() == a.teamId() && Objects.equals(p.memberId(), a.memberId())) {
            throw Problem.bad("Target must differ from source");
        }
        if (m.slotHandling() != null && !Set.of("KEEP", "DROP").contains(m.slotHandling())) {
            throw Problem.bad("slotHandling must be KEEP or DROP");
        }
        if ("DROP".equals(m.slotHandling()) && (p.memberId() == null || a.memberId() == null)) {
            throw Problem.bad("slotHandling DROP only for member to member");
        }
        var target = repo.target(p.workOrderId(), a.teamId(), a.memberId(), p.day());
        int n = m.duration() == null ? p.plannedMinutes() : Policy.minutes(m.duration(), false);
        var movedSlots = new ArrayList<Slot>();
        boolean full = n == p.plannedMinutes();
        var remaining = new ArrayList<>(p.slots());
        if (slotId != null) {
            var s = p.slots().stream().filter(x -> x.id().equals(slotId)).findFirst()
                    .orElseThrow(() -> new Problem(404, "TIME_PLAN_NOT_FOUND", "Slot does not belong to source"));
            n = Math.min(s.minutes(), p.plannedMinutes());
            full = n == p.plannedMinutes();
            remaining.remove(s);
            if (a.memberId() != null) {
                if (m.start() == null || m.end() == null) {
                    throw Problem.bad("Target start and end required");
                }
                if (Duration.between(m.start(), m.end()).toMinutes() != s.minutes()
                        || !Duration.between(m.start(), m.end()).equals(Duration.ofMinutes(s.minutes()))) {
                    throw Problem.bad("Slot move must preserve duration");
                }
                movedSlots.add(new Slot(s.id(), m.start(), m.end()));
            }
        } else {
            if (n > p.plannedMinutes()) {
                throw Problem.bad("Cannot move more than source duration");
            }
            if (!full && p.memberId() != null && (a.memberId() != null || !p.slots().isEmpty())) {
                throw Problem.bad("Partial member move allowed only to team without slots");
            }
            if (m.slots() != null) {
                if (m.slots().size() > 100) {
                    throw Problem.bad("At most 100 slots");
                }
                if (p.memberId() != null || a.memberId() == null) {
                    throw Problem.bad("Optional slots apply only team to member");
                }
                for (var s : m.slots()) {
                    if (s == null || s.id() != null) {
                        throw Problem.bad("New move slots cannot carry ids");
                    }
                }
                movedSlots.addAll(m.slots());
            } else if (a.memberId() != null && p.memberId() != null && !"DROP".equals(m.slotHandling())) {
                movedSlots.addAll(p.slots());
            }
            if (full) {
                remaining.clear();
            }
        }
        boolean reuse = full && target == null && (slotId == null
                || a.memberId() != null && p.slots().size() == 1 && p.plannedMinutes() <= p.scheduled());
        int total = Math.addExact(n, target == null ? 0 : target.plannedMinutes());
        var all = new ArrayList<Slot>();
        if (target != null) {
            all.addAll(target.slots());
        }
        all.addAll(movedSlots);
        Plan to = target == null
                ? new Plan(reuse ? p.id() : 0, p.workOrderId(), a.teamId(), a.memberId(), p.day(), total,
                        reuse ? p.version() : 0, all)
                : target.change(a.teamId(), a.memberId(), total, all);
        Plan from = full ? null : p.change(p.teamId(), p.memberId(), p.plannedMinutes() - n, remaining);
        if (from != null) {
            common.limit(from);
        }
        var warnings = new ArrayList<Map<String, Object>>();
        // Existing target slots can already have external conflicts. Only incoming
        // slots
        // need to fit now; existing bookings must still block those incoming slots.
        var incoming = to.change(to.teamId(), to.memberId(), to.plannedMinutes(), movedSlots);
        if (incoming.memberId() != null) {
            slots.validateRequestOverlaps(List.of(incoming));
        }
        var excluded = Set.of(p.id());
        var errors = slots.conflicts(incoming, excluded, from == null ? List.of(incoming) : List.of(from, incoming),
                warnings);
        return new Evaluation(p, target, from, to, a, n, full, errors, warnings);
    }

    @Transactional
    public Map<String, Object> move(long id, Move m, Long slotId) {
        if (m == null) {
            throw Problem.bad("Body required");
        }
        var source = repo.find(id);
        var assignee = common.valid(source.workOrderId(), m.targetTeamId(), m.targetMemberId());
        // One sorted pass over the source and target team-days and person-days.
        locks.lockAssignees(List.of(source, new Plan(0, source.workOrderId(), assignee.teamId(), assignee.memberId(),
                source.day(), 0, 0, List.of())));
        var existing = repo.target(source.workOrderId(), assignee.teamId(), assignee.memberId(), source.day());
        locks.lockPlans(existing == null ? List.of(source.id()) : List.of(source.id(), existing.id()));
        // Re-read under lock: a stale client gets 409 before any rule is evaluated on
        // the newer state.
        common.version(repo.find(id), m.expectedVersion());
        var e = evaluate(id, m, slotId);
        if (!e.conflicts().isEmpty()) {
            boolean window = e.conflicts().stream().anyMatch(c -> "OUTSIDE_VEHICLE_WINDOW".equals(c.get("reason")));
            var details = new LinkedHashMap<String, Object>();
            details.put("conflicts", e.conflicts());
            // Capacity is the same for KEEP and DROP: the dialog asks once and the retry
            // carries allowOverbooking.
            var shortfall = common.capacityShortfall(e.source(), e.assignee(), e.source().day(), e.moved());
            if (shortfall != null)
                details.put("capacity", shortfall);
            throw window ? new Problem(409, "OUTSIDE_VEHICLE_WINDOW", "Slot outside vehicle window", details)
                    : new Problem(409, "SLOT_NOT_FREE", "Target slots conflict", details);
        }
        common.limit(e.targetAfter());
        common.capacity(e.source(), e.assignee(), e.source().day(), e.moved(),
                Boolean.TRUE.equals(m.allowOverbooking()));
        var sync = new ArrayList<Map<String, Object>>();
        var saved = new ArrayList<Plan>();
        var correlation = UUID.randomUUID();
        String op = slotId == null ? "MOVE_PLAN" : "MOVE_SLOT";
        int auditMinutes = slotId == null ? e.moved()
                : e.source().slots().stream().filter(s -> s.id().equals(slotId)).findFirst().orElseThrow().minutes();
        // Reassign moved slots before deleting a merged source. save(sourceAfter) would
        // delete transferred ids first.
        Plan to = repo.save(e.targetAfter());
        if (e.sourceAfter() != null) {
            Plan from = repo.save(e.sourceAfter());
            saved.add(from);
            logs.logMove(op, e.source(), from, to.id(), correlation, auditMinutes, e.source(), to);
        } else if (to.id() != e.source().id()) {
            repo.delete(new Plan(e.source().id(), e.source().workOrderId(), e.source().teamId(), e.source().memberId(),
                    e.source().day(), e.source().plannedMinutes(), e.source().version(), List.of()));
            logs.logMove(op, e.source(), null, to.id(), correlation, auditMinutes, e.source(), to);
        }
        saved.add(to);
        if (e.target() != null) {
            logs.logMove(op, e.target(), to, e.source().id(), correlation, auditMinutes, e.source(), to);
        } else
            logs.logMove(op, e.full() ? e.source() : null, to, e.full() ? null : e.source().id(), correlation,
                    auditMinutes, e.source(), to);
        if (slotId == null && m.slots() != null && !m.slots().isEmpty()) {
            // Before = the target after the move, without the new slots.
            var unscheduled = to.change(to.teamId(), to.memberId(), to.plannedMinutes(),
                    e.target() == null ? List.of() : e.target().slots());
            logs.log("SCHEDULE", unscheduled, to, null, correlation, to.scheduled());
        }
        var oldStates = new ArrayList<Plan>(List.of(e.source()));
        if (e.target() != null) {
            oldStates.add(e.target());
        }
        common.syncStates(oldStates, saved, sync);
        return common.result(saved, sync);
    }

    public Map<String, Object> preview(long id, Move m) {
        var e = evaluate(id, m, null);
        var p = e.source();
        var c = repo.capacity(e.assignee().teamId(), e.assignee().memberId(), p.day());
        boolean gains = e.assignee().memberId() != null || e.assignee().teamId() != p.teamId();
        boolean fits = true;
        if (e.assignee().teamId() != p.teamId()) {
            fits = repo.capacity(e.assignee().teamId(), null, p.day()).availableMinutes() >= e.moved();
        }
        if (e.assignee().memberId() != null && !Objects.equals(p.memberId(), e.assignee().memberId())) {
            fits = fits && repo.capacity(e.assignee().teamId(), e.assignee().memberId(), p.day())
                    .availableMinutes() >= e.moved();
        }
        var merge = e.target() == null ? null
                : Map.of("timePlanId", e.target().id(), "plannedDuration",
                        Duration.ofMinutes(e.target().plannedMinutes()).toString(), "plannedAfterDuration",
                        Duration.ofMinutes(e.targetAfter().plannedMinutes()).toString());
        var slots = new LinkedHashMap<String, Object>();
        int slotCount = m.slots() == null ? p.slots().size() : m.slots().size();
        String outcome = slotCount == 0 ? "NONE"
                : e.assignee().memberId() == null ? "REMOVED" : e.conflicts().isEmpty() ? "KEEP" : "CONFLICT";
        slots.put("outcome", outcome);
        slots.put("count", p.slots().size());
        slots.put("conflicts", e.conflicts());
        slots.put("pendingAbsenceWarnings", e.warnings());
        // Only kept slots can exceed the merged plan; CONFLICT is confirmed as DROP (or
        // without the new slots).
        slots.put("durationExceeded", "KEEP".equals(outcome)
                && e.targetAfter().scheduled() > Policy.ceil15(e.targetAfter().plannedMinutes()));
        var out = new LinkedHashMap<String, Object>();
        out.put("timePlanVersion", p.version());
        out.put("plannedDuration", Duration.ofMinutes(p.plannedMinutes()).toString());
        out.put("scheduledDuration", Duration.ofMinutes(p.scheduled()).toString());
        out.put("sourceUnscheduledDuration",
                Duration.ofMinutes(Math.max(0, p.plannedMinutes() - p.scheduled())).toString());
        out.put("capacity",
                Map.of("bookedDuration", Duration.ofMinutes(c.bookedMinutes()).toString(), "availableDuration",
                        Duration.ofMinutes(c.availableMinutes()).toString(), "gains", gains, "fits", fits));
        out.put("merge", merge);
        out.put("slots", slots);
        return out;
    }
}
