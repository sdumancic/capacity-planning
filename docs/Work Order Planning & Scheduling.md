# Work Order Planning & Scheduling

Oct 23, 2026 · @Sanjin Dumancic

## Scope and terminology

Work order time is **planned** on a team or a member for a day, then **scheduled** into concrete time slots in the Planner. Planned time lives in `work_order_time_plan`, slots in `work_order_time_slot`; capacity is calculated from them, never stored.

`work_order_planned_time_v2` and its endpoints stay as they are and are marked deprecated. All work for this design is new code, merged to main continuously behind a feature flag; from the moment the flag is switched on, v3 is the only source of planned time (see Cutover from v2).

The capacity overview in the Creation Wizard keeps its layout; only the values change because of the new calculation.

| Term | Meaning |
| --- | --- |
| Planning | Assigning work order minutes to a team or a member for a day, without a time. |
| Scheduling | Attaching planned minutes to concrete slots (`start_at` / `end_at`) in the Planner. |
| Time plan | Planned time of one work order for one assignee (team or member) on one day. One row in `work_order_time_plan`. |
| Slot | A scheduled part of a member's time plan. One row in `work_order_time_slot`. |
| planned\_minutes | Total planned duration of the time plan. |
| unscheduled | `planned_minutes − SUM(slot minutes)`, calculated, never stored; shown as 0 when negative. A time plan is **fully scheduled** when the slots reach `planned_minutes` rounded down to 15 minutes (97 → 90). |

Examples use work order 34084 on 15.10.2026, team 53 (members 452 Ana, 460 Marko) and team 61 (member 470 Ivan). Time plans are written P1, P2, …

## Time model

Instants are UTC everywhere; only shifts and business days are local, and they are placed on the UTC timeline with the business zone of the deployment.

| What | Java | Database | API |
| --- | --- | --- | --- |
| Instant: slot, hub event, absence, audit | `Instant` | `DATETIME2(7)`, UTC | `"2026-10-28T06:00:00Z"` |
| Duration: planned, slot, unscheduled | `Duration` | `INT` minutes | `"PT1H37M"` |
| Business day: plan day, dealer non-working day | `LocalDate` | `DATE` | `"2026-10-28"` |
| Shift time of day | `LocalTime` | `TIME(0)` | `"07:00:00"` |

- **Business zone**: one per deployment and database (`capacity.business-zone`, IANA, e.g. `Europe/Sofia`), read from the same Helm variable as `quarkus.jackson.timezone` but as its own property and without a default, so a missing value stops the start instead of shifting capacity silently.
- **SQL** does not know the zone by name: `dbo.calendar.utc_offset_minutes` holds its offset at noon for every day, filled by `CalendarOffsetInitializer` at startup and recalculated when the zone changes (`dbo.app_setting`). No night shifts, so a DST switch at 02:00/03:00 never falls inside working hours and one offset per day is exact.
- **Java** uses the `ZoneId` directly: `LocalDate.ofInstant(start, zone)` for the slot's business day, `day.atTime(t).atZone(zone).toInstant()` for shift segments, `LocalDate.now(zone)` for today.
- **Jackson**: `write-dates-as-timestamps=false` and `write-durations-as-timestamps=false`, so `Instant` and `Duration` are ISO 8601 strings. DTOs use `Instant`, never `LocalDateTime` or `OffsetDateTime`.
- **Frontend: every time shown or entered goes through the business zone, never the browser's or the server's zone.** The API returns the zone once, in the planner configuration: `GET v3/planner/config` → `{ "businessZone": "Europe/Zagreb" }` (the value of `capacity.business-zone`). The browser's zone is wrong for a user outside the dealer's country or with a misconfigured laptop, and Next.js server rendering usually runs in UTC. The bug is invisible in testing while developers and dealers share one zone.

  | UTC from the API | Business zone | Shown |
  | --- | --- | --- |
  | `2026-10-23T12:00:00Z` | Europe/Zagreb, summer time (+2) | 14:00 |
  | `2026-10-26T12:00:00Z` | Europe/Zagreb, winter time (+1) | 13:00 |
  | `2026-10-23T12:00:00Z` | Europe/Sofia (+3) | 15:00 |

  Never add a fixed offset: format with the zone name, so the rule of that date applies.

  ```ts
  import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';
  const zone = config.businessZone;                                  // "Europe/Zagreb"
  
  // display: UTC -> business time
  formatInTimeZone('2026-10-23T12:00:00Z', zone, 'HH:mm');           // "14:00"
  
  // input: a click on the 09:00 cell of 23.10 -> UTC for the API
  fromZonedTime('2026-10-23T09:00:00', zone).toISOString();          // "2026-10-23T07:00:00.000Z"
  // wrong: new Date('2026-10-23T09:00') uses the browser's zone
  ```

  A planner day runs from midnight to midnight in the business zone, as in the backend (`calendar.utc_offset_minutes`). Day labels, the 15-minute grid and "today" are computed in that zone too.
- Times in the example tables below are shown in local time for readability; the API and the database carry UTC.

## Data model

```sql
CREATE TABLE dbo.work_order_time_plan (
    id              BIGINT IDENTITY NOT NULL PRIMARY KEY,
    work_order_id   INT             NOT NULL,
    team_id         INT             NOT NULL,   -- pp_team.id
    member_id       INT             NULL,       -- pp_team_member.id; NULL = planned on the team
    [day]           DATE            NOT NULL,   -- business day (LocalDate)
    planned_minutes INT             NOT NULL CHECK (planned_minutes >= 0),   -- API: Duration
    version         INT             NOT NULL DEFAULT 0,   -- JPA @Version, expectedVersion
    created_at      DATETIME2(7)    NOT NULL DEFAULT SYSUTCDATETIME(),   -- UTC
    created_by      INT             NULL,
    modified_at     DATETIME2(7)    NOT NULL DEFAULT SYSUTCDATETIME(),   -- UTC
    modified_by     INT             NULL
);

-- one time plan per work order, assignee and day
-- (NULL member_id counts as one value: one team plan per work order, team and day)
CREATE UNIQUE INDEX ux_wotp_assignee_day
    ON dbo.work_order_time_plan (work_order_id, team_id, member_id, [day]);
CREATE INDEX ix_wotp_team_day   ON dbo.work_order_time_plan (team_id, [day])   INCLUDE (member_id, planned_minutes);
CREATE INDEX ix_wotp_member_day ON dbo.work_order_time_plan (member_id, [day]) INCLUDE (planned_minutes) WHERE member_id IS NOT NULL;

CREATE TABLE dbo.work_order_time_slot (
    id           BIGINT IDENTITY NOT NULL PRIMARY KEY,
    time_plan_id BIGINT          NOT NULL REFERENCES dbo.work_order_time_plan (id),
    start_at     DATETIME2(7)    NOT NULL,   -- UTC (Instant)
    end_at       DATETIME2(7)    NOT NULL,   -- UTC (Instant)
    minutes      AS DATEDIFF(minute, start_at, end_at) PERSISTED,
    CONSTRAINT ck_wots_time CHECK (end_at > start_at)
    -- "slot lies on the plan's business day" depends on the zone: checked by the service and the nightly check
);
CREATE INDEX ix_wots_plan ON dbo.work_order_time_slot (time_plan_id) INCLUDE (start_at, end_at);
```

The link to hub calendar events lives in its own tables (`slot_hub_sync`, `hub_sync_outbox`), see Hub calendar integration. The change log is `work_order_time_plan_log`, see Change log.

**Nightly consistency check** (must return no rows):

```sql
SELECT p.id
FROM dbo.work_order_time_plan p
LEFT JOIN dbo.work_order_time_slot s ON s.time_plan_id = p.id
JOIN dbo.calendar c ON c.[day] = p.[day]
GROUP BY p.id, p.member_id, p.[day], p.planned_minutes
HAVING SUM(ISNULL(s.minutes, 0)) > CEILING(p.planned_minutes / 15.0) * 15  -- slots exceed the plan rounded up to 15
    OR (p.member_id IS NULL AND COUNT(s.id) > 0)                           -- team plan with slots
    -- slots on the plan's business day: UTC + offset of that day = local
    OR MIN(CAST(DATEADD(minute, c.utc_offset_minutes, s.start_at) AS DATE)) <> p.[day]
    OR MAX(CAST(DATEADD(minute, c.utc_offset_minutes, DATEADD(second, -1, s.end_at)) AS DATE)) <> p.[day];
```

