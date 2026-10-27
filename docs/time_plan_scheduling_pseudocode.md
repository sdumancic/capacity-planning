# Work order time plans & scheduling – pseudocode

Tables: `work_order_time_plan`, `work_order_time_slot`, `work_order_time_plan_log`, `slot_hub_sync`, `hub_sync_outbox`.
Aligned with the doc "Work Order Planning & Scheduling" (rev 65). API base path `v3/work-order-time-plans`.
`work_order_planned_time_v2` is deprecated and not touched by this code.

## Current dummy implementation

The Java implementation follows section 8 with a simulated Hub adapter. Enqueue writes the outbox
and `slot_hub_sync` in the planning transaction; the REST resource is not transactional, so after
the service commits it calls `HubSyncService.syncNow` for the request's own slots (3 s budget,
person-days in parallel, deletes → updates → creates, one retry of a refused slot at the end of
its person-day) and returns the real status per slot in `hubSync[]`. `HubSyncDispatcher` is the
worker (`@Scheduled` every 2 s, `SKIP`): it claims up to 20 due messages plus the rest of their
person-days, and reclaims rows of crashed processors after their 60 s lock. Each person-day runs
under the session applock `hub-user-day:<pp_user_id>:<day>`, outside any DB transaction; planner
writes take only the sorted `team-day` / `user-day` transaction locks. Retries back off
5 s, 30 s, 2 min, 10 min, 1 h, 1 h, 1 h and end `DEAD` / `FAILED` after the 8th attempt; other 4xx
are `DEAD` at once; DEAD messages and a PENDING message older than 5 min are logged as `ALERT`.
`GuardedHubCalendarClient` wraps the adapter in a SmallRye Fault Tolerance circuit breaker
(5 failures in a row, half-open after 30 s); while open, messages are released without counting
an attempt. Update 404 clears the ID and creates; delete 404 is success; a deleted slot without an
ID is looked up by key; a successful delete removes the `slot_hub_sync` row.

`DummyHubCalendarClient` makes no network calls. It keeps live events in memory per application
instance (reset on restart, so updates of older IDs answer 404 and are recreated): create is
idempotent by external key, delete frees the key and a new create gets a new ID. It simulates
create/update HTTP 409 with `capacity.hub.dummy-conflict-probability` (0 outside dev, 0.35 in dev;
0 disables, 1 forces the initial conflict). After a simulated rejection, the next create/update
with changed start/end for the same external slot key succeeds once, even at probability 1.
Only the action that got `REJECTED` opens the planner's dialog; the planner does not poll.
`ExternalChangeService` holds the section 7 entry points (`onBlockingChange`,
`onHubEventRemovedOrMoved`) for the absence, hub event and shift handlers, which live outside
this service.

## Time model

| What | Java | Database | API |
|---|---|---|---|
| Instant (slot, event, absence, audit) | `Instant` | `DATETIME2(7)`, UTC | `"2026-10-28T06:00:00Z"` |
| Duration (planned, slot, unscheduled) | `Duration` | `INT` minutes | `"PT1H37M"` |
| Business day (plan day, non-working day) | `LocalDate` | `DATE` | `"2026-10-28"` |
| Shift time of day | `LocalTime` | `TIME(0)` | `"07:00:00"` |

`zone` below = `ZoneId` from `capacity.business-zone` (one per deployment and database, IANA, no default).
SQL uses the same zone through `dbo.calendar.utc_offset_minutes` (offset at noon), filled by `CalendarOffsetInitializer` at startup.
No night shifts, so a DST switch (02:00/03:00) never falls inside working hours.

```
localDay(instant)           := LocalDate.ofInstant(instant, zone)
toInstant(day, localTime)   := day.atTime(localTime).atZone(zone).toInstant()
dayStart(day) / dayEnd(day) := toInstant(day, 00:00) / toInstant(day + 1, 00:00)
```

## Terms

| Term | Meaning |
|---|---|
| **time plan** | Planned time of one work order for one assignee (team or member) on one day. One row in `work_order_time_plan`. Key: `(work_order_id, team_id, member_id, day)`, unique. |
| **slot** | A scheduled part of a member's time plan. One row in `work_order_time_slot`, ids kept across moves so hub events are updated, not recreated. |
| **planned_minutes** | Total planned duration of the time plan. |
| **unscheduled** | `planned_minutes − SUM(slot.minutes)`, calculated; shown as 0 when negative. |
| **fully scheduled** | `SUM(slot.minutes) >= floor15(planned_minutes)` (97 → 90). |
| **version** | `INT` on the time plan, JPA `@Version`. Hibernate checks and increments it on every update; when only slots change, `PESSIMISTIC_FORCE_INCREMENT` increments it anyway. |
| **merge** | Minutes (and slots) moved to an assignee who already has the work order on that day go into that time plan. |

## Invariants

1. One time plan per `(work_order_id, team_id, member_id, day)` (unique index).
2. `SUM(slot.minutes) <= ceil15(planned_minutes)` (97 → 105): on a 15-minute grid the next step above the planned minutes is allowed.
3. A time plan is one day: all its slots lie on `plan.day`. No action moves time between days.
4. Slot: `end_at > start_at` (check constraint); `localDay(start) == localDay(end) == plan.day` (service); start and end on the 15-minute grid in local time (service); inside one free interval of the member.
5. Slot inside the vehicle window: `dropoff_at <= start_at` and `end_at <= pickup_at`.
6. Only member time plans have slots.
7. A time plan has one assignee; a full move changes it or merges into the target's time plan.
8. Team and target member are enabled and belong to the same dealer as the source.

