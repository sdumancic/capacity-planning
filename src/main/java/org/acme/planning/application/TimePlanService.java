package org.acme.planning.application;

import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TimePlanService {
    private final PlanningRepository repo;
    private final PlanningLockRepository locks;
    private final CommonPlanningService common;
    private final TimeSlotService slotService;
    private final TimePlanLogService logs;
    public TimePlanService(PlanningRepository repo, PlanningLockRepository locks, CommonPlanningService common,
            TimeSlotService slots, TimePlanLogService logs) {
        this.repo = repo;
        this.locks = locks;
        this.common = common;
        this.slotService = slots;
        this.logs = logs;
    }

    @Transactional
    public Map<String, Object> create(Create c) {
        if (c == null || c.workOrderId() == null || c.day() == null) {
            throw Problem.bad("workOrderId and day required");
        }
        common.day(c.day());
        int n = Policy.minutes(c.duration(), false);
        var a = common.valid(c.workOrderId(), c.teamId(), c.memberId());
        locks.lockAssignees(List.of(new Plan(0, c.workOrderId(), a.teamId(), a.memberId(), c.day(), n, 0, List.of())));
        var p = repo.target(c.workOrderId(), a.teamId(), a.memberId(), c.day());
        if (p != null) { locks.lockPlans(List.of(p.id())); p = repo.find(p.id()); }
        // The API adds to the existing plan without a version; a client that sends one still gets 409 when stale.
        if (p != null && c.expectedVersion() != null) {
            common.version(p, c.expectedVersion());
        }
        var next = p == null ? 
                            new Plan(0, c.workOrderId(), a.teamId(), a.memberId(), c.day(), n, 0, List.of())
                            : p.change(p.teamId(), p.memberId(), Math.addExact(p.plannedMinutes(), n), p.slots());
        common.limit(next);
        common.capacity(null, a, c.day(), n, Boolean.TRUE.equals(c.allowOverbooking()));
        next = repo.save(next);
        logs.logMove("PLAN", p, next, null, UUID.randomUUID(), n, null, next);
        return common.result(List.of(next), List.of());
    }

    @Transactional
    public Map<String, Object> change(long id, Change c) {
        if (c == null) {
            throw Problem.bad("Body required");
        }
        int n = Policy.minutes(c.plannedDuration(), false);
        var p = repo.find(id);
        locks.lockAssignees(List.of(p));
        locks.lockPlans(List.of(p.id()));
        p = repo.find(id);
        common.version(p, c.expectedVersion());
        var next = p.change(p.teamId(), p.memberId(), n, p.slots());
        common.limit(next);
        if (n>p.plannedMinutes()) {
            common.capacity(null, common.valid(p.workOrderId(), p.teamId(), p.memberId()), p.day(), n-p.plannedMinutes(), Boolean.TRUE.equals(c.allowOverbooking()));
        }
        next = repo.save(next);
        logs.log("CHANGE_DURATION", p, next, null, UUID.randomUUID(), n-p.plannedMinutes());
        return common.result(List.of(next), List.of());
    }

    @Transactional
    public Map<String, Object> delete(long id, Integer expected) {
        var p = repo.find(id);
        locks.lockAssignees(List.of(p));
        locks.lockPlans(List.of(p.id()));
        p = repo.find(id);
        common.version(p, expected);
        var sync = new ArrayList<Map<String, Object>>();
        common.sync(p, null, sync);
        repo.delete(p);
        logs.log("DELETE", p, null, null, UUID.randomUUID(), p.plannedMinutes());
        return common.result(List.of(), sync);
    }

    private Map<String,Object> view(Plan p) {
        var view = new LinkedHashMap<>(PlanningViews.plan(p));
        var warnings = new ArrayList<Map<String,Object>>();
        var issues = slotService.conflicts(p,Set.of(p.id()),List.of(p),warnings);
        var slots = new ArrayList<Map<String,Object>>();
        for (var slot:p.slots()) {
            var item = new LinkedHashMap<String,Object>();
            item.put("id",slot.id());
            item.put("start",slot.start());
            item.put("end",slot.end());
            item.put("duration",Duration.ofMinutes(slot.minutes()).toString());
            item.put("hubStatus",slot.hubStatus() == null?"PENDING":slot.hubStatus());
            item.put("conflicts",issues.stream().filter(c -> Objects.equals(c.get("slotId"),slot.id())).toList());
            item.put("warnings",warnings.stream().filter(c -> Objects.equals(c.get("slotId"),slot.id())).toList());
            slots.add(item);
        }
        view.put("slots",slots);
        return view;
    }

    public Map<String, Object> planner(LocalDate day, Integer dealer) {
        common.day(day);
        var result = new LinkedHashMap<>(repo.planner(day, dealer, common.zone().toString()));
        var dealers = new HashMap<Integer,Integer>();
        result.put("timePlans",repo.list(day,null).stream().filter(p -> dealer == null || dealers.computeIfAbsent(p.workOrderId(), o -> repo.order(o).dealerId()).equals(dealer)).map(this::view).toList());
        return result;
    }

    public Object list(LocalDate day, Integer order) {
        if (day != null) {
            common.day(day);
        }
        return repo.list(day, order).stream().map(this::view).toList();
    }

    public Object find(long id) {
        return view(repo.find(id));
    }
}
