package org.acme.planning;

import org.junit.jupiter.api.Test;
import org.acme.planning.application.*;
import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningServicesTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(ZoneId.of("Europe/Zagreb")).toInstant();
    }
    static Slot slot(long id, int hour) {
        return new Slot(id, at(hour), at(hour + 1));
    }
    static Plan plan(long id, int order, int member, int minutes, Slot...slots) {
        return new Plan(id, order, 53, member, DAY, minutes, 0, List.of(slots));
    }
    static class Memory implements PlanningRepository, PlanningLockRepository {
        Map<Long, Plan> plans = new LinkedHashMap<>();
        List<Interval> blocked = new ArrayList<>();
        List<Interval> pending = new ArrayList<>();
        int available = 1000;
        Integer teamAvailable;
        Set<Integer> disabledMembers = new HashSet<>();
        List<Integer> auditMinutes = new ArrayList<>();
        int saves;
        long sequence = 100;
        List<String> logs = new ArrayList<>();
        List<Long> enqueued = new ArrayList<>();
        List<Collection<Plan>> lockCalls = new ArrayList<>();
        public void lockAssignees(Collection<Plan> p) {
            lockCalls.add(List.copyOf(p));
        }
        public Optional<Held> lockHubUserDay(int u, LocalDate d, Duration t) {
            return Optional.of(() -> {});
        }
        public void lockPlans(Collection<Long> ids) {
        }
        public Plan find(long id) {
            return plans.get(id);
        }
        public List<Plan> list(LocalDate d, Integer o) {
            return new ArrayList<>(plans.values());
        }
        public List<Plan> personPlans(int user, LocalDate d) {
            return plans.values().stream().filter(p -> p.memberId() != null && p.day().equals(d)
                    && assignee(p.teamId(), p.memberId()).userId() == user).toList();
        }
        public Plan target(int o, int t, Integer m, LocalDate d) {
            return plans.values().stream().filter(p -> p.workOrderId() == o && p.teamId() == t && Objects.equals(p.memberId(), m) && p.day().equals(d)).findFirst().orElse(null);
        }
        public Order order(int id) {
            return new Order(id, 1, "Order", at(6), at(19));
        }
        public Assignee assignee(Integer t, Integer m) {
            return new Assignee(t == null?53:t, m, 1, m == null?null:m == 999?452:m, !disabledMembers.contains(m));
        }
        public Capacity capacity(int t, Integer m, LocalDate d) {
            int free = m == null && teamAvailable != null ? teamAvailable : available;
            return new Capacity(480, 480-free, free);
        }
        public List<Interval> shifts(Assignee a, LocalDate d) {
            return List.of(new Interval(1L, at(7), at(12), "SHIFT", null), new Interval(2L, at(13), at(18), "SHIFT", null));
        }
        public List<Interval> blocks(int u, LocalDate d, boolean p) {
            return p?pending:blocked;
        }
        public List<Interval> booked(int user, LocalDate d, Set<Long> excluded) {
            var out = new ArrayList<Interval>();
            for (var p:plans.values()) {
                if (!excluded.contains(p.id()) && p.memberId() != null && assignee(p.teamId(), p.memberId()).userId() == user) {
                    for (var s:p.slots()) {
                        out.add(new Interval(s.id(), s.start(), s.end(), "BOOKED", p.workOrderId()));
                    }
                }
            }
            return out;
        }
        public Plan save(Plan p) {
            saves++;
            long id = p.id() == 0?++sequence:p.id();
            var slots = p.slots().stream().map(s -> new Slot(s.id() == null?++sequence:s.id(), s.start(), s.end())).toList();
            var saved = new Plan(id, p.workOrderId(), p.teamId(), p.memberId(), p.day(), p.plannedMinutes(), p.id() == 0?0:p.version() + 1, slots);
            plans.put(id, saved);
            return saved;
        }
        public void delete(Plan p) {
            plans.remove(p.id());
        }
        public void enqueue(long s, int u, LocalDate d) {
            enqueued.add(s);
        }
        public void log(String op, Plan b, Plan a, Long r, UUID c, Integer minutes) {
            logs.add(op);
            auditMinutes.add(minutes);
        }
        public Map<String, Object> planner(LocalDate d, Integer i, String z) {
            return Map.of();
        }
        public List<Map<String, Object>> logs(long id) {
            return List.of();
        }
    }
    CommonPlanningService common(Memory r) {
        return new CommonPlanningService(r, "Europe/Zagreb");
    }

    TimePlanService plans(Memory r) {
        var common = common(r);
        return new TimePlanService(r, r, common, new TimeSlotService(r, common), new TimePlanLogService(r));
    }

    SchedulesService schedules(Memory r) {
        var common = common(r);
        return new SchedulesService(r, r, common, new TimeSlotService(r, common), new TimePlanLogService(r));
    }

    TimePlanMoveService moves(Memory r) {
        var common = common(r);
        return new TimePlanMoveService(r, r, common, new TimeSlotService(r, common), new TimePlanLogService(r));
    }

    @Test
    void batchSwapValidatesFinalState() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60, slot(21, 8)));
        r.plans.put(2L, plan(2, 1506, 452, 60, slot(31, 9)));
        schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(slot(21, 9))), new Schedule(2L, 0, List.of(slot(31, 8)))));
        assertEquals(at(9), r.find(1).slots().getFirst().start());
        assertEquals(2, r.enqueued.size());
    }

    @Test
    void finalStateOverlapRejectsBeforeAnySave() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60, slot(21, 8)));
        r.plans.put(2L, plan(2, 1506, 452, 60, slot(31, 9)));
        var e = assertThrows(Problem.class, () -> schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(slot(21, 10))), new Schedule(2L, 0, List.of(slot(31, 10))))));
        assertEquals("SLOT_OVERLAP", e.code);
        assertEquals(0, r.saves);
    }

    @Test
    void differentMembershipSamePersonBlocks() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60));
        r.plans.put(2L, plan(2, 1506, 999, 60, slot(31, 9)));
        assertThrows(Problem.class, () -> schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(new Slot(null, at(9), at(10)))))));
        assertEquals(0, r.saves);
    }

    @Test
    void staleVersionRejected() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60));
        var e = assertThrows(Problem.class, () -> plans(r).change(1, new Change(9, "PT2H", false)));
        assertEquals("VERSION_CONFLICT", e.code);
        assertEquals(0, r.saves);
    }

    @Test
    void pendingAbsenceWarnsButDoesNotBlock() {
        var r = new Memory();
        r.pending.add(new Interval(50L, at(9), at(10), "PENDING_ABSENCE", null));
        r.plans.put(1L, plan(1, 1505, 452, 60));
        var result = schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(new Slot(null, at(9), at(10))))));
        assertEquals(1, ((List<?>)result.get("warnings")).size());
        assertNotNull(((Map<?, ?>)((List<?>)result.get("warnings")).getFirst()).get("slotId"));
    }

    @Test
    void lunchBreakBlocks() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 120));
        assertThrows(Problem.class, () -> schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(new Slot(null, at(11), at(13)))))));
    }

    @Test
    void approvedAbsenceBlocks() {
        var r = new Memory();
        r.blocked.add(new Interval(50L, at(9), at(10), "ABSENCE", null));
        r.plans.put(1L, plan(1, 1505, 452, 60));
        assertThrows(Problem.class, () -> schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(new Slot(null, at(9), at(10)))))));
    }

    @Test
    void ceilingAllows105For97ButNot120() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 97));
        schedules(r).schedule(List.of(new Schedule(1L, 0, List.of(new Slot(null, at(9), at(10).plusSeconds(45*60))))));
        assertThrows(Problem.class, () -> plans(r).change(1, new Change(1, "PT1H30M", false)));
    }

    @Test
    void ownTeamMoveDoesNotCheckUnchangedTeamCapacity() {
        var r = new Memory();
        r.available = -100;
        r.plans.put(1L, plan(1, 1505, 452, 60));
        moves(r).move(1, new Move(0, 53, null, null, null, null, false, null, null), null);
        assertNull(r.find(1).memberId());
    }

    @Test
    void capacityGainRequiresConfirmation() {
        var r = new Memory();
        r.available = 0;
        r.plans.put(1L, plan(1, 1505, 452, 60));
        var e = assertThrows(Problem.class, () -> moves(r).move(1, new Move(0, 53, 460, null, null, null, false, null, null), null));
        assertEquals("CAPACITY_EXCEEDED", e.code);
        assertEquals(0, r.saves);
    }

    @Test
    void previewReportsMergeRoundingWithoutWriting() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 50, slot(21, 9)));
        r.plans.put(2L, plan(2, 1505, 460, 50, slot(31, 10)));
        var result = moves(r).preview(1, new Move(null, 53, 460, null, null, null, false, null, null));
        assertEquals(true, ((Map<?, ?>)result.get("slots")).get("durationExceeded"));
        assertEquals(0, r.saves);
        assertThrows(Problem.class, () -> moves(r).move(1, new Move(0, 53, 460, null, null, null, false, null, null), null));
    }

    @Test
    void roundedSlotCannotMakeSourceNegative() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 50, slot(21, 9)));
        moves(r).move(1, new Move(0, 53, 460, null, null, null, true, at(10), at(11)), 21L);
        assertEquals(50, r.find(1).plannedMinutes());
        assertEquals(460, r.find(1).memberId());
        assertEquals(21L, r.find(1).slots().getFirst().id());
    }

    @Test
    void previewIncludesGainingTeamCapacity() {
        var r = new Memory();
        r.teamAvailable = 0;
        r.plans.put(1L, plan(1,1505,452,60));
        var result = moves(r).preview(1,new Move(null,61,470,null,null,null,false,null,null));
        assertEquals(false,((Map<?,?>)result.get("capacity")).get("fits"));
    }

    @Test
    void teamChipScheduleHasSeparateAuditAndMovedMinutes() {
        var r = new Memory();
        r.plans.put(1L,new Plan(1,1505,53,null,DAY,120,0,List.of()));
        moves(r).move(1,new Move(0,53,460,"PT1H",List.of(new Slot(null,at(9),at(10))),null,false,null,null),null);
        assertEquals(List.of("MOVE_PLAN","MOVE_PLAN","SCHEDULE"),r.logs);
        assertEquals(60,r.auditMinutes.getFirst());
        assertEquals(60,r.auditMinutes.getLast());
    }

    @Test
    void malformedSlotAndOutOfRangeDayAreBadRequests() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,60));
        var e = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(1L,0,List.of(new Slot(null,null,at(10)))))));
        assertEquals(400,e.status);
        assertThrows(Problem.class,() -> plans(r).planner(LocalDate.of(1999,12,31),1));
        assertEquals(0,r.saves);
    }

    @Test
    void newlyApprovedAbsenceAppearsAsReadConflictWithoutChangingSlot() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,60,slot(21,9)));
        r.blocked.add(new Interval(50L,at(9),at(10),"ABSENCE",null));
        var result = (Map<?,?>)plans(r).find(1);
        var slot = (Map<?,?>)((List<?>)result.get("slots")).getFirst();
        assertEquals("ABSENCE",((Map<?,?>)((List<?>)slot.get("conflicts")).getFirst()).get("reason"));
        assertEquals(0,r.saves);
    }

    @Test
    void disabledMemberReadMarksConflicts() {
        var r = new Memory();
        r.disabledMembers.add(452);
        r.plans.put(1L,plan(1,1505,452,60,slot(21,9)));
        var result = (Map<?,?>)plans(r).find(1);
        var slot = (Map<?,?>)((List<?>)result.get("slots")).getFirst();
        assertEquals("DISABLED_ASSIGNEE",((Map<?,?>)((List<?>)slot.get("conflicts")).getFirst()).get("reason"));
        assertEquals(0,r.saves);
    }

    @Test void disabledMemberCanUnscheduleExistingSlots() {
        var r = new Memory();
        r.disabledMembers.add(452);
        r.plans.put(1L, plan(1, 1505, 452, 60, slot(21, 9)));
        schedules(r).schedule(List.of(new Schedule(1L, 0, List.of())));
        assertTrue(r.find(1).slots().isEmpty());
        assertEquals(List.of(21L), r.enqueued);
    }
    @Test void rounded105SlotMoves97PlannedMinutesToTeamAndDeletesSource() {
        var r = new Memory();
        r.plans.put(1L, plan(1,1505,452,97,new Slot(21L,at(9),at(9).plusSeconds(105*60))));
        moves(r).move(1,new Move(0,53,null,null,null,null,false,null,null),21L);
        assertNull(r.find(1));
        var target = r.target(1505,53,null,DAY);
        assertNotNull(target);
        assertEquals(97,target.plannedMinutes());
        assertTrue(target.slots().isEmpty());
        assertEquals(105,r.auditMinutes.getFirst());
        assertEquals(List.of(21L),r.enqueued);
    }

    @Test void roundedSlotMergesOnlyWhenCombinedCeilingAllowsIt() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,97,new Slot(21L,at(9),at(9).plusSeconds(105*60))));
        r.plans.put(2L,plan(2,1505,460,8));
        moves(r).move(1,new Move(0,53,460,null,null,null,false,at(9),at(9).plusSeconds(105*60)),21L);
        assertNull(r.find(1));
        assertEquals(105,r.find(2).plannedMinutes());
        assertEquals(21L,r.find(2).slots().getFirst().id());
    }

    @Test void zeroDurationPatchRejectedBeforeWriting() {
        var r = new Memory(); r.plans.put(1L,plan(1,1505,452,60));
        assertThrows(Problem.class,() -> plans(r).change(1,new Change(0,"PT0M",false)));
        assertEquals(0,r.saves);
    }

    @Test void allStaleBatchIdsReportedBeforeWriting() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,60)); r.plans.put(2L,plan(2,1506,460,60));
        var e = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(1L,3,List.of()),new Schedule(2L,4,List.of()))));
        assertEquals(List.of(1L,2L),e.details.get("staleIds"));
        assertEquals(0,r.saves);
    }

    @Test void batchOverlapAcrossMembershipsIsInvalidRequest() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,60)); r.plans.put(2L,plan(2,1506,999,60));
        var e = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(1L,0,List.of(new Slot(null,at(9),at(10)))),new Schedule(2L,0,List.of(new Slot(null,at(9),at(10)))))));
        assertEquals(400,e.status); assertEquals("SLOT_OVERLAP",e.code); assertEquals(0,r.saves);
    }

    @Test void pendingWarningIsOnlyIntersectionWithSlot() {
        var r = new Memory(); r.plans.put(1L,plan(1,1505,452,60));
        r.pending.add(new Interval(50L,at(8),at(11),"PENDING_ABSENCE",null));
        var result = schedules(r).schedule(List.of(new Schedule(1L,0,List.of(new Slot(null,at(9),at(10))))));
        var warning = (Map<?,?>)((List<?>)result.get("warnings")).getFirst();
        assertEquals(at(9),warning.get("from")); assertEquals(at(10),warning.get("to"));
        assertEquals(60,r.auditMinutes.getFirst());
    }

    @Test void teamScheduleAndForeignSlotHaveSpecificErrors() {
        var r = new Memory();
        r.plans.put(1L,new Plan(1,1505,53,null,DAY,60,0,List.of()));
        var team = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(1L,0,List.of()))));
        assertEquals("TEAM_TIME_PLAN",team.code);
        r.plans.put(2L,plan(2,1505,452,60));
        var foreign = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(2L,0,List.of(slot(99,9))))));
        assertEquals(404,foreign.status); assertEquals("TIME_PLAN_NOT_FOUND",foreign.code);
        assertEquals(0,r.saves);
    }

    @Test void vehicleWindowAndBatchLimitHaveSpecificErrors() {
        var r = new Memory(); r.plans.put(1L,plan(1,1505,452,60));
        var window = assertThrows(Problem.class,() -> schedules(r).schedule(List.of(new Schedule(1L,0,List.of(new Slot(null,at(5),at(6)))))));
        assertEquals(409,window.status); assertEquals("OUTSIDE_VEHICLE_WINDOW",window.code);
        var oversized = Collections.nCopies(51,new Schedule(1L,0,List.of()));
        var count = assertThrows(Problem.class,() -> schedules(r).schedule(oversized));
        assertEquals(400,count.status); assertEquals("TOO_MANY_TIME_PLANS",count.code);
        assertEquals(0,r.saves);
    }

    @Test void teamMergePreservesExistingSlotConflictedByNewAbsence() {
        var r = new Memory();
        r.plans.put(1L,new Plan(1,1505,53,null,DAY,60,0,List.of()));
        r.plans.put(2L,plan(2,1505,460,60,slot(31,9)));
        r.blocked.add(new Interval(50L,at(9),at(10),"ABSENCE",null));
        moves(r).move(1,new Move(0,53,460,null,null,null,false,null,null),null);
        assertNull(r.find(1));
        assertEquals(120,r.find(2).plannedMinutes());
        assertEquals(List.of(slot(31,9)),r.find(2).slots());
        assertTrue(r.enqueued.isEmpty());
    }

    @Test void dropMergePreservesExistingTargetSlotConflictedByNewAbsence() {
        var r = new Memory();
        r.plans.put(1L,plan(1,1505,452,60,slot(21,8)));
        r.plans.put(2L,plan(2,1505,460,60,slot(31,9)));
        r.blocked.add(new Interval(50L,at(9),at(10),"ABSENCE",null));
        moves(r).move(1,new Move(0,53,460,null,null,"DROP",false,null,null),null);
        assertNull(r.find(1));
        assertEquals(120,r.find(2).plannedMinutes());
        assertEquals(List.of(slot(31,9)),r.find(2).slots());
        assertEquals(List.of(21L),r.enqueued);
    }

    @Test void incomingSlotStillCannotOverlapExistingTargetSlot() {
        var r = new Memory();
        r.plans.put(1L,new Plan(1,1505,53,null,DAY,60,0,List.of()));
        r.plans.put(2L,plan(2,1505,460,60,slot(31,9)));
        var e = assertThrows(Problem.class,() -> moves(r).move(1,new Move(0,53,460,null,List.of(new Slot(null,at(9),at(10))),null,false,null,null),null));
        assertEquals("SLOT_NOT_FREE",e.code);
        assertEquals(0,r.saves);
    }

    @Test void outsideShiftReasonMatchesSchedulingContract() {
        var r = new Memory(); r.plans.put(1L,plan(1,1505,452,60,slot(21,12)));
        var result = (Map<?,?>)plans(r).find(1);
        var slot = (Map<?,?>)((List<?>)result.get("slots")).getFirst();
        assertEquals("OUTSIDE_SHIFT",((Map<?,?>)((List<?>)slot.get("conflicts")).getFirst()).get("reason"));
    }


    @Test void planOnExistingPlanNeedsNoVersionButRejectsAStaleOne() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 180));
        plans(r).create(new Create(1505, 53, 452, DAY, "PT1H", false, null));
        assertEquals(240, r.find(1).plannedMinutes());
        var stale = assertThrows(Problem.class, () -> plans(r).create(new Create(1505, 53, 452, DAY, "PT1H", false, 0)));
        assertEquals("VERSION_CONFLICT", stale.code);
    }

    @Test void everyWriteTakesItsLocksInOnePass() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60, slot(21, 9)));
        moves(r).move(1, new Move(0, 61, 470, null, null, null, false, null, null), null);
        assertEquals(1, r.lockCalls.size());
        r.lockCalls.clear();
        plans(r).create(new Create(1506, 53, 452, DAY, "PT1H", false, null));
        assertEquals(1, r.lockCalls.size());
    }

    @Test void staleMoveIsVersionConflictBeforeRuleChecks() {
        var r = new Memory();
        // The client loaded version 0 without slots; the plan has since been scheduled.
        r.plans.put(1L, new Plan(1, 1505, 53, 452, DAY, 120, 1, List.of(slot(21, 9))));
        var e = assertThrows(Problem.class, () -> moves(r).move(1, new Move(0, 53, null, "PT1H", null, null, false, null, null), null));
        assertEquals("VERSION_CONFLICT", e.code);
    }

    @Test void slotConflictIsReportedBeforeDurationAndCarriesCapacity() {
        var r = new Memory();
        r.available = 0;
        r.plans.put(1L, plan(1, 1505, 452, 50, slot(21, 9)));
        r.plans.put(2L, plan(2, 1505, 460, 50, slot(31, 9)));
        var e = assertThrows(Problem.class, () -> moves(r).move(1, new Move(0, 53, 460, null, null, null, false, null, null), null));
        assertEquals("SLOT_NOT_FREE", e.code);
        assertNotNull(e.details.get("capacity"));
        assertEquals(0, r.saves);
    }

    @Test void previewDurationExceededOnlyForKeptSlots() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 50, slot(21, 9)));
        r.plans.put(2L, plan(2, 1505, 460, 50, slot(31, 9)));
        var result = moves(r).preview(1, new Move(null, 53, 460, null, null, null, false, null, null));
        var slots = (Map<?, ?>) result.get("slots");
        assertEquals("CONFLICT", slots.get("outcome"));
        assertEquals(false, slots.get("durationExceeded"));
        assertEquals(1, slots.get("count"));
        assertEquals("PT1H40M", ((Map<?, ?>) result.get("merge")).get("plannedAfterDuration"));
    }

    @Test void blockingChangeReportsConflictsWithoutChangingSlots() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 60, slot(21, 9)));
        r.blocked.add(new Interval(50L, at(9), at(10), "ABSENCE", null));
        var common = common(r);
        var conflicts = new ExternalChangeService(r, r, new TimeSlotService(r, common)).onBlockingChange(452, List.of(DAY));
        assertEquals(1, conflicts.size());
        assertEquals("ABSENCE", conflicts.getFirst().get("reason"));
        assertEquals(1L, conflicts.getFirst().get("timePlanId"));
        assertEquals(1, r.lockCalls.size());
        assertEquals(0, r.saves);
    }

    @Test void removedHubEventResendsOnlyRejectedSlots() {
        var r = new Memory();
        r.plans.put(1L, plan(1, 1505, 452, 120, new Slot(21L, at(9), at(10), "REJECTED"), new Slot(22L, at(10), at(11), "SYNCED")));
        var common = common(r);
        var resent = new ExternalChangeService(r, r, new TimeSlotService(r, common)).onHubEventRemovedOrMoved(452, List.of(DAY));
        assertEquals(List.of(21L), resent);
        assertEquals(List.of(21L), r.enqueued);
    }
}