Capacity functions read `SUM(planned_minutes)` per (team/member, day) through `dbo.vw_planned_minutes`.
Booked = planned minutes, not scheduled minutes: slots may exceed planned by up to 14 minutes (15-minute grid); that difference is not booked.
Absences: APPROVED block capacity and slots; PENDING are shown only (`pending_absence_minutes`, slot warnings) and never reduce capacity.
Over capacity is a **warning**: the request must carry `allowOverbooking = true`, else `409 CAPACITY_EXCEEDED`.

---

## Helpers

```
lockScope(scopes[])                                       // scope = (teamId, memberId?, day)
    // Same lock names as absence/event/shift handlers.
    for s in SORT(DISTINCT(scopes)):                      // fixed order prevents deadlocks
        // person-level lock: one employee in two teams has two member ids but one pp_user_id
        key := s.memberId != null ? "user-day:" + ppUserId(s.memberId) + ":" + s.day
                                  : "team-day:"   + s.teamId   + ":" + s.day
        sp_getapplock(key, Exclusive, Transaction, timeout = 5s)
        if not granted -> throw Conflict("Planner is busy, try again")

lockPlans(planIds[])
    // two time plans touched by one merge: always lock in id order
    for id in SORT(planIds): em.lock(plan(id), PESSIMISTIC_WRITE)

loadPlan(planId)
    plan  := SELECT * FROM work_order_time_plan WHERE id = planId     else NotFound("TIME_PLAN_NOT_FOUND")
    slots := SELECT * FROM work_order_time_slot WHERE time_plan_id = planId
    plan.unscheduled := plan.planned_minutes − SUM(slots.minutes)
    return (plan, slots)

findPlan(workOrderId, teamId, memberId?, day)            // the unique key
    return SELECT * FROM work_order_time_plan
           WHERE work_order_id = workOrderId AND team_id = teamId
             AND member_id IS NOT DISTINCT FROM memberId AND day = day

ceil15(m)  := CEILING(m / 15) * 15                       // 97 -> 105, 90 -> 90
floor15(m) := FLOOR(m / 15) * 15                         // 97 -> 90

checkVersion(plan, expectedVersion)
    // early, readable 409; Hibernate's WHERE version = ? on flush is the real guard
    // (OptimisticLockException -> 409 VERSION_CONFLICT)
    if plan.version != expectedVersion -> throw Conflict("VERSION_CONFLICT, reload")

touch(plan)                                               // version + 1 even if the plan row is unchanged
    em.lock(plan, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    plan.modifiedAt = now(); plan.modifiedBy = currentUser

freeIntervals(memberId, day, excludePlanIds[])           // all intervals are UTC Instants
    work    := shift segments of the member's pp_user_id on day (shift ∩ dealer opening hours),
               each as [toInstant(day, seg.start), toInstant(day, seg.end))
    blocked := merged(APPROVED absences ∪ hub calendar events) of the member overlapping [dayStart(day), dayEnd(day))
               // PENDING absences never block: they only produce warnings (pendingOverlaps)
    booked  := slots of time plans of ANY membership of the same person (pp_team_member.pp_member_id
               = ppUserId(memberId)) on day, plan.id NOT IN excludePlanIds
               // one employee in two teams: slots in team A block the same time in team B
    return work − blocked − booked                        // list of [start, end)

validateSlots(plan, slots[], excludePlanIds[])
    wo := work order of plan
    for s in slots:
        require s.end > s.start and localDay(s.start) == localDay(s.end) == plan.day
                                                          else BadRequest("INVALID_SLOT")
        require s.start.atZone(zone).getMinute() % 15 == 0 and s.end.atZone(zone).getMinute() % 15 == 0
                and seconds == 0
                                                          else BadRequest("INVALID_SLOT", "not on 15-minute grid")
        require wo.dropoff_at <= s.start and s.end <= wo.pickup_at
                                                          else Conflict("OUTSIDE_VEHICLE_WINDOW", window)
    require no two slots overlap                          else BadRequest("SLOT_OVERLAP")
    free := freeIntervals(plan.member_id, plan.day, excludePlanIds)
    for s in slots:
        require exists f in free: f.start <= s.start and s.end <= f.end
                                                          else Conflict("SLOT_NOT_FREE", s)
    return SUM(minutes(s) for s in slots)

notFreeSlots(plan, slots[], excludePlanIds[])             // like validateSlots, but collects instead of throwing
    free := freeIntervals(plan.member_id, plan.day, excludePlanIds)
    return [ { slotId: s.id, start: s.start, end: s.end, reason: firstBlocker(s),
               blockedFrom, blockedTo, workOrderId }        // the blocking part and, for BOOKED, its work order
             for s in slots if not exists f in free: f.start <= s.start and s.end <= f.end ]
    // firstBlocker: OUTSIDE_SHIFT | ABSENCE | HUB_EVENT | BOOKED (another slot of the same person)

pendingOverlaps(memberId, day, slots[])                    // non-blocking, returned as warnings
    pending := PENDING absences of the member's pp_user_id overlapping [dayStart(day), dayEnd(day))
    return [ { code: "SLOT_OVERLAPS_PENDING_ABSENCE", slotId: s.id, absenceId: a.id,
               from: max(s.start, a.start), to: min(s.end, a.end) }
             for s in slots, a in pending if s.start < a.end and a.start < s.end ]

checkCapacity(teamId, memberId?, day, addedMinutes, allowOverbooking)
    // call only when the target team/member actually gains minutes on that day
    available := member or team available from fn_team_member_capacity(dealer, day, day)
    if available < addedMinutes and not allowOverbooking
        -> throw Conflict("CAPACITY_EXCEEDED", available, addedMinutes)

targetPlan(workOrderId, teamId, memberId?, day)          // existing plan of the key, or a new empty one
    p := findPlan(workOrderId, teamId, memberId, day)
    if p == null:
        INSERT work_order_time_plan(work_order_id, team_id, member_id, day, planned_minutes = 0)
        p := the inserted row
    return p

log(op, correlationId, planId, relatedPlanId?, from, to, minutes, before, after, initiatedBy = USER, reason?)
    INSERT work_order_time_plan_log (...)                 // append only, same transaction

enqueueHub(slotIds[], ownerPlan)                          // collects; written by flushHub() just before COMMIT
    // ownerPlan = the plan that owned the slots BEFORE the change (known even when the slot row is already deleted)
    for id in slotIds:
        pending += (id, ownerBefore = ppUserId(ownerPlan.member_id), day = ownerPlan.day)

flushHub()                                                // last step before COMMIT, after all writes
    for (id, ownerBefore, day) in pending:
        owner := slot id still exists ? ppUserId of its plan now : ownerBefore   // delete -> old owner
        UPDATE hub_sync_outbox WITH (UPDLOCK, HOLDLOCK) SET next_attempt_at = now(), pp_user_id = owner
        WHERE slot_id = id AND status = 'PENDING'
        if @@ROWCOUNT == 0: INSERT hub_sync_outbox(slot_id = id, pp_user_id = owner, day)
        MERGE slot_hub_sync: status = PENDING, error_code = NULL, error_message = NULL, updated_at = now()
                             (hub_event_id unchanged; inserted with NULL for a new slot)
    outboxIds := ids of these outbox rows                 // handed to syncNow after COMMIT
    // worker: see section 8
```

