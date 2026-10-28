package org.acme.planning.application;

import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.*;

@ApplicationScoped
public class SchedulesService {
    private final PlanningRepository repo;
    private final PlanningLockRepository locks;
    private final CommonPlanningService common;
    private final TimeSlotService slots;
    private final TimePlanLogService logs;

    public SchedulesService(PlanningRepository repo, PlanningLockRepository locks, CommonPlanningService common,
            TimeSlotService slots, TimePlanLogService logs) {
        this.repo = repo;
        this.locks = locks;
        this.common = common;
        this.slots = slots;
        this.logs = logs;
    }

    @Transactional
    public Map<String, Object> schedule(List<Schedule> schedules) {
        if (schedules != null && schedules.size() > 50) {
            throw new Problem(400, "TOO_MANY_TIME_PLANS", "At most 50 schedules");
        }
        if (schedules == null || schedules.isEmpty()) {
            throw Problem.bad("1–50 schedules required");
        }
        var before = new ArrayList<Plan>();
        var after = new ArrayList<Plan>();
        var excluded = new HashSet<Long>();
        for (var s : schedules) {
            if (s == null || s.timePlanId() == null || !excluded.add(s.timePlanId())) {
                throw Problem.bad("Unique timePlanId required");
            }
            var p = repo.find(s.timePlanId());
            if (s.slots() != null && !s.slots().isEmpty()) {
                common.valid(p.workOrderId(), p.teamId(), p.memberId());
            }
            if (p.memberId() == null) {
                throw new Problem(400, "TEAM_TIME_PLAN", "Team plans cannot be scheduled");
            }
            before.add(p);
            after.add(p.change(p.teamId(), p.memberId(), p.plannedMinutes(), slots.requested(p, s.slots())));
        }
        locks.lockAssignees(before);
        locks.lockPlans(excluded);
        before.clear();
        after.clear();
        var stale = new ArrayList<Long>();
        for (var item : schedules) {
            var p = repo.find(item.timePlanId());
            if (item.expectedVersion() == null) {
                throw Problem.bad("expectedVersion required");
            }
            if (p.version() != item.expectedVersion()) {
                stale.add(p.id());
            }
            before.add(p);
            after.add(p.change(p.teamId(), p.memberId(), p.plannedMinutes(), slots.requested(p, item.slots())));
        }
        if (!stale.isEmpty()) {
            throw new Problem(409, "VERSION_CONFLICT", "Time plans changed; reload", Map.of("staleIds", stale));
        }
        slots.validateRequestOverlaps(after);
        var warnings = new ArrayList<Map<String, Object>>(); // the response recomputes them from the saved state
        for (var p : after) {
            common.limit(p);
        }
        var conflicts = slots.conflicts(after, excluded, warnings);
        if (conflicts.stream().anyMatch(c -> "OUTSIDE_VEHICLE_WINDOW".equals(c.get("reason")))) {
            throw new Problem(409, "OUTSIDE_VEHICLE_WINDOW", "Slot outside vehicle window",
                    Map.of("conflicts", conflicts));
        }
        if (!conflicts.isEmpty()) {
            throw new Problem(409, "SLOT_NOT_FREE", "Slots conflict with availability", Map.of("conflicts", conflicts));
        }
        var sync = new ArrayList<Map<String, Object>>();
        var saved = new ArrayList<Plan>();
        var correlation = UUID.randomUUID();
        for (int i = 0; i < after.size(); i++) {
            var p = repo.save(after.get(i));
            saved.add(p);
            common.sync(before.get(i), p, sync);
            logs.log("SCHEDULE", before.get(i), p, null, correlation, p.scheduled());
        }
        return common.result(saved, sync);
    }
}