## Invariants

The service keeps these on every write; the database enforces 1 and 4, the nightly check verifies 2, 3 and 6.

1. **One time plan per work order, assignee and day** (`work_order_id`, `team_id`, `member_id`, `day`). Time added to an assignee who already has the work order on that day goes into that time plan.
2. `SUM(slot minutes) ≤ planned_minutes` **rounded up to 15 minutes** (97 → 105). Slots on a 15-minute grid cannot always match the planned minutes exactly, so the next grid step above is allowed.
3. **A time plan is one day.** All its slots lie on its `day`. The planner shows one day and every action stays within it, so no action moves time between days.
4. A slot ends after it starts and does not cross midnight.
5. A slot starts and ends on a 15-minute boundary (enforced by the backend), inside one free interval of the member: no break, approved absence, hub event or other slot inside it. A pending absence does not block a slot; the response returns a warning.
6. Only member time plans have slots. Team time plans have none.
7. A slot lies inside the vehicle window of its work order: `dropoff_at ≤ start_at` and `end_at ≤ pickup_at`. Both are mandatory timestamps on the work order.
8. A time plan has one assignee. Moving a whole time plan changes its assignee; if the target already has a time plan of the same work order on that day, the two are merged.
9. Team and target member must be enabled and belong to the same dealer as the source.

## Capacity effect

Capacity is calculated by SQL functions from shifts, absences, hub events and planned minutes. Team booked = `SUM(planned_minutes)` of all time plans of the team (team and member plans); member booked = `SUM(planned_minutes)` of the member's plans. Scheduling never changes booked minutes, only planning and moves do. Booked counts planned minutes, not scheduled minutes: because of the 15-minute grid, the slots of a time plan may exceed its planned minutes by up to 14 minutes, and that difference is not counted as booked.

| Term | Meaning |
| --- | --- |
| shift | Shift minutes without breaks, within dealer working hours |
| blocked | Approved absences ∪ hub events inside the shift (overlaps counted once) |
| attended | shift − blocked |
| productivity | pp\_user.productivity\_percent, whole number 0–100, 100 for every employee until Partner Profile sends a value |
| capacity | FLOOR(attended × productivity / 100), per person before the split per team |
| booked | Minutes planned on work orders |
| available | capacity − booked (negative = overbooked) |
| pending absence | Pending absences inside the shift that are not already blocked; shown only, never reduces capacity or available |

| Action | Source member | Source team | Target member | Target team |
| --- | --- | --- | --- | --- |
| Plan on team | – | +m | – | – |
| Plan on member | +m | +m | – | – |
| Schedule (PUT slots): create, move, resize, remove slot | 0 | 0 | – | – |
| Team → team | – | −m | – | +m |
| Team → member, same team | – | 0 | +m | (same) |
| Team → member, other team | – | −m | +m | +m |
| Member → team, same team | −m | 0 | – | (same) |
| Member → team, other team | −m | −m | – | +m |
| Member → member, same team | −m | 0 | +m | (same) |
| Member → member, other team | −m | −m | +m | +m |
| Move slot to member, same team | −slot | 0 | +slot | (same) |
| Move slot to member, other team | −slot | −slot | +slot | +slot |
| Move slot to team, own team | −slot | 0 | – | (same) |
| Move slot to team, other team | −slot | −slot | – | +slot |
| Delete | −m | −m | – | – |

`m` = minutes moved, `+` = more booked (less available). Scheduling only splits planned minutes into slots and unscheduled time of the same plan, so booked does not change. `available` counts all planned minutes, scheduled or not; free time for slots counts only slots. A member can have `available` 0 and still a whole free day for slots.

**Over capacity is a warning, never a block.** Every action that adds minutes to a team or member (plan, move time plan, move slot, team chip to member) may exceed `available`. The frontend shows the warning with the target's available minutes for that day; the user confirms and the request carries `allowOverbooking: true`. Without it the backend answers `409 CAPACITY_EXCEEDED` with the same numbers, so the warning cannot be skipped by accident. The check runs only when the target team or member actually gains minutes: moving minutes from a member to their own team changes nothing for the team and needs no check.

## Concurrency and versioning

Every write on an existing time plan carries `expectedVersion` and is rejected with **409** when the time plan changed since the client loaded it.

- **Version** = `version` (`INT`) of the time plan, mapped with JPA `@Version`: Hibernate adds `WHERE version = ?` to every update and increments it; a conflict throws `OptimisticLockException`, mapped to 409. When only slots change, the service locks the plan with `LockModeType.PESSIMISTIC_FORCE_INCREMENT`, which locks the row and increments the version although the plan row itself is unchanged. No trigger and no manual touch are needed.
- **Locks**: inside the transaction the service takes `sp_getapplock` per person and day (user-day:\<pp\_user\_id>, one lock for all teams of the person, because free time is per person; team-day for team plans) for every day touched, source and target, always in sorted order to avoid deadlocks. Absence, hub event and shift handlers take the same locks.
- **Validation runs under the lock**, after re-reading the time plan, so two planners cannot book the same free slot.
- Responses return every changed time plan with its slots (with ids) and the new version.

**Merge.** A move into an existing time plan touches two time plans. The source `expectedVersion` is checked, both are locked in `id` order (no deadlock between two opposite moves), and the target's version advances too, so a client holding a stale target gets 409.

## Hub calendar integration

Each slot has one hub calendar event with the member as participant. For work order events this service is the master; hub calendar does not send them back, so they never count as blocked time. Hub calendar refuses overlapping events.

Sync goes through a state-based outbox: a message says "slot X changed, reconcile it", and a processor compares the slot's current state with hub calendar. The action that changed the slots processes its own messages **right after its commit, in the same HTTP request**, and returns the result per slot; the worker handles what is left (hub down, time budget exceeded, crash) and every change made by system handlers. Hub is never called inside the planning transaction.

Prerequisites in hub calendar (changed by our team): event type `work-order`; field `external_id`, filled with `service-portal:slot:{slotId}`; create is idempotent by it (an existing live event is returned, looked up **before** the overlap check); search by it; unique among live events only, so a key can be used again after a delete (hub rows are deleted physically, with a unique index on the key). Two creates with the same key can still race past the lookup; hub then catches the unique-index violation, reads the row by key and returns it as success. Hub is per country, like the database, so the key needs no country.

**All or nothing applies to our database; hub is synced per slot.** Three slots that pass our validation are all saved. If hub then refuses slot 2 (an event created in hub that we had not received yet), slots 1 and 3 are in the calendar and slot 2 waits for the planner. An overlap we already know at save time rejects the whole request with `409 SLOT_NOT_FREE` and nothing is saved.

| Slot in DB | Hub event id | Processor calls hub | Then |
| --- | --- | --- | --- |
| exists | none | create (idempotent by key) | save the id; if the returned event is older than the slot, update it |
| exists | exists | update (time, participant) | 404 → clear the id and create |
| missing | exists, or found by key | delete (404 = success) | delete the `slot_hub_sync` row |
| missing | none and not found | nothing | delete the `slot_hub_sync` row |

Hub refuses a create or update with an overlap → the slot becomes `REJECTED` (`HUB_OVERLAP`), no retry, no alert. **A refused update deletes the slot's existing event**: otherwise hub would keep the old time and, after a move to another member, the old member. Hub then shows nothing for the slot rather than something wrong; the next successful sync creates a new event (new hub id, same key). A delete is never refused.

Every change that creates, changes, moves or deletes a slot enqueues that slot: PUT slots, PUT schedules, move slot, move time plan, delete time plan. Slots are updated by id (diff), not deleted and re-inserted, so a drag updates the existing event. Slot times are stored in UTC, like hub calendar, so they are sent without conversion.

**Order per person and day.** A swap is valid in our model (A 8–9 → 9–10, B 9–10 → 8–9) but slot by slot hub would refuse A while B still holds 9–10. The processor therefore handles all messages of one person and day together, under an applock `hub-user-day:<pp_user_id>:<day>`: deletes first, then updates, then creates. A slot refused while other messages of that person-day are still waiting is tried once more at the end. In the swap A is refused, its stale event deleted, B moves to 8–9 and the retry creates A at 9–10. Different person-days are processed in parallel.