Every operation: `BEGIN TRANSACTION` → load → lock → re-load under lock → `checkVersion` → validate → write → `touch` every changed plan → log → `flushHub()` → `COMMIT` → `syncNow(outboxIds)` (section 8, outside the transaction) → response with `hubSync[]`.

---

## 1. Plan time (`POST /`)

New time plan, or added to the existing time plan of the same work order, assignee and day.

```
planTime(workOrderId, teamId, memberId?, day, minutes, allowOverbooking)
    require minutes > 0
    require team/member enabled, same dealer as the work order
    BEGIN TRANSACTION
    lockScope([(teamId, memberId, day)])
    checkCapacity(teamId, memberId, day, minutes, allowOverbooking)

    before := snapshot(findPlan(workOrderId, teamId, memberId, day))
    plan   := targetPlan(workOrderId, teamId, memberId, day)
    UPDATE plan SET planned_minutes = planned_minutes + minutes
    touch(plan.id)

    log(PLAN, NEWID(), plan.id, null, null, (teamId, memberId), minutes, before, snapshot(plan))
    COMMIT
    return plan
```

## 2. Schedule one time plan (`PUT /{id}/slots`)

The front sends all slots of the time plan. With id = update, without id = insert, missing = delete.
SCHEDULE never changes `planned_minutes`.

```
scheduleTimePlan(planId, expectedVersion, slots[])
    return scheduleBatch([{ planId, expectedVersion, slots }])
```

## 2a. Schedule several time plans (`PUT /schedules`)

All or nothing. Validates the final state (needed for swaps). Never changes the assignee.

```
scheduleBatch(items[])                                    // item = { planId, expectedVersion, slots[] }
    require items.count <= 50                             else BadRequest("TOO_MANY_TIME_PLANS")
    BEGIN TRANSACTION
    plans := items.map(i -> loadPlan(i.planId))
    require every plan.member_id != null                  else BadRequest("TEAM_TIME_PLAN")
    lockScope(plans.map(p -> (p.team_id, p.member_id, p.day)))
    plans := re-load all under lock
    stale := items where checkVersion fails
    if stale not empty -> throw Conflict("VERSION_CONFLICT", stale ids)

    batchIds := items.planId
    require no two request slots of the same member overlap
                                                          else BadRequest("SLOT_OVERLAP")
    for (p, i) in zip(plans, items):
        scheduled := validateSlots(p, i.slots, excludePlanIds = batchIds)
        require scheduled <= ceil15(p.planned_minutes)    else BadRequest("DURATION_EXCEEDED")

    correlationId := NEWID()
    for (p, i) in zip(plans, items):
        before  := snapshot(p)
        changed := []
        for s in i.slots where s.id != null:              // update by id -> hub event updated
            require s.id belongs to p                     else NotFound("TIME_PLAN_NOT_FOUND", slot)
            if times differ: UPDATE work_order_time_slot SET start_at, end_at WHERE id = s.id; changed += s.id
        for s in i.slots where s.id == null:
            INSERT work_order_time_slot(time_plan_id = p.id, start_at = s.start, end_at = s.end); changed += new id
        for s in p.slots where s.id not in i.slots.id:
            DELETE work_order_time_slot WHERE id = s.id; changed += s.id
        touch(p.id)
        log(SCHEDULE, correlationId, p.id, null, assignee, assignee, SUM(minutes(i.slots)), before, snapshot(p))
        enqueueHub(changed, p)

    flushHub()                                            // outbox rows + slot_hub_sync = PENDING, same transaction
    COMMIT
    // ---- after COMMIT, outside the transaction: hub calls and their errors (section 8)
    hubSync := syncNow(outboxIds)                         // per slot: SYNCED | REJECTED (HUB_OVERLAP) | FAILED | PENDING
                                                          // never throws: every hub error becomes a status of its slot
    return all plans with their slots and new versions,
           warnings = concat(pendingOverlaps(p.member_id, p.day, i.slots) for each item),
           hubSync
```