Status per slot (`slot_hub_sync.status`), returned by every read of slots and in `hubSync[]` of every action that changes slots:

| Status | Meaning | Planner shows |
| --- | --- | --- |
| `PENDING` | change not yet in hub (being sent, or waiting for a retry) | mark only: clock; "calendar delayed" after a few minutes (`updated_at`) |
| `SYNCED` | hub shows the slot as it is | nothing |
| `REJECTED` | hub refused the time (`HUB_OVERLAP`); hub has no event for the slot | dialog after the action: move, remove or keep |
| `FAILED` | technical error after all retries, or a non-retryable 4xx; alert sent | mark only: red "calendar error"; ops gets the alert |

**Only `REJECTED` opens a dialog.** It is the one outcome the planner must decide on. `PENDING` and `FAILED` are solved by the system (retry) or by ops (alert) and show only as a mark on the slot, so a slow or unavailable hub never interrupts planning; the planner works as if only `work_order_time_slot` counted.

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

The planner resolves a `REJECTED` slot with the normal actions; any change of the slot sends it again. A `REJECTED` slot stays in our database, occupies the member's time and counts as booked like any other slot. When a hub event of that person and day is deleted or moved, the hub event handler re-enqueues the person-day's `REJECTED` slots, so a slot whose time became free enters the calendar on its own.

### Tables

```sql
-- sync state per slot; survives the slot row so a delete can still reach hub
CREATE TABLE dbo.slot_hub_sync (
    slot_id       BIGINT         NOT NULL PRIMARY KEY,   -- work_order_time_slot.id (no FK on purpose)
    hub_event_id  NVARCHAR(64)   NULL,                   -- NULL = no event in hub (not created yet, or REJECTED)
    status        VARCHAR(10)    NOT NULL,               -- PENDING / SYNCED / REJECTED / FAILED
    error_code    VARCHAR(50)    NULL,                   -- HUB_OVERLAP, ...
    error_message NVARCHAR(1000) NULL,
    synced_at     DATETIME2(7)   NULL,
    updated_at    DATETIME2(7)   NOT NULL DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.hub_sync_outbox (
    id              BIGINT IDENTITY NOT NULL PRIMARY KEY,
    slot_id         BIGINT          NOT NULL,
    pp_user_id      INT             NOT NULL,   -- owner of the slot after the change (before it, for a delete)
    [day]           DATE            NOT NULL,
    status          VARCHAR(12)     NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / DONE / DEAD
    attempts        INT             NOT NULL DEFAULT 0,
    next_attempt_at DATETIME2(7)    NOT NULL DEFAULT SYSUTCDATETIME(),
    locked_until    DATETIME2(7)    NULL,
    last_error      NVARCHAR(2000)  NULL,
    created_at      DATETIME2(7)    NOT NULL DEFAULT SYSUTCDATETIME(),
    processed_at    DATETIME2(7)    NULL
);
-- at most one pending message per slot: several changes collapse into one
CREATE UNIQUE INDEX ux_hub_outbox_pending ON dbo.hub_sync_outbox (slot_id) WHERE status = 'PENDING';
CREATE INDEX ix_hub_outbox_due ON dbo.hub_sync_outbox (status, next_attempt_at);
CREATE INDEX ix_hub_outbox_user_day ON dbo.hub_sync_outbox (pp_user_id, [day]) WHERE status IN ('PENDING','PROCESSING');
```

Enqueue, in the same transaction as the planning change and after all its writes, for every slot created, changed or removed; the slot's `slot_hub_sync` row is set to `PENDING` in the same step:

```sql
-- a pending message exists: keep it, just make it due now (a new change should not wait for backoff)
UPDATE dbo.hub_sync_outbox WITH (UPDLOCK, HOLDLOCK)
SET next_attempt_at = SYSUTCDATETIME(), pp_user_id = @ppUserId
WHERE slot_id = @slotId AND status = 'PENDING';

-- none pending (none at all, or one PROCESSING that may have read the old state): add one
IF @@ROWCOUNT = 0
    INSERT dbo.hub_sync_outbox (slot_id, pp_user_id, [day]) VALUES (@slotId, @ppUserId, @day);
```

A PROCESSING message never absorbs a new change: the processor may already have read the old state, so a new PENDING row is added and runs after the current one finishes. attempts is not reset, so a broken hub still ends in DEAD. UPDLOCK, HOLDLOCK closes the race of two transactions both seeing no pending row; planner actions are already serialized by the member-day lock, the worker and system handlers are not.

### Processing

After commit (in the request): claim the request's own messages (PENDING → PROCESSING, locked 60 s; messages another processor holds are skipped), process them per person-day with a 3 s budget for the whole request, return `hubSync[]`. Messages not finished in time go back to PENDING, due now, and the worker continues; their slots are returned as `PENDING`. In Quarkus the resource method is not `@Transactional`: the service method commits, then the processor runs.

Worker: a scheduled poller inside the service (Quarkus `@Scheduled`, `concurrentExecution = SKIP`), safe to run on several pods.

1. Claim up to 20 due messages, plus the other due messages of the same person-days, in a short transaction; skip slots another processor holds; lock them for 60 s.
2. Process each person-day as above, outside any DB transaction.
3. Finish:
   - success → DONE, slot `SYNCED`;
   - overlap → DONE, slot `REJECTED`;
   - network error, timeout, 5xx → back to PENDING with backoff, slot stays `PENDING`; DEAD + alert after the 8th attempt, slot `FAILED`;
   - other 4xx → DEAD + alert, slot `FAILED`.

The slot's status is written only if no newer PENDING message exists for it; otherwise it stays `PENDING`.

```sql
WITH c AS (
    SELECT TOP (20) o.*
    FROM dbo.hub_sync_outbox o WITH (ROWLOCK, UPDLOCK, READPAST)
    WHERE ((o.status = 'PENDING' AND o.next_attempt_at <= SYSUTCDATETIME())
        OR (o.status = 'PROCESSING' AND o.locked_until < SYSUTCDATETIME()))   -- crashed processor
      AND NOT EXISTS (SELECT 1 FROM dbo.hub_sync_outbox p
                      WHERE p.slot_id = o.slot_id AND p.id <> o.id
                        AND p.status = 'PROCESSING' AND p.locked_until > SYSUTCDATETIME())
    ORDER BY o.id)
UPDATE c SET status = 'PROCESSING',
             locked_until = DATEADD(second, 60, SYSUTCDATETIME()),
             attempts = attempts + 1
OUTPUT inserted.id, inserted.slot_id, inserted.pp_user_id, inserted.[day], inserted.attempts;
```

Retry. A message going back to PENDING would break ux\_hub\_outbox\_pending when a newer change has already added a PENDING row for the same slot. That newer row reconciles the same slot, so the old one is closed instead:

```sql
UPDATE o
SET status          = CASE WHEN EXISTS (SELECT 1 FROM dbo.hub_sync_outbox p
                                        WHERE p.slot_id = o.slot_id AND p.status = 'PENDING')
                           THEN 'DONE' ELSE 'PENDING' END,
    next_attempt_at = DATEADD(second, @backoffSeconds, SYSUTCDATETIME()),
    last_error      = @error,
    locked_until    = NULL
FROM dbo.hub_sync_outbox o
WHERE o.id = @id;
```

| Setting | Value |
| --- | --- |
| Time budget after commit | 3 s per request; hub p95 is 200 ms per call, so about 15 sequential calls per person-day fit, person-days run in parallel |
| Circuit breaker | SmallRye Fault Tolerance on hub calls: open after 5 failures or timeouts in a row, half-open after 30 s. While open, the request skips hub and returns PENDING at once, so Save does not wait during a hub outage; the worker keeps retrying. |
| Status refresh | No polling or SSE. The planner sees later outcomes (PENDING → SYNCED / REJECTED / FAILED) on its next normal load of the day. A REJECTED found later by the worker opens no dialog; the slot shows it. |
| Worker poll interval | 2 s |
| Batch size | 20 messages, plus the rest of their person-days |
| Lock | 60 s |
| Backoff | 5 s, 30 s, 2 min, 10 min, 1 h, 1 h, 1 h; DEAD after 8 attempts (about 3 h) |
| Hub overlap error | to confirm with hub (status + code), mapped to `HUB_OVERLAP` |
| Alerts | any DEAD message; oldest PENDING older than 5 min |

Crash safety. If hub creates the event and the processor crashes before the id is saved, the retry sends the same key and hub returns the existing event instead of refusing it as an overlap with itself. A slot deleted before its id was saved is found by key and its event deleted.

## Change log

One log row per time plan touched by an operation, written in the same transaction. An operation that touches two time plans (split, merge, partial move) writes two rows with the same `correlation_id`, linked through `related_time_plan_id`. The ids are kept without a foreign key, so the log survives deleted and merged time plans.

```sql
CREATE TABLE dbo.work_order_time_plan_log (
    id                   BIGINT IDENTITY  NOT NULL PRIMARY KEY,
    correlation_id       UNIQUEIDENTIFIER NOT NULL,
    time_plan_id         BIGINT           NOT NULL,
    related_time_plan_id BIGINT           NULL,
    work_order_id        INT              NOT NULL,
    operation            VARCHAR(30)      NOT NULL,
    from_team_id         INT              NULL,
    from_member_id       INT              NULL,
    to_team_id           INT              NULL,
    to_member_id         INT              NULL,
    minutes              INT              NULL,
    before_state         NVARCHAR(MAX)    NULL CHECK (before_state IS NULL OR ISJSON(before_state) = 1),
    after_state          NVARCHAR(MAX)    NULL CHECK (after_state  IS NULL OR ISJSON(after_state)  = 1),
    reason               NVARCHAR(500)    NULL,
    initiated_by         VARCHAR(10)      NOT NULL,   -- USER / SYSTEM
    changed_by_user_id   INT              NULL,
    changed_at           DATETIME2(7)     NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE INDEX ix_wotp_log_plan       ON dbo.work_order_time_plan_log (time_plan_id, changed_at);
CREATE INDEX ix_wotp_log_work_order ON dbo.work_order_time_plan_log (work_order_id, changed_at);
```

| operation | Written by |
| --- | --- |
| PLAN | Plan time on team or member (new time plan, or added to the existing one of that day) |
| SCHEDULE | Save slots of a time plan |
| MOVE\_PLAN | Move a whole or partial time plan to another team or member |
| MOVE\_SLOT | Move one slot to another member or a team (split or merge) |
| CHANGE\_DURATION | Change `planned_minutes` |
| DELETE | Time plan deleted |

`before_state` / `after_state` hold a time plan snapshot:

```json
{
  "teamId": 53, "memberId": 452, "day": "2026-10-15",
  "plannedDuration": "PT3H", "unscheduledDuration": "PT1H",
  "slots": [ { "id": 2, "start": "2026-10-15T07:00:00Z", "end": "2026-10-15T09:00:00Z", "duration": "PT2H" } ]
}
```

Rules: append only (the service user gets INSERT and SELECT only), never used for business logic, system changes logged with `initiated_by = SYSTEM` and a `reason`.

In the examples the log tables show the key columns; snapshots are abbreviated as `planned / unscheduled / slots`.

## Creation Wizard

Both actions create a time plan without slots, or add to the existing one of that work order, assignee and day.

`POST v3/work-order-time-plans` — `{ workOrderId, teamId, memberId?, day, duration, allowOverbooking? }`

### Plan time on team

3 hours on team 53.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | NULL | 15.10 | 180 | 180 |

| log | correlation | time plan | related | operation | from | to | minutes | after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L1 | C1 | P1 | – | PLAN | – | team 53 | 180 | 180 / 180 / – |

### Plan time on member

3 hours on member 452 (team 53).

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 | 180 |

| log | correlation | time plan | related | operation | from | to | minutes | after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L1 | C1 | P1 | – | PLAN | – | 53 / 452 | 180 | 180 / 180 / – |

## Work Order Details

### Plan additional time to team or member

Same endpoint. It adds to the **existing time plan** of the same work order, assignee and day; a new time plan is created only when the assignee has none for that day. Starting state: member 452 has P1 (180) on 15.10.

Plan 60 more minutes on member 452 for 15.10:

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 → **240** | 180 → **240** |

| log | correlation | time plan | related | operation | from | to | minutes | after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L2 | C2 | P1 | – | PLAN | – | 53 / 452 | 60 | 240 / 240 / – |

The planner shows one line of 4:00 h, which matches the row. The additional time stays traceable as its own PLAN row in the log. Planned for 16.10 instead, the same request creates a new time plan P2, because a time plan is one day.

### Plan team time on member

Moves unscheduled team minutes to a member. Same operation as Move time plan: team → member below.

### Delete planned time

`DELETE v3/work-order-time-plans/{id}?expectedVersion=…` — deletes the time plan, its slots and, through the outbox, the hub events of the slots. Starting state: P1 on member 452 with one slot.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 | 60 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 2 | P1 | 09:00 | 11:00 | 120 |

Both rows deleted; hub event of slot 2 deleted via outbox.

| log | correlation | time plan | related | operation | from | to | minutes | after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L3 | C3 | P1 | – | DELETE | 53 / 452 | – | 180 | – |

The frontend lets the user delete team time only after the member times of that work order are deleted. This is a UI order, not a backend rule.

### Change duration

`PATCH v3/work-order-time-plans/{id}` — `{ expectedVersion, plannedDuration, allowOverbooking? }`. Shorter than the scheduled minutes is rejected (400) when the scheduled minutes exceed the new value rounded up to 15; the slots must be changed first. Logged as CHANGE\_DURATION.

## Planner: scheduling

One PUT endpoint saves **all slots** of a time plan every time; it handles create, drag within the same member, resize and unschedule. The user clicks a free cell (15-minute grid), expands the slot and saves. Moving work orders from the row menu (…) is removed; only drag and drop remains.

`PUT v3/work-order-time-plans/{id}/slots`

```json
{
  "expectedVersion": 7,
  "slots": [
    { "id": 2,    "start": "2026-10-15T07:00:00Z", "end": "2026-10-15T09:00:00Z" },
    { "id": null, "start": "2026-10-15T11:00:00Z", "end": "2026-10-15T12:00:00Z" }
  ]
}
```

Rules:

- Slots with `id` are updated, slots without `id` are inserted, existing slots missing from the list are deleted.
- `SUM(slot minutes) ≤ CEILING(planned_minutes / 15) × 15`, else 400. For 1h37m planned, 1h30m and 1h45m are both accepted without a warning; 2h is rejected. Less is a partial schedule, not a warning. Scheduling never changes `planned_minutes`.
- Slots must lie on the time plan's day, must not overlap each other and must lie in the member's free time (own slots of this time plan excluded), else 409 with the conflicting slots.
- Slots must lie between the work order's `dropoff_at` and `pickup_at`, else 409 `outside vehicle window` with the window in the response.
- Hub events: inserted, changed or removed slots are enqueued in the outbox and sent to hub right after the commit, in the same request; the response carries hubSync\[\] with the result per slot (see Hub calendar integration).

**Pending absences** do not block and do not change capacity. A slot that overlaps one is saved, and the response carries a warning the planner shows next to the slot. The same warnings come back from move time plan and move slot when the target is a member.

```json
"warnings": [ { "code": "SLOT_OVERLAPS_PENDING_ABSENCE", "slotId": 52,
                "absenceId": 4711, "from": "2026-10-15T11:00:00Z", "to": "2026-10-15T13:00:00Z" } ]
```

The planner read API returns absences per member and day with their `status` (`PENDING` / `APPROVED`), so the UI can draw pending ones differently.

Starting state: P1, 180 minutes on member 452, no slots.

### Step 1: first slot 09:00–11:00

Payload `[ {id: null, 09:00–11:00} ]`. Hub: create event for slot 2.

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 2 | P1 | 09:00 | 11:00 | 120 |

P1: planned 180, unscheduled **60**.

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L4 | C4 | P1 | SCHEDULE | 180 / 180 / – | 180 / 60 / 09–11 |

### Step 2: second slot 13:00–14:00

Payload `[ {id: 2, 09:00–11:00}, {id: null, 13:00–14:00} ]`. Slot 2 unchanged, slot 3 inserted. Hub: create event for slot 3.

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 2 | P1 | 09:00 | 11:00 | 120 |
| 3 | P1 | 13:00 | 14:00 | 60 |

P1: planned 180, unscheduled **0**.

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L5 | C5 | P1 | SCHEDULE | 180 / 60 / 09–11 | 180 / 0 / 09–11, 13–14 |