The same `warnings` are returned by `movePlan` (member target, kept or new slots) and `moveSlot` (member target).

Capacity: scheduling only splits planned minutes into slots and unscheduled time of the same plan,
so booked minutes do not change and no capacity check is needed.

## 3. Change duration (`PATCH /{id}`)

```
changeDuration(planId, expectedVersion, newMinutes, allowOverbooking)
    require newMinutes > 0
    BEGIN TRANSACTION
    (plan, slots) := loadPlan(planId)
    lockScope([(plan.team_id, plan.member_id, plan.day)])
    (plan, slots) := loadPlan(planId)
    checkVersion(plan, expectedVersion)

    require ceil15(newMinutes) >= SUM(slots.minutes)      else BadRequest("Unschedule slots first")
    if newMinutes > plan.planned_minutes:
        checkCapacity(plan.team_id, plan.member_id, plan.day,
                      newMinutes − plan.planned_minutes, allowOverbooking)

    before := snapshot(plan)
    UPDATE plan SET planned_minutes = newMinutes
    touch(planId)
    log(CHANGE_DURATION, NEWID(), planId, null, assignee, assignee, newMinutes − before.planned, before, snapshot(plan))
    COMMIT
```

## 4. Move time plan (`POST /{id}/move`)

`{ expectedVersion, targetTeamId, targetMemberId?, minutes?, slots?, slotHandling?, allowOverbooking }`.
No `minutes` = full move. Same day always.
`slotHandling` (member → member only): `KEEP` (default) or `DROP`.

| From → to | Full | Partial | Slots |
|---|---|---|---|
| Team → team | yes | yes | none |
| Team → member | yes | yes | optional `slots` for the target (chip dropped into a time cell) |
| Member → team | yes | only without slots | deleted, hub events deleted |
| Member → member, `KEEP` | yes | no | kept, all must be free at the target, else `409 SLOT_NOT_FREE` with all conflicting slots |
| Member → member, `DROP` | yes | no | all deleted, hub events deleted; only planned minutes move ("Move planned time only") |

UI flow: drop → `previewMove` (section 4a) → dialog → confirm → `movePlan` with `expectedVersion` from the
preview, `slotHandling = DROP` if the preview said `CONFLICT` (button "Move planned time only"), and
`allowOverbooking = true` if the user accepted "does not fit". The move validates again; any `409`
(`SLOT_NOT_FREE`, `CAPACITY_EXCEEDED`, `VERSION_CONFLICT`) makes the frontend call the preview again and show
the dialog with the new state.

```
movePlan(planId, expectedVersion, targetTeamId, targetMemberId?, minutes?, newSlots?, slotHandling = KEEP, allowOverbooking)
    BEGIN TRANSACTION
    (src, srcSlots) := loadPlan(planId)
    require target team (and member) enabled, same dealer as src.team_id
    require (targetTeamId, targetMemberId) != (src.team_id, src.member_id)
    full  := minutes == null or minutes == src.planned_minutes
    moved := full ? src.planned_minutes : minutes
    require full or (moved > 0 and moved <= src.unscheduled)
                                                          else BadRequest("Not enough unscheduled minutes")

    srcIsMember := src.member_id != null
    tgtIsMember := targetMemberId != null
    if srcIsMember and not full:
        require not tgtIsMember                           else BadRequest("Member → member is full only")
        require srcSlots is empty                         else BadRequest("A scheduled time plan moves only as a whole")
    if newSlots != null:
        require not srcIsMember and tgtIsMember           else BadRequest("slots only for team → member")
    if slotHandling == DROP:
        require srcIsMember and tgtIsMember               else BadRequest("slotHandling only for member → member")

    lockScope([(src.team_id, src.member_id, src.day),
               (targetTeamId, targetMemberId, src.day)]
              + (srcIsMember ? [(src.team_id, null, src.day)] : []))
    existing := findPlan(src.work_order_id, targetTeamId, targetMemberId, src.day)
    lockPlans([src.id] + (existing ? [existing.id] : []))
    (src, srcSlots) := loadPlan(planId)                   // re-read under lock
    checkVersion(src, expectedVersion)

    keep := srcIsMember and tgtIsMember and slotHandling == KEEP

    // --- slot check first (member → member, KEEP): one 409 carries slots AND capacity
    if keep:
        conflicts := notFreeSlots(stub(targetTeamId, targetMemberId, src.day), srcSlots,
                                  excludePlanIds = [src.id])
        if conflicts not empty:
            throw Conflict("SLOT_NOT_FREE", conflicts,    // every slot that does not fit, with reason
                           capacity = gains(...) ? capacityShortfall(targetTeamId, targetMemberId, src.day, moved) : null)
            // capacity is the same for KEEP and DROP, so the dialog asks once and the DROP retry
            // carries allowOverbooking; no second 409 CAPACITY_EXCEEDED
        if existing != null:                              // merge: target's own slots + moved slots
            require SUM(existing.slots.minutes) + SUM(srcSlots.minutes)
                    <= ceil15(existing.planned_minutes + moved)
                                                          else BadRequest("DURATION_EXCEEDED")

    if gains(targetTeamId, targetMemberId, src):          // see capacity table below
        checkCapacity(targetTeamId, targetMemberId, src.day, moved, allowOverbooking)

    before := snapshot(src)
    correlationId := NEWID()

    // --- slots of the source
    if srcIsMember and not keep:                          // member → team, or member → member DROP
        enqueueHub(srcSlots.id, src); DELETE work_order_time_slot WHERE time_plan_id = src.id
        srcSlots := []
    else if keep:
        enqueueHub(srcSlots.id, src)                      // participant changes

    // --- write
    if full and existing == null:
        // plain transfer: same plan, new assignee, slots follow their plan
        UPDATE src SET team_id = targetTeamId, member_id = targetMemberId
        target := src
    else:
        target := existing ?? targetPlan(src.work_order_id, targetTeamId, targetMemberId, src.day)
        UPDATE target SET planned_minutes = planned_minutes + moved
        if srcIsMember and tgtIsMember:                   // merge: re-point the slots, ids kept
            UPDATE work_order_time_slot SET time_plan_id = target.id WHERE time_plan_id = src.id
        if full: DELETE src                               // merged into the target
        else:    UPDATE src SET planned_minutes = planned_minutes − moved
    touch(target.id); if src still exists and src != target: touch(src.id)

    // --- team → member with slots (atomic MOVE + SCHEDULE)
    if newSlots != null:
        (target, targetSlots) := loadPlan(target.id)
        scheduled := validateSlots(target, targetSlots + newSlots, excludePlanIds = [target.id])
        require scheduled <= ceil15(target.planned_minutes) else BadRequest("DURATION_EXCEEDED")
        INSERT newSlots into work_order_time_slot(time_plan_id = target.id)
        enqueueHub(new slot ids, target)
        log(SCHEDULE, correlationId, target.id, ...)

    log(MOVE_PLAN, correlationId, src.id, target.id != src.id ? target.id : null, from, to, moved, before, snapshot(src or null))
    if target.id != src.id:
        log(MOVE_PLAN, correlationId, target.id, src.id, from, to, moved, ..., snapshot(target))
    flushHub(); COMMIT
    hubSync := syncNow(outboxIds)                         // after COMMIT, see section 8
    return (src if not deleted, target), hubSync
```