### Step 3: drag slot 2 to 10:00–12:00

Payload `[ {id: 2, 10:00–12:00}, {id: 3, 13:00–14:00} ]`. Slot 2 updated in place. Hub: update event of slot 2.

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 2 | P1 | **10:00** | **12:00** | 120 |
| 3 | P1 | 13:00 | 14:00 | 60 |

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L6 | C6 | P1 | SCHEDULE | 180 / 0 / 09–11, 13–14 | 180 / 0 / 10–12, 13–14 |

### Step 4: shrink slot 2 to 10:00–11:00

Payload `[ {id: 2, 10:00–11:00}, {id: 3, 13:00–14:00} ]`. The freed 60 minutes become unscheduled again. Hub: update event of slot 2.

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 2 | P1 | 10:00 | **11:00** | **60** |
| 3 | P1 | 13:00 | 14:00 | 60 |

P1: planned 180, unscheduled **60**.

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L7 | C7 | P1 | SCHEDULE | 180 / 0 / 10–12, 13–14 | 180 / 60 / 10–11, 13–14 |

Extending the slots beyond the rounded-up value of `planned_minutes` is rejected; the duration must be changed first.

### Step 5: unschedule everything

Payload `[]`. Slots 2 and 3 deleted, P1 unscheduled back to 180. Hub: delete both events.

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L8 | C8 | P1 | SCHEDULE | 180 / 60 / 10–11, 13–14 | 180 / 180 / – |

### Batch: several time plans in one request

Swapping slots of two work orders is impossible with two separate PUTs: the first one always fails, because the target time is still taken by the other time plan's old slot. The batch endpoint validates the final state of all time plans together. The single PUT is the batch with one time plan.

`PUT v3/work-order-time-plans/schedules`

```json
{
  "timePlans": [
    { "timePlanId": 1505, "expectedVersion": 4,
      "slots": [ { "id": 21, "start": "2026-10-15T09:00:00Z", "end": "2026-10-15T10:00:00Z" } ] },
    { "timePlanId": 1506, "expectedVersion": 3,
      "slots": [ { "id": 31, "start": "2026-10-15T08:00:00Z", "end": "2026-10-15T09:00:00Z" } ] }
  ]
}
```

- All or nothing: one transaction; one failing time plan fails the whole request.
- Locks for all touched person-days are taken first, in sorted order.
- Version checked per time plan; any stale one → 409 listing the changed time plans.
- Validation runs on the final state: slots of the request must not overlap each other, must lie in free time (time plans of the request excluded) and inside each work order's vehicle window.
- Log: one SCHEDULE row per time plan, same `correlation_id`. Outbox: one message per changed slot.
- The batch changes times only, never the assignee; moving a slot to another member stays move slot. Limit: 50 time plans per request.

**Response 200** — every time plan of the request with all its slots and its new version; the client replaces its local state with it.

```json
{
  "timePlans": [
    { "timePlanId": 1505, "version": 5,
      "plannedDuration": "PT1H", "unscheduledDuration": "PT0S",
      "slots": [ { "id": 21, "start": "2026-10-15T09:00:00Z", "end": "2026-10-15T10:00:00Z", "duration": "PT1H" } ] },
    { "timePlanId": 1506, "version": 4,
      "plannedDuration": "PT1H", "unscheduledDuration": "PT0S",
      "slots": [ { "id": 31, "start": "2026-10-15T08:00:00Z", "end": "2026-10-15T09:00:00Z", "duration": "PT1H" } ] }
  ],
  "warnings": [],
  "hubSync": [
    { "slotId": 21, "status": "SYNCED" },
    { "slotId": 31, "status": "SYNCED" }
  ]
}
```

**Errors** — nothing is saved on any error.

| Status | Code | When | Body |
| --- | --- | --- | --- |
| 400 | INVALID\_SLOT | end ≤ start, slot on another day than the time plan, not on the 15-minute grid | timePlanId, slot index |
| 400 | DURATION\_EXCEEDED | slots of a time plan exceed the rounded-up value of `planned_minutes` | timePlanId, planned and slot minutes |
| 400 | SLOT\_OVERLAP | two slots of the request overlap | both slots |
| 400 | TOO\_MANY\_TIME\_PLANS | more than 50 time plans | – |
| 400 | TEAM\_TIME\_PLAN | a time plan without a member | timePlanId |
| 404 | TIME\_PLAN\_NOT\_FOUND | unknown timePlanId or slot id not in that time plan | timePlanId, slot id |
| 409 | VERSION\_CONFLICT | a time plan changed since it was loaded | list of timePlanIds |
| 409 | SLOT\_NOT\_FREE | slot hits break, absence, hub event, another slot or lies outside the shift | timePlanId, slot, reason, blocking interval |
| 409 | OUTSIDE\_VEHICLE\_WINDOW | slot outside `dropoff_at` / `pickup_at` | timePlanId, slot, window |

**Example: swap two work orders of one member.** Separate PUTs fail here, because each target time is still taken by the other time plan.

Before:

| slot | time plan | work order | member | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- | --- | --- |
| 21 | P1505 | #1505 | 452 | 10:00 | 11:00 | 60 |
| 31 | P1506 | #1506 | 452 | 11:00 | 12:00 | 60 |

After (slots updated in place, ids kept):

| slot | time plan | work order | member | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- | --- | --- |
| 21 | P1505 | #1505 | 452 | **11:00** | **12:00** | 60 |
| 31 | P1506 | #1506 | 452 | **10:00** | **11:00** | 60 |

| log | correlation | time plan | operation | before | after |
| --- | --- | --- | --- | --- | --- |
| L30 | C30 | P1505 | SCHEDULE | 60 / 0 / 10–11 | 60 / 0 / 11–12 |
| L31 | C30 | P1506 | SCHEDULE | 60 / 0 / 11–12 | 60 / 0 / 10–11 |

| outbox slot\_id | status | Hub, right after commit |
| --- | --- | --- |
| 21 | PENDING | update hub event of slot 21 to 11:00–12:00 |
| 31 | PENDING | update hub event of slot 31 to 10:00–11:00 |

Both messages belong to one person-day and are sent together. Hub refuses slot 21 at 11:00–12:00 while slot 31 still holds it; slot 21's stale event is deleted, slot 31 moves to 10:00–11:00, and the retry at the end of the group creates slot 21's event at 11:00–12:00 (new hub id). The response returns both as `SYNCED`.

## Move time plan

Moving a time plan changes who does the work. A full move changes the assignee of the time plan; a partial move lowers `planned_minutes` of the source and gives the minutes to the target. **If the target already has a time plan of the same work order on that day, the minutes are merged into it**: its `planned_minutes` grow, moved slots get the target `time_plan_id` (slot ids kept, so hub events are updated, not recreated), and on a full move the source time plan is deleted. Otherwise the target gets a new time plan, or on a full move the source simply changes its assignee.

`POST v3/work-order-time-plans/{id}/move` — `{ expectedVersion, targetTeamId, targetMemberId?, duration?, slots?, slotHandling?, allowOverbooking? }` (no `duration` = full move).

| From → to | Full | Partial | Slots |
| --- | --- | --- | --- |
| Team → team | yes | yes (API only; the UI moves team → team in full) | team plans have none |
| Team → member | yes | yes | optional `slots` for the target (chip dropped into a time cell) |
| Member → team | yes | only if the plan has no slots | deleted with hub events |
| Member → member | yes | no | KEEP (default): kept, all must be free at the target, else 409 SLOT\_NOT\_FREE. DROP: all deleted with their hub events, only planned time moves |

`slotHandling` applies to member → member only: `KEEP` (default) or `DROP`. With `KEEP`, one slot that does not fit at the target is enough for `409 SLOT_NOT_FREE`; with `DROP` all slots of the time plan are deleted, including the ones that would fit, their hub events are deleted, and the planned minutes move to the target as unscheduled time. The choice is made in the move dialog from the preview below, not by retrying after a 409.

**Move preview.** Every move of a time plan opens a dialog first and is sent only after the user confirms. The dialog needs data the frontend does not have: the target's available time (`fn_team_member_capacity`) and the target person's free time across all their teams.

`POST v3/work-order-time-plans/{id}/move/preview` — same body as move, without `slotHandling` (slots included for a team chip dropped into a time cell). Read only: no lock, no write, no log, no outbox. Move slot has no preview.

```json
{
  "source": { "version": 4, "plannedDuration": "PT1H42M", "scheduledDuration": "PT30M", "unscheduledDuration": "PT1H12M" },
  "target": {
    "gains": true, "bookedDuration": "PT5H57M", "availableDuration": "PT2H", "fits": true,
    "existingTimePlan": null
  },
  "slots": {
    "outcome": "CONFLICT",
    "count": 1,
    "conflicts": [
      { "slotId": 7923, "start": "2026-10-15T07:00:00Z", "end": "2026-10-15T07:30:00Z",
        "blockedFrom": "2026-10-15T07:00:00Z", "blockedTo": "2026-10-15T07:15:00Z",
        "reason": "BOOKED", "workOrderId": 1511 }
    ],
    "pendingAbsenceWarnings": [],
    "durationExceeded": false
  }
}
```

| `slots.outcome` | When | Dialog |
| --- | --- | --- |
| `NONE` | no scheduled time | "Only the planned time moves." |
| `REMOVED` | member → team | "N scheduled times (h) will be removed, the hub events deleted." |
| `KEEP` | member → member, every slot free at the target; or team → member with slots that are all free | slots move with the plan; pending absence warnings shown |
| `CONFLICT` | member → member, at least one slot not free; or team → member with a slot that is not free (Move planned time only then sends the move without slots) | each slot with its time and what blocks it; "N scheduled times will be removed"; button **Move planned time only** |

`source.version` is sent back as `expectedVersion`; `source.unscheduledDuration` is what can be moved. `target.bookedDuration` is the target's planned time that day on all work orders and `target.availableDuration` its capacity minus booked, both before the move. `target.existingTimePlan` is the target's time plan of this work order that day, or `null`. `target.fits` and `target.existingTimePlan.plannedAfterDuration` are computed for the amount of this request (the whole planned time when no `duration` was sent).

Dialog rules: `target.gains = false` (member to own team, or the team part of a move inside one team) → "team capacity does not change" instead of available time; `target.fits = false` → warning and button "Move anyway"; `target.existingTimePlan` → "added to the existing time plan, X → Y"; a conflict shows the slot (`start`–`end`) and the blocking part (`blockedFrom`–`blockedTo`, reason, other work order), not only the blocking part.

Confirm sends move with `expectedVersion = source.version`, `slotHandling: DROP` when the outcome was `CONFLICT` (else `KEEP`), and `allowOverbooking: true` when the user accepted "does not fit". The preview is a snapshot: the move validates again under lock, and any `409` (`SLOT_NOT_FREE`, `CAPACITY_EXCEEDED`, `VERSION_CONFLICT`) makes the frontend call the preview again and show the dialog with the new state instead of an error. Preview and move share one evaluation in the backend (`MovePolicy` + `SlotValidator`); the move throws where the preview only reports.

**Team → member asks for the amount.** The dialog ("Move planned time to employee") has an h:mm field, prefilled with all remaining time, and a button "All remaining". The amount is not rounded; rounding to 15 minutes happens when the time is scheduled. The preview is called once when the dialog opens, without `duration`; the frontend computes the lines while the user types and never shows `target.fits` or `plannedAfterDuration`, which are for the whole amount:

| Line | Calculation |
| --- | --- |
| Planned time left on the team | `source.unscheduledDuration` − amount |
| Member planned time after | `target.bookedDuration` + amount |
| Member available time after | `target.availableDuration` − amount; negative → red, button "Move anyway" with `allowOverbooking: true` |
| Existing time plan after (only when `target.existingTimePlan` is not null) | `target.existingTimePlan.plannedDuration` + amount |

The amount must be > 0 and ≤ `source.unscheduledDuration`, else the button is disabled (the backend answers 400 as well). Amount = all remaining → the move is sent without `duration` (full move: the same time plan gets the member). Less → partial move with `duration`: a new member time plan, or added to `target.existingTimePlan` if the member already has the work order that day ("added to the existing X h"). A team chip dropped into a time cell asks for no amount: the drop sizes the slot.

Other moves are full in the UI: team → team and member → team move the whole time plan; member → member is full by rule. The API keeps partial moves for team → team and for member → team without slots, but the UI does not call them.

### Team → team

Starting state: P7, 5 hours on team 53.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P7 | 53 | NULL | 15.10 | 300 | 300 |

**Full move to team 61** — update of the time plan.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P7 | **61** | NULL | 15.10 | 300 | 300 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L10 | C10 | P7 | – | MOVE\_PLAN | team 53 | team 61 | 300 |