Capacity effect (`gains` is true for every row marked +):

| Case | Source member | Source team | Target team | Target member |
|---|---|---|---|---|
| team → other team | – | − | + | – |
| team → member of same team | – | 0 | 0 | + |
| team → member of other team | – | − | + | + |
| member → own team | − | 0 | 0 | – |
| member → other team | − | − | + | – |
| member → member, same team | − | 0 | 0 | + |
| member → member, other team | − | − | + | + |

## 4a. Move preview (`POST /{id}/move/preview`)

Same body as move, without `slotHandling`. Read only: no lock, no write, no log, no outbox. Uses the same
`evaluateMove` as `movePlan`, so the dialog and the move cannot drift apart. Move slot has no preview.

```
evaluateMove(src, srcSlots, targetTeamId, targetMemberId?, minutes?, newSlots?) -> MoveEvaluation
    // the validation part of movePlan, without throwing for slots and capacity
    full, moved, case     := MovePolicy.classify(src, targetTeamId, targetMemberId, minutes)   // BadRequest as in movePlan
    existing              := findPlan(src.work_order_id, targetTeamId, targetMemberId, src.day)
    gains                 := gains(targetTeamId, targetMemberId, src)
    available             := gains ? available(targetTeamId, targetMemberId, src.day) : null
    slotOutcome, conflicts, pending :=
        newSlots given (team → member)     -> conflicts := notFreeSlots(stub(target), newSlots, excludePlanIds = [])
                                              conflicts empty ? KEEP : CONFLICT   // DROP = send the move without slots
        srcSlots empty                     -> NONE
        srcIsMember and not tgtIsMember    -> REMOVED                       // team plans have no slots
        member → member:
            conflicts := notFreeSlots(stub(target), srcSlots, excludePlanIds = [src.id])  // with blockedFrom/To
            conflicts empty ? KEEP (+ pendingOverlaps(target, srcSlots)) : CONFLICT
    durationExceeded      := slotOutcome == KEEP and existing != null
                             and SUM(existing.slots) + SUM(srcSlots) > ceil15(existing.planned + moved)
    return { source: { version: src.version, planned: src.planned_minutes, scheduled: SUM(srcSlots.minutes),
                       unscheduled: src.unscheduled },                // amount dialog: left on the team
             target: { gains, booked: booked(target, src.day), available, fits: not gains or available >= moved,
                       existingTimePlan: existing ? { timePlanId: existing.id, planned: existing.planned_minutes,
                                                      plannedAfter: existing.planned_minutes + moved } : null },
             slots: { outcome: slotOutcome, count: srcSlots.count, conflicts, pending, durationExceeded } }

previewMove(planId, targetTeamId, targetMemberId?, minutes?, newSlots?)
    BEGIN TRANSACTION READ ONLY                           // snapshot only; the move checks again under lock
    (src, srcSlots) := loadPlan(planId)
    return evaluateMove(src, srcSlots, targetTeamId, targetMemberId, minutes, newSlots)
```

`movePlan` calls the same `evaluateMove` after the lock and throws where the preview only reports:
`CONFLICT` with `KEEP` → `409 SLOT_NOT_FREE`, `fits = false` without `allowOverbooking` → `409 CAPACITY_EXCEEDED`,
`durationExceeded` → `400 DURATION_EXCEEDED`.