**Partial move of 2 hours to team 61** — update of the source, insert of a new time plan (or +120 on team 61's existing plan of #34084 that day).

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P7 | 53 | NULL | 15.10 | **180** | **180** |
| **P8** | 61 | NULL | 15.10 | 120 | 120 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L11 | C11 | P7 | P8 | MOVE\_PLAN | team 53 | team 61 | 120 |
| L12 | C11 | P8 | P7 | MOVE\_PLAN | team 53 | team 61 | 120 |

### Team → member

Team plans are never scheduled, so only unscheduled minutes move. The target member can be in the same team or another team of the same dealer. Starting state: P7, 5 hours on team 53.

**Partial, member 452 in the same team (2 hours)**

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P7 | 53 | NULL | 15.10 | **180** | **180** |
| **P8** | 53 | 452 | 15.10 | 120 | 120 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L13 | C13 | P7 | P8 | MOVE\_PLAN | team 53 | 53 / 452 | 120 |
| L14 | C13 | P8 | P7 | MOVE\_PLAN | team 53 | 53 / 452 | 120 |

**Partial, member 470 in team 61 (2 hours)** — the new time plan belongs to team 61; no extra team plan.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P7 | 53 | NULL | 15.10 | **180** | **180** |
| **P8** | **61** | 470 | 15.10 | 120 | 120 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L15 | C15 | P7 | P8 | MOVE\_PLAN | team 53 | 61 / 470 | 120 |
| L16 | C15 | P8 | P7 | MOVE\_PLAN | team 53 | 61 / 470 | 120 |

**Full** — the time plan gets `member_id` (and `team_id` if the team changes); same time plan, no new row. If the member already has #34084 that day, the minutes are merged into that plan and P7 is deleted.

**Team → member with a slot** (team chip dropped into a member's time cell) — move and schedule in one transaction. `move` takes optional `slots` when the target is a member:

```json
{ "expectedVersion": 3, "targetTeamId": 53, "targetMemberId": 460,
  "slots": [ { "start": "2026-10-15T11:00:00Z", "end": "2026-10-15T13:00:00Z" } ] }
```

The frontend sizes the slot from the drop point: as much as fits into the member's free interval, at most the team plan's unscheduled minutes, end rounded to 15 minutes. All fits → full move (the team plan becomes the member's and gets the slot). Less fits → partial move: the slot's minutes go to the member's time plan, the rest stays on the team. Validation (free time, vehicle window, same dealer, enabled member) runs before any write; on failure nothing changes.

Example: chip of 3 h (P3) dropped on member 460 at 13:00, free until 15:00.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P3 | 53 | NULL | 15.10 | **60** | **60** |
| **P4** | 53 | 460 | 15.10 | 120 | 0 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 10 | P4 | 13:00 | 15:00 | 120 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L40 | C40 | P3 | P4 | MOVE\_PLAN | team 53 | 53 / 460 | 120 |
| L41 | C40 | P4 | P3 | MOVE\_PLAN | team 53 | 53 / 460 | 120 |
| L42 | C40 | P4 | – | SCHEDULE | – | – | – |

Hub: the event of slot 10 is created for member 460 right after commit. Without `slots` (chip dropped on the member's name) the call is a plain team → member move.

### Member → team

Starting state: P1, 3 hours on member 452, 2 hours scheduled.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 | 60 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 7922 | P1 | 09:00 | 11:00 | 120 |

**Full move to team 61** — the UI confirms that the slot will be removed. Slot deleted, hub event of 7922 deleted, the time plan becomes a team plan.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | **61** | **NULL** | 15.10 | 180 | **180** |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L17 | C17 | P1 | – | MOVE\_PLAN | 53 / 452 | team 61 | 180 |

Moving to the member's own team works the same way with `team_id` unchanged. If the target team already has a plan of #34084 that day, the minutes are merged into it and P1 is deleted.

### Member → member

Starting state: P1, 3 hours on member 452, two slots.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 | 30 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 7922 | P1 | 09:00 | 11:00 | 120 |
| 7923 | P1 | 13:00 | 13:30 | 30 |

With `slotHandling: KEEP` (default) every slot must be free at the target member (shift, breaks, absences, hub events, other slots). Any conflict → `409 SLOT_NOT_FREE` listing all conflicting slots, nothing changes. Hub: update the participant of both events.

**Same team, member 460** — one row changes, the slots follow their time plan.

```sql
UPDATE dbo.work_order_time_plan SET member_id = 460, version = version + 1, modified_at = SYSUTCDATETIME() WHERE id = @p1 AND version = @expected;
```

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | **460** | 15.10 | 180 | 30 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L18 | C18 | P1 | – | MOVE\_PLAN | 53 / 452 | 53 / 460 | 180 |

**Other team, member 470 in team 61**

```sql
UPDATE dbo.work_order_time_plan SET team_id = 61, member_id = 470, version = version + 1, modified_at = SYSUTCDATETIME() WHERE id = @p1 AND version = @expected;
```

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | **61** | **470** | 15.10 | 180 | 30 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L19 | C19 | P1 | – | MOVE\_PLAN | 53 / 452 | 61 / 470 | 180 |

If the target already has a plan of #34084 that day, P1's minutes and slots are merged into it (`UPDATE work_order_time_slot SET time_plan_id = …`) and P1 is deleted. The merged plan must still satisfy scheduled ≤ ceil15(planned), else `400 DURATION_EXCEEDED` (two plans of 50 min with a 60-min slot each would merge into 100 min with 120 min of slots). If the same person is a member of both teams, the free-time check runs per person and excludes the slots being moved.

**Slots do not fit — move planned time only.** Member 460 has an approved absence 13:00–15:00 on 15.10, so slot 7923 (13:00–13:30) does not fit; 7922 would.

```json
POST v3/work-order-time-plans/P1/move/preview
{ "targetTeamId": 53, "targetMemberId": 460 }

200
{
  "source": { "version": 4, "plannedDuration": "PT3H", "scheduledDuration": "PT2H30M", "unscheduledDuration": "PT30M" },
  "target": { "gains": true, "bookedDuration": "PT4H", "availableDuration": "PT4H", "fits": true, "existingTimePlan": null },
  "slots": {
    "outcome": "CONFLICT",
    "count": 2,
    "conflicts": [
      { "slotId": 7923, "start": "2026-10-15T11:00:00Z", "end": "2026-10-15T11:30:00Z",
        "blockedFrom": "2026-10-15T11:00:00Z", "blockedTo": "2026-10-15T11:30:00Z", "reason": "ABSENCE" }
    ],
    "pendingAbsenceWarnings": [],
    "durationExceeded": false
  }
}
```

The dialog lists slot 7923 and says "2 scheduled times (2:30 h) will be removed"; the user confirms Move planned time only:

```json
POST v3/work-order-time-plans/P1/move
{ "expectedVersion": 4, "targetTeamId": 53, "targetMemberId": 460, "slotHandling": "DROP" }
```

```sql
DELETE dbo.work_order_time_slot WHERE time_plan_id = @p1;   -- 7922, 7923 -> outbox: hub events deleted
UPDATE dbo.work_order_time_plan SET member_id = 460, version = version + 1, modified_at = SYSUTCDATETIME() WHERE id = @p1 AND version = @expected;
```

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | **460** | 15.10 | 180 | **180** |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L20 | C20 | P1 | – | MOVE\_PLAN | 53 / 452 | 53 / 460 | 180 |

The log row's before state holds both slots, so the removed times stay traceable. If 460 already has a plan of #34084 that day, the 180 minutes are added to it and P1 is deleted; that plan's own slots stay.

## Move slot

Dragging one slot moves only that time, not the work order, and always within the planner's day. Within the same member it is a scheduling change (PUT slots). To another member the slot leaves its time plan and joins the target's time plan of the same work order on that day, or a new time plan when the target has none; the source time plan shrinks by the slot.

`POST v3/work-order-time-plans/{id}/slots/{slotId}/move` — `{ expectedVersion, targetMemberId, start, end, allowOverbooking? }` or `{ expectedVersion, targetTeamId, allowOverbooking? }`

- The slot keeps its duration; the target time must be free at the target member, else 409. When the slot joins an existing time plan of the target, that plan must still satisfy scheduled ≤ ceil15(planned), else 400 DURATION\_EXCEEDED.
- The unscheduled remainder stays with the source member.
- If the slot is the whole time plan (one slot, unscheduled 0) and the target has no plan of that work order that day, the time plan moves as a whole, no split.
- **To a team** (`targetTeamId`, no time): the slot is removed, its minutes leave the source time plan and go to the team's plan of that work order and day, a new one if none exists (see D below). Its hub event is deleted.
- Over capacity at the target is a warning (`allowOverbooking`), see Capacity effect.
- Hub: update the event's participant and time; delete it when the target is a team.

Starting state for all cases: P1 on member 452, 30 minutes unscheduled, two slots. The user drags slot 7922.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | 180 | 30 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 7922 | P1 | 09:00 | 11:00 | 120 |
| 7923 | P1 | 13:00 | 13:30 | 30 |

### A) Within the same member: 09–11 to 14–16

`PUT .../work-order-time-plans/P1/slots` with `[ {id: 7922, 14:00–16:00}, {id: 7923, 13:00–13:30} ]`. Slot 7922 updated in place, hub event updated.

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 7922 | P1 | **14:00** | **16:00** | 120 |
| 7923 | P1 | 13:00 | 13:30 | 30 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L20 | C20 | P1 | – | SCHEDULE | – | – | – |

### B) To member 460 in the same team: 10–12

P1 shrinks to 60. Slot 7922 moves to the new time plan P11 (row kept, so its hub event is updated, not recreated).

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | **60** | 30 |
| **P11** | 53 | **460** | 15.10 | 120 | 0 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| 7922 | **P11** | **10:00** | **12:00** | 120 |
| 7923 | P1 | 13:00 | 13:30 | 30 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L21 | C21 | P1 | P11 | MOVE\_SLOT | 53 / 452 | 53 / 460 | 120 |
| L22 | C21 | P11 | P1 | MOVE\_SLOT | 53 / 452 | 53 / 460 | 120 |

### C) To member 470 in team 61: 10–12

Same as B, the new time plan belongs to team 61.

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1 | 53 | 452 | 15.10 | **60** | 30 |
| **P11** | **61** | **470** | 15.10 | 120 | 0 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L23 | C23 | P1 | P11 | MOVE\_SLOT | 53 / 452 | 61 / 470 | 120 |
| L24 | C23 | P11 | P1 | MOVE\_SLOT | 53 / 452 | 61 / 470 | 120 |

**Merge.** If member 460 already has time plan P9 of the same work order on 15.10 (planned 30, unscheduled 30), slot 7922 joins P9 instead of creating P11: P9 `planned_minutes` 30 → 150, unscheduled stays 30, slot 7922 gets `time_plan_id` P9. The log rows link P1 and P9.

### D) To a team

Ivan (team Toyota 2) has #1511 with 5 h; the slot 11:00–12:00 is dropped on team Toyota 1. The dialog shows: slot removed and its hub event deleted, 1 h stays as planned time of the team, #1511 stays with Ivan with 4 h. If Toyota 1 has less than 1 h available, the dialog shows a warning and the user can still confirm.

`POST .../work-order-time-plans/P1511/slots/52/move` — `{ expectedVersion, targetTeamId: T1, allowOverbooking: true }`

| plan | team\_id | member\_id | day | planned\_minutes | unscheduled |
| --- | --- | --- | --- | --- | --- |
| P1511 | T2 | Ivan | 15.10 | 300 → **240** | 0 |
| **P20** | **T1** | NULL | 15.10 | 60 | 60 |

| slot | time plan | start\_at | end\_at | minutes |
| --- | --- | --- | --- | --- |
| ~~52~~ | P1511 | 11:00 | 12:00 → deleted | 60 |

| log | correlation | time plan | related | operation | from | to | minutes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L45 | C45 | P1511 | P20 | MOVE\_SLOT | T2 / Ivan | team T1 | 60 |
| L46 | C45 | P20 | P1511 | MOVE\_SLOT | T2 / Ivan | team T1 | 60 |

Hub: the event of slot 52 is deleted right after commit. Capacity on 15.10: Ivan −60, Toyota 2 −60, Toyota 1 +60. Dropped on Ivan's own team (Toyota 2) the team total does not change and no capacity check runs. If Toyota 1 already has a time plan of #1511 on 15.10, its `planned_minutes` grow by 60 instead of creating P20.

## Frontend: which action for which gesture

The rule: a change of **time** is SCHEDULE and goes into the draft, saved together on Save; a change of **assignee** is MOVE: a time plan move is sent after the move dialog (preview) is confirmed, a slot move on drop; after either the frontend reloads the affected rows.

| Gesture | Action | Endpoint | Sent |
| --- | --- | --- | --- |
| New slot, resized or dragged slot, same member (one or more work orders) | SCHEDULE | `PUT /schedules` (each changed time plan with all its slots) | on Save, all drafts together; a dialog opens only if hubSync has a REJECTED slot (move, remove or keep); PENDING and FAILED only mark the slot |
| Slot dragged to another member (same or other team) | MOVE\_SLOT | `POST /{id}/slots/{slotId}/move` with `targetMemberId`, `start`, `end` | on drop |
| Slot dragged to a team | MOVE\_SLOT to team | `POST /{id}/slots/{slotId}/move` with `targetTeamId` | on drop, after the confirmation dialog |
| Member's work order row dragged to a team | MOVE\_PLAN, full | `POST /{id}/move` with `targetTeamId` | after the move dialog ("N scheduled times will be removed") |
| Member's work order row dragged to another member | MOVE\_PLAN member → member, full | POST /{id}/move with targetMemberId and slotHandling from the dialog (KEEP, or DROP for Move planned time only) | after the move dialog; if slots do not fit it lists the conflicts and offers "Move planned time only — N scheduled times will be removed" |
| Team chip dropped on a member's name | MOVE\_PLAN team → member | `POST /{id}/move` with `targetMemberId` (+ `duration` from the amount dialog when less than all remaining) | after the move dialog |
| Team chip dropped into a member's time cell | MOVE\_PLAN + SCHEDULE | `POST /{id}/move` with `targetMemberId` and `slots` | after the move dialog |
| Team chip dropped on another team | MOVE\_PLAN team → team | `POST /{id}/move` with `targetTeamId` (`full move only`) | after the move dialog |

Every MOVE\_PLAN gesture opens the move dialog from `POST /{id}/move/preview` and sends the move only on confirm (see Move time plan). MOVE\_SLOT has no preview and is sent on drop.

Paths are relative to `v3/work-order-time-plans`. Every MOVE that pushes the target over capacity shows a warning first and is sent with `allowOverbooking: true` after the user confirms.

Drafts and moves do not mix: before a MOVE on a time plan with unsaved drafts, the frontend saves or discards those drafts, otherwise the move fails with 409 (stale version). A planner row (day · assignee · work order) is exactly one time plan, so dragging the row moves that time plan.

## Conflicts from external changes

An absence, a hub calendar event or a new shift version can land on an existing slot. The system never moves or deletes the planner's slot on its own; it marks the conflict and the planner resolves it.

- After processing absence, hub event and shift messages, the handler checks the affected person-days under the same lock and lists slots that are no longer inside free time.
- The planner sees them as conflicts and reschedules, moves or unschedules them with the normal actions.
- A member disabled in a team keeps its time plans; they show as conflicts (capacity 0, available negative) until moved.
- **Vehicle window**: `dropoff_at` / `pickup_at` cannot be changed so that a scheduled slot of the work order falls outside the new window. The work order save is rejected until those slots are unscheduled; no conflict state exists for the vehicle window.
- The system never changes slots on its own, so the log has no system unschedule operation. If an automatic unschedule is ever introduced, it gets its own log operation (with `initiated_by = SYSTEM` and the reason) and deletes the slot's hub event.

**Pending → approved.** When a pending absence is approved, it starts to block: slots that overlap it become conflicts through the same check, nothing is deleted. Pending → rejected or cancelled changes nothing, because a pending absence never blocked.

Example: absence 13:00–14:00 arrives for member 452. Slot 7923 (13:00–13:30) becomes a conflict; nothing changes in the tables.

## Cutover from v2

- **Trunk + feature flag, no long-lived branch.** v3 code is merged to main continuously; the v3 tables exist in production, empty, and the v3 endpoints and planner are hidden behind the flag. `work_order_planned_time_v2` and its endpoints stay unchanged and are marked deprecated.
- **Release 1** (capacity from shifts, absences, hub events) ships first: it reads planned minutes through the view `dbo.vw_planned_minutes`, defined over v2 until the switch and over `work_order_time_plan` after it; the capacity functions do not change. `team_capacity` is no longer filled from Release 1 on.
- **Switch**: a one-time migration (`migrate_v2_to_v3.sql`) copies v2 into v3. All rows of a work order, team, member and day are summed into one time plan. Valid member rows with `start_at` become slots; team rows and invalid times keep their minutes as unscheduled and are listed for re-scheduling. v2 stores local time, so slot times and audit timestamps are converted to UTC with `dbo.calendar.utc_offset_minutes`. Then `vw_planned_minutes` is redefined over v3 and the flag is switched on. From then on v2 is frozen and read only.
- **No dual writes.** The frontend calls only v3 after the switch; the old write endpoints have no caller and are removed later. Nobody else writes v2.
- **v2 projection only if needed.** v3 keeps v2 up to date (same transaction, one direction v3 → v2) only while something still reads v2 (other readers, see open questions) or as short-term rollback insurance. Without it, switching the flag off after the switch loses the changes made in v3 since then.

## API summary

Base path `v3/work-order-time-plans`. The v2 endpoints under `v2/work-order-planned-times` are deprecated.

| Method | Path | Action | Log operation |
| --- | --- | --- | --- |
| POST | `/` | Plan time on team or member (new or existing time plan of that day) | PLAN |
| PATCH | `/{id}` | Change `planned_minutes` | CHANGE\_DURATION |
| DELETE | `/{id}` | Delete time plan, its slots and hub events | DELETE |
| PUT | `/{id}/slots` | Save all slots of one time plan | SCHEDULE |
| PUT | `/schedules` | Save slots of several time plans in one transaction | SCHEDULE × n |
| POST | `/{id}/move` | Move time plan (full or partial), optional slots for a member target, slotHandling KEEP/DROP for member → member | MOVE\_PLAN × 1–2 (+ SCHEDULE) |
| POST | /{id}/move/preview | Read-only evaluation of a move for the move dialog: capacity, merge, what happens to the slots, conflicts | – |
| POST | `/{id}/slots/{slotId}/move` | Move one slot to another member or to a team | MOVE\_SLOT × 1–2 |

All writes on existing time plans take `expectedVersion` and return the changed time plans with their new version.

## Open questions

- Who else reads `work_order_planned_time_v2` (work order details, reports, other services, BI)? They must switch to v3 at the cutover or get a compatibility view.
- Which hub event statuses count as blocked time (e.g. PENDING, CANCELLED codes)?
- Hub calendar: status and code of the overlap error (mapped to `HUB_OVERLAP`). Latency is confirmed (p95 200 ms); external key, idempotent create and search by key are being added to hub by our team.
- Which service edits `dropoff_at` / `pickup_at`, and how does it check scheduled slots before saving?
- Productivity (separate task, decided): `pp_user.productivity_percent TINYINT NOT NULL DEFAULT 100`, whole number 0–100, filled by the existing pp\_user Service Bus event once Partner Profile sends it (field missing in the event → value kept; outside 0–100 → dead-letter). Capacity = FLOOR(attended × productivity / 100) per person in `fn_user_capacity`; the functions also return `attended_minutes` and `productivity_percent`. Slots, free time, booked and hub are unchanged; 0 % is a valid value (capacity 0, every plan is a capacity warning). Still open: whether reports need history. Without it a change also recalculates past days.
- Employee in two teams: capacity split equally per membership (today), member rows per person with team totals split (proposed), or a configured share per membership? Free time and locks are already per person.
- Which absence status codes does Partner Profile send (PENDING, APPROVED, REJECTED, CANCELLED)?