UI usage: team → member asks for the amount (h:mm, default all remaining; all = full move without `minutes`),
the preview is called once and the dialog computes left on team / member planned after / available after locally.
Team → team and member → team are full moves in the UI; the partial variants stay in the API only.

Dialog rules taken from the preview: `target.gains = false` → "capacity does not change" instead of available time;
`target.existingTimePlan` → "added to the existing time plan, X → Y"; `CONFLICT` → each slot with its time and what blocks it
(`blockedFrom`–`blockedTo`, reason, other work order), and "N scheduled times will be removed" (DROP removes all).

## 5. Move slot (`POST /{id}/slots/{slotId}/move`)

`{ expectedVersion, targetMemberId, start, end, allowOverbooking }` or `{ expectedVersion, targetTeamId, allowOverbooking }`.
Moving a slot within the same member is not this action; it is `scheduleBatch`.

```
moveSlot(planId, expectedVersion, slotId, targetMemberId?, targetTeamId?, newStart?, newEnd?, allowOverbooking)
    BEGIN TRANSACTION
    (src, srcSlots) := loadPlan(planId)
    slot := srcSlots.find(slotId)                          else NotFound("Slot does not belong to time plan")

    if targetMemberId != null:
        require targetMemberId != src.member_id            else BadRequest("Use PUT slots for the same member")
        tgtTeam := team of targetMemberId; tgtMember := targetMemberId
        require newEnd − newStart == slot.minutes          // keeps its duration
        require localDay(newStart) == src.day              // same business day
    else:
        require targetTeamId != null
        tgtTeam := targetTeamId; tgtMember := null         // slot becomes unscheduled team time
    require target enabled, same dealer as src.team_id

    lockScope([(src.team_id, src.member_id, src.day), (src.team_id, null, src.day),
               (tgtTeam, tgtMember, src.day), (tgtTeam, null, src.day)])
    existing := findPlan(src.work_order_id, tgtTeam, tgtMember, src.day)
    lockPlans([src.id] + (existing ? [existing.id] : []))
    (src, srcSlots) := loadPlan(planId)
    checkVersion(src, expectedVersion)

    if tgtMember != null:
        movedSlot := { start = newStart, end = newEnd }
        validateSlots(existing ?? stub(tgtTeam, tgtMember, src.day), [movedSlot],
                      excludePlanIds = [src.id])           // slots of `existing` stay counted by freeIntervals
        if existing != null:                               // merge into the target's plan
            require SUM(existing.slots.minutes) + slot.minutes
                    <= ceil15(existing.planned_minutes + MIN(slot.minutes, src.planned_minutes))
                                                          else BadRequest("DURATION_EXCEEDED")
    if gains(tgtTeam, tgtMember, src):                     // slot → own team: no gain, no check
        checkCapacity(tgtTeam, tgtMember, src.day, MIN(slot.minutes, src.planned_minutes), allowOverbooking)

    before := snapshot(src)
    correlationId := NEWID()

    moved := MIN(slot.minutes, src.planned_minutes)       // a slot may be up to 14 min longer than the plan (ceil15)

    if tgtMember != null and srcSlots.count == 1 and src.unscheduled <= 0 and existing == null:
        // the slot is the whole time plan -> plain transfer, no split
        UPDATE src SET team_id = tgtTeam, member_id = tgtMember
        UPDATE slot SET start_at = newStart, end_at = newEnd
        target := src
    else:
        target := existing ?? targetPlan(src.work_order_id, tgtTeam, tgtMember, src.day)
        UPDATE target SET planned_minutes = planned_minutes + moved
        if tgtMember != null:
            UPDATE slot SET time_plan_id = target.id, start_at = newStart, end_at = newEnd   // id kept
        else:
            DELETE slot                                     // team plans have no slots
        UPDATE src SET planned_minutes = planned_minutes − moved
        if src.planned_minutes == 0: DELETE src
    touch(target.id); if src still exists and src != target: touch(src.id)

    enqueueHub([slot.id], src)     // member: update participant + time; team: delete the event
    log(MOVE_SLOT, correlationId, src.id, target.id, ..., slot.minutes)
    if target.id != src.id:
        log(MOVE_SLOT, correlationId, target.id, src.id, ..., slot.minutes)
    flushHub(); COMMIT
    hubSync := syncNow(outboxIds)                         // after COMMIT, see section 8
    return (src if not deleted, target), hubSync
```

## 6. Delete time plan (`DELETE /{id}`)

```
deletePlan(planId, expectedVersion)
    BEGIN TRANSACTION
    (plan, slots) := loadPlan(planId)
    lockScope([(plan.team_id, plan.member_id, plan.day)])
    (plan, slots) := loadPlan(planId)
    checkVersion(plan, expectedVersion)
    enqueueHub(slots.id, plan)                             // the hub events are deleted after COMMIT
    DELETE work_order_time_slot WHERE time_plan_id = planId
    DELETE work_order_time_plan WHERE id = planId
    log(DELETE, NEWID(), planId, null, assignee, null, plan.planned_minutes, snapshot, null)
    flushHub(); COMMIT
    return syncNow(outboxIds)                             // deletes are never refused: SYNCED or PENDING
```

Deleting team time only after the member times of that work order is a UI order, not a backend rule.

## 7. Conflicts after external events (absence, hub event, new shift version)

The system never moves or deletes the planner's slots. It marks conflicts; the planner resolves them.

```
// absence handler calls it when an absence becomes APPROVED (new, or PENDING -> APPROVED) or changes while APPROVED;
// PENDING -> REJECTED / CANCELLED and new PENDING absences call nothing (they never blocked)
// days = business days the changed interval touches: localDay(start) .. localDay(end − 1 ns)
// (an absence 2026-10-27T22:00Z–2026-10-28T22:00Z in Europe/Sofia is exactly 28.10)
onBlockingChange(ppUserId, days[])                         // called by absence/event/shift handlers
    for member in pp_team_member where pp_member_id = ppUserId:
        lockScope(days.map(d -> (member.team_id, member.id, d)))
        for slot in slots of member.id's time plans on days:
            free := freeIntervals(member.id, slot.day, [slot.time_plan_id])
            if slot not fully inside one interval of free:
                mark conflict (slot.id, reason)            // shown in planner
```

The log has no system unschedule operation. If an automatic unschedule is ever introduced, it gets
its own log operation (initiatedBy = SYSTEM, reason): slot deleted, `enqueueHub([slot.id], plan)`.

Vehicle window: changing `dropoff_at` / `pickup_at` is rejected while a slot of the work order would fall outside the new window.

A hub event that is deleted or moved may free a time that hub refused before. The hub event handler,
in the same transaction as `onBlockingChange`, re-enqueues the `REJECTED` slots of that person and day:

```
onHubEventRemovedOrMoved(ppUserId, days[])
    rejected := slots of ppUserId on days with slot_hub_sync.status = 'REJECTED'
    for each plan of rejected: enqueueHub(its rejected slot ids, plan)
    flushHub()                  // the worker retries them; no syncNow (no user waiting)
```

## 8. Hub sync (after commit and worker)

Hub calendar refuses overlapping events. Our validation catches an overlap with every hub event we already
know; one created in hub but not yet received by us is only found when we send the slot. The planner sees the
result per slot in the response of the action that changed it (`hubSync[]`) and on every later read.

| Setting | Value |
|---|---|
| External key of the hub event | `service-portal:slot:{slotId}`; create is idempotent by it, events are searchable by it |
| Time budget of `syncNow` | 3 s per request; what is not done by then stays `PENDING` for the worker |
| Circuit breaker | SmallRye Fault Tolerance on hub calls: open after 5 failures/timeouts in a row, half-open after 30 s; while open `syncNow` skips hub and returns `PENDING` at once |
| Status refresh | no polling or SSE: the planner sees `PENDING` → `SYNCED` / `REJECTED` / `FAILED` on its next normal load of the day |
| Hub error for an overlap | to confirm with hub (status + code); mapped to `HUB_OVERLAP` |
| Worker | `@Scheduled` every 2 s, `concurrentExecution = SKIP`, safe on several pods |
| Retry (network, timeout, 5xx) | 5 s, 30 s, 2 min, 10 min, 1 h, 1 h, 1 h → `DEAD` after 8 attempts |

`slot_hub_sync.status` as the planner sees it. **Only `REJECTED` opens a dialog**: it is the one outcome the planner
must decide on. `PENDING` and `FAILED` are solved by the system or by ops and show only as a mark on the slot,
so a slow or unavailable hub never interrupts planning.
A `REJECTED` found later by the worker (budget exceeded, hub was down) opens no dialog; the slot shows it on the
next load of the planner.

| Status | Meaning | Planner |
|---|---|---|
| `PENDING` | change not yet in hub (being sent or waiting for a retry) | mark only: clock; "calendar delayed" after a few minutes (`updated_at`) |
| `SYNCED` | hub shows the slot as it is | nothing |
| `REJECTED` | hub refused the time (`HUB_OVERLAP`); hub has no event for the slot | **dialog** after the action: move, remove or keep |
| `FAILED` | technical error after all retries, or a non-retryable 4xx; alert sent | mark only: red "calendar error"; ops gets the alert |

```
syncNow(outboxIds[])                                      // endpoint, after COMMIT, outside any transaction
    rows := claim(outboxIds)                              // PENDING -> PROCESSING, locked 60 s; rows a worker holds are skipped
    if hubCircuitOpen(): release(rows); return statuses (PENDING)   // hub down: Save does not wait at all
    deadline := now() + 3 s
    parallel for group in rows.groupBy(pp_user_id, day):  // order only matters inside one person-day
        processGroup(group, deadline)
    return [ { slotId, status, errorCode? } for each slot of outboxIds ]   // unfinished -> PENDING

worker()
    rows := claim(up to 20 due PENDING rows) + the other due rows of the same person-days
    for group in rows.groupBy(pp_user_id, day): processGroup(group, deadline = none)

processGroup(rows, deadline)
    sp_getapplock('hub-user-day:' + pp_user_id + ':' + day)   // one processor per person-day across pods
    ordered := deletes (slot gone), then updates (event exists), then creates (no event)
                                                          // frees times before they are taken again
    retry := []
    for r in ordered:
        if deadline passed: release(r); continue          // back to PENDING, the worker takes it
        outcome := reconcile(r.slot_id)
        if outcome == OVERLAP and rows after r remain: retry += r; continue
        finish(r, outcome)
    for r in retry:                                       // once, after the rest of the person-day was sent
        finish(r, reconcile(r.slot_id))
    // swap A 8–9 -> 9–10, B 9–10 -> 8–9: A is refused and its stale event deleted (see reconcile),
    // B moves to 8–9, the retry creates A at 9–10. A gets a new hub event id.

reconcile(slotId) -> SYNCED | OVERLAP | RETRYABLE | FAILED
    slot := load slot with its plan; sync := slot_hub_sync(slotId)
    key  := 'service-portal:slot:' + slotId
    if slot == null:                                      // slot deleted
        id := sync.hub_event_id ?? hub.findByExternalId(key)?.id     // find it if a crash lost the link
        if id: hub.delete(id)                             // 404 = done
        DELETE slot_hub_sync WHERE slot_id = slotId
        return SYNCED
    want := { externalId: key, type: work-order, participant: ppUserId(slot), start, end, workOrder }
    try:
        if sync.hub_event_id == null:
            e := hub.create(want)                         // returns the live event if the key already exists
            UPDATE slot_hub_sync SET hub_event_id = e.id
            if e != want: hub.update(e.id, want)          // an earlier attempt created an older state
        else:
            hub.update(sync.hub_event_id, want)           // 404 -> hub_event_id = NULL, create as above
        return SYNCED
    on hub overlap error:
        if sync.hub_event_id != null:                     // a refused update would leave the event at the
            hub.delete(sync.hub_event_id)                 // old time and, after a move, with the old member
            UPDATE slot_hub_sync SET hub_event_id = NULL
        return OVERLAP
    on network error, timeout, 5xx: return RETRYABLE
    on other 4xx:                   return FAILED

finish(row, outcome)
    // slot_hub_sync is written only if no newer PENDING row exists for the slot; otherwise it stays PENDING
    SYNCED    -> outbox DONE;  slot_hub_sync SYNCED, synced_at = now(), error NULL
    OVERLAP   -> outbox DONE;  slot_hub_sync REJECTED, error_code = 'HUB_OVERLAP' (no retry, no alert)
    RETRYABLE -> outbox back to PENDING with backoff (or closed if a newer PENDING row exists);
                 after the 8th attempt DEAD + alert, slot_hub_sync FAILED
    FAILED    -> outbox DEAD + alert; slot_hub_sync FAILED, error_code / error_message from hub
```

Response of every action that changes slots (`PUT slots`, `PUT schedules`, move time plan, move slot, delete time plan):

```json
{
  "timePlans": [ ... ],
  "hubSync": [
    { "slotId": 7922, "status": "SYNCED" },
    { "slotId": 7923, "status": "REJECTED", "errorCode": "HUB_OVERLAP" },
    { "slotId": 7924, "status": "PENDING" }
  ]
}
```

All or nothing applies to our database; hub is synced per slot. Three slots that pass our validation are all
saved; if hub refuses slot 2, slots 1 and 3 are in the calendar and slot 2 waits for the planner's decision.
An overlap we already know at save time rejects the whole request with `409 SLOT_NOT_FREE` and nothing is saved.
A delete is never refused by hub, so moves to a team, `DROP` and delete time plan only report `SYNCED` or `PENDING`.

---

## Example: plan, add time, schedule, move slots to a team

| Call | Time plans (`planned` / `unscheduled`) | Slots |
|---|---|---|
| `planTime(#1511, T2, Ivan, 15.10, 240)` | P1: 240 / 240 | – |
| `planTime(#1511, T2, Ivan, 15.10, 60)` (additional time) | P1: 300 / 300 | – |
| `scheduleBatch([P1: 08–10, 11–12])` | P1: 300 / 120 | 51: 08–10 (120) · 52: 11–12 (60) |
| `moveSlot(P1, slot 52, targetTeamId = T1)` | P1: 240 / 120 · P20 (T1, team): 60 / 60 | 51: 08–10 |
| `moveSlot(P1, slot 51, targetTeamId = T1)` | P1: 120 / 120 · P20: 180 / 180 (merged) | – |

---

## Supporting SQL

Table DDL: `capacity_schema.sql`, section 5b.

```sql
-- nightly consistency check (should return no rows)
SELECT p.id
FROM dbo.work_order_time_plan p
LEFT JOIN dbo.work_order_time_slot s ON s.time_plan_id = p.id
JOIN dbo.calendar c ON c.[day] = p.[day]
GROUP BY p.id, p.member_id, p.[day], p.planned_minutes
HAVING SUM(ISNULL(s.minutes, 0)) > CEILING(p.planned_minutes / 15.0) * 15
    OR (p.member_id IS NULL AND COUNT(s.id) > 0)
    -- slots on the plan's business day: UTC + offset of that day = local
    OR MIN(CAST(DATEADD(minute, c.utc_offset_minutes, s.start_at) AS date)) <> p.[day]
    OR MAX(CAST(DATEADD(minute, c.utc_offset_minutes, DATEADD(second, -1, s.end_at)) AS date)) <> p.[day];

-- planner side panel: still to schedule
SELECT p.id, p.work_order_id, p.planned_minutes,
       p.planned_minutes - ISNULL(SUM(s.minutes), 0) AS unscheduled_minutes
FROM dbo.work_order_time_plan p
LEFT JOIN dbo.work_order_time_slot s ON s.time_plan_id = p.id
WHERE p.member_id = @memberId AND p.[day] = @day
GROUP BY p.id, p.work_order_id, p.planned_minutes
HAVING ISNULL(SUM(s.minutes), 0) < FLOOR(p.planned_minutes / 15.0) * 15;   -- not fully scheduled

-- cutover v2 -> v3: migrate_v2_to_v3.sql (local v2 times -> UTC via dbo.calendar.utc_offset_minutes),
-- then redefine dbo.vw_planned_minutes over work_order_time_plan (capacity_schema.sql, section 5)
```
