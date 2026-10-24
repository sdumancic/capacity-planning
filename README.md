# Capacity Planning

Standalone Java 25 / Quarkus implementation of [Work Order Planning & Scheduling](docs/Work%20Order%20Planning%20%26%20Scheduling.md), with SQL Server, Liquibase and a browser planner served by Quarkus. The current database and scheduling policies follow [capacity_schema.sql](docs/capacity_schema.sql) and [time_plan_scheduling_pseudocode.md](docs/time_plan_scheduling_pseudocode.md). No frontend installation is needed.

## Start locally

Requirements: Java 25, Docker Compose and an x86-64 SQL Server container runtime. On Apple Silicon with Rancher Desktop, use **VZ with Rosetta enabled**. QEMU crashed SQL Server with exit139 on this workspace; enabling Rosetta resolved it and was verified against the live database. The setting restarts the backend and interrupts other containers:

```sh
rdctl set --virtual-machine.use-rosetta=true
```

Microsoft [does not support SQL Server container emulation for production](https://learn.microsoft.com/en-us/sql/linux/sql-server-linux-docker-container-deployment?pivots=cs1-bash&view=sql-server-ver17). For a supported environment use an x86-64 host, or set `DB_URL` to a reachable SQL Server instance.

```sh
cp .env.example .env
# Set the same independent local password in DB_PASSWORD and MSSQL_SA_PASSWORD.
docker compose up -d
docker compose ps -a
./mvnw quarkus:dev
```

Open **http://localhost:8080** and select **23 October 2026**, or 26 October for a 97-minute plan after the DST change.

Compose binds SQL Server Developer Edition to `127.0.0.1:1433`, waits for health and creates `capacity_planning` through a one-shot bootstrap. Quarkus applies Liquibase migrations and initializes calendar offsets. `.env` is ignored by Git and read by Compose and Quarkus dev mode. A local `.env` with a generated password may already exist; preserve it if the SQL volume was initialized. Changing `.env` does not change an existing SQL Server login password.

The volume survives `docker compose down`. Do not delete it to apply migrations. `docker compose down -v` deliberately deletes this project's database.

## Configuration

| Variable | Purpose |
| --- | --- |
| `CAPACITY_BUSINESS_ZONE` | Required IANA zone, e.g. `Europe/Zagreb`; missing/invalid fails startup. |
| `DB_PASSWORD` | Required JDBC login password. |
| `DB_USERNAME` | Local default `sa`. |
| `DB_URL` | Default SQL Server localhost:1433, database `capacity_planning`. |
| `MSSQL_SA_PASSWORD` | Compose SQL Server bootstrap password. |
| `CAPACITY_V3_ENABLED` | v3 feature flag: enabled in dev, disabled by default elsewhere. |

HTTP and database ports bind to loopback. The default JDBC URL trusts the local certificate. Use validated TLS, a dedicated runtime login, authentication and dealer-scoped authorization before exposing this beyond development. The starter has no identity provider; audit rows are not attributed to an authenticated actor.

Liquibase bootstrap chain in `src/main/resources/db/changelog.xml`:

- `001-schema.sql`: explicit minimal standalone prerequisites for Partner Profile, work orders and an empty legacy v2 table. These are local fixture contracts where the attachment assumes an existing upstream table.
- `002-capacity.sql`: byte-for-byte copy of the attached `docs/capacity_schema.sql`.
- `005-v3-cutover.sql`: makes `vw_planned_minutes` read v3 as `planned_minutes AS minutes`.
- `003-demo.sql`: local versioned-shift and scheduling data.
- `004-consistency.sql`: nightly consistency view.
- `006-user-capacity-corrections.sql`: two confirmed corrections to the supplied function: row-dependent conversion fails only for NULL calendar offsets, and shifts are unioned per person/day before subtracting blocks. The original NULL-guard constant failed even for initialized offsets; overlapping assignments could double-count attendance or blocked minutes.

This bootstrap chain replaces the earlier standalone schema. The local project database was explicitly dropped/recreated on request; an older database must also be deliberately recreated rather than bypassing Liquibase checksum checks. Demo data is gated by explicit `demo` context and automatically enabled **only in dev**. For packaged demo startup export `QUARKUS_LIQUIBASE_CONTEXTS=base,demo`, the required variables and `CAPACITY_V3_ENABLED=true` before `java -jar target/quarkus-app/quarkus-run.jar`. Liquibase seeds once; restarts do not duplicate rows.

## Dummy data

Dealer 1; teams 53/61; Ana (452), Marko (460), Ivan (470); orders 34084/1511/1505/1506. Weekdays 19–30 October 2026 have local 07:00–12:00 and 13:00–16:00 shifts. Approved/pending absences and an overlapping external event on 23 October demonstrate interval union. UTC event fixtures illustrate Europe/Zagreb. Marko has 80% productivity. Team/member plans include a 97-minute plan on 26 October; create slots in the planner.

The schema uses `pp_team.pp_dealer_id` and `pp_team_member.pp_member_id` (the person ID). `shift_version`, its days/breaks/segments and weekly ISO `shift_assignment` replace the old direct shift table. Daily versions resolve by `effective_from`, ignoring the informational version on the assignment. `user_absence` honors enabled/status; `calendar_event` and its participants block a person across dealers, excluding cancelled/deleted events. Service exceptions with no brand close or clip a day. Demo versions change on 26 October and the assignment deliberately still references the old version; 29 October has a shorter opening window.

There is no live upstream event ingestion. Break segments are already derived in the fixtures. Calendar dates 2020-01-01 through 2040-12-31 match the attached ISO dimension; API inputs outside the range are rejected. Offsets refresh on zone/tzdb change and missing offsets are repaired before serving the planner.

## Planner

The planner hierarchy is team → employee → work order. Each assigned work order has its own row, including fully unscheduled plans. Employee headers show shared capacity and accept team-work drops. Click a 15-minute cell in a work-order row to schedule that order directly. Click a slot to edit times or remove it. Same-assignee changes stay in drafts until **Save schedules**, which sends all changed plans atomically. Drag within a member to draft a time change; drag to another member to move a slot. Drop a team chip on an employee name to choose an amount, or on a time cell to propose a slot. Drop a slot on a team to convert its minutes to unscheduled team time.

Use the grip handle before an employee’s work-order label to drag the whole time plan to another employee or to a team, including the employee’s own team. It always moves the full planned duration, with preview/confirmation and KEEP/DROP handling for scheduled slots. Dropping a member plan on a time cell changes its assignee and preserves its scheduled times; it does not create a new slot at the drop time. Unsaved schedule drafts must be saved/discarded first. Click the handle for a keyboard-accessible destination dialog. Click the work-order label beside its timeline to change duration, move, delete or view history. Whole-plan moves use preview/confirmation, with planned-time-only handling for conflicts. Overbooking requires confirmation. Scheduling never changes booked minutes. Pending absences warn without blocking. All time conversion uses the API business zone independently of browser/JVM timezone. The visible timeline is 06:00–18:00; API scheduling also supports other valid shifts. Form controls provide keyboard alternatives and slot resizing.

## API

- `GET /v3/planner/config`: business zone and supported dates.
- `GET /v3/planner?dealerId=1&day=2026-10-23`: teams, capacity, free intervals, orders, plans, absences.
- `GET /v3/work-order-time-plans?day=2026-10-23&workOrderId=34084`: plans.
- `GET /v3/work-order-time-plans/{id}` and `GET /{id}/log`: detail/history.

Writes under `/v3/work-order-time-plans`:

| Method/path | Behavior |
| --- | --- |
| `POST /` | Add planned duration to new/existing assignee-day plan. |
| `PATCH /{id}` | Change planned duration. |
| `DELETE /{id}?expectedVersion=…` | Delete plan and slots. |
| `PUT /{id}/slots` | Save all slots, preserving supplied IDs. |
| `PUT /schedules` | Atomic final-state batch, maximum 50 plans. |
| `POST /{id}/move/preview` | Read-only move evaluation. |
| `POST /{id}/move` | Move/merge whole or partial plan. |
| `POST /{id}/slots/{slotId}/move` | Move slot to member or team. |

Example additive planning (read the actual version first):

```json
{"workOrderId":34084,"teamId":53,"memberId":452,"day":"2026-10-23","duration":"PT1H37M","expectedVersion":0}
```

All existing-plan writes require `expectedVersion`, including additive POST. Omit it only for new plans. Batch scheduling example, replacing IDs/versions with actual reads:

```json
{"timePlans":[{"timePlanId":1,"expectedVersion":1,"slots":[{"start":"2026-10-23T08:30:00Z","end":"2026-10-23T09:30:00Z"}]}]}
```

Validation covers vehicle windows, dealer ownership, enabled memberships, shifts/breaks/absences/events, person-wide overlap, rounding and capacity. JDBC version compare-and-set updates provide optimistic version checks, including slot-only saves. SQL application locks serialize writes per scope: every write takes its `team-day` and `user-day` locks in one sorted pass (no global lock). Plan row locks are taken in ID order and validation rereads state after scope locks. Future external writers must honor the same locks. Batch request overlaps return `400 SLOT_OVERLAP`, stale batches list all stale IDs, a foreign slot returns `404 TIME_PLAN_NOT_FOUND`, and vehicle-window violations use `409 OUTSIDE_VEHICLE_WINDOW`. Changed durations must be positive. Pending-warning intervals are clipped to the slot intersection.

## Integration boundaries

- **Hub (dummy integration):** slot changes write sync state and a state-based outbox in the planning transaction. After commit the request syncs its own slots (3 s budget, per person-day ordering and retry) and returns `SYNCED` / `REJECTED` / `FAILED` / `PENDING` per slot; a 2 s worker handles the rest with the spec's backoff, DEAD after 8 attempts, a circuit breaker and `ALERT` log lines. `DummyHubCalendarClient` keeps live events in memory only and simulates HTTP 409 with `capacity.hub.dummy-conflict-probability` (0 outside dev, 0.35 in dev). Details: `docs/time_plan_scheduling_pseudocode.md`, "Current dummy implementation". Audit columns (`created_by`, `modified_by`, `changed_by_user_id`) take the `X-User-Id` header, which the authenticating gateway must set; without it they stay NULL.
- **Upstream:** no event transport/consumer or work-order window-edit API exists. Future window editors must validate scheduled slots before narrowing a vehicle window. Reads derive current slot conflicts without silently deleting slots.
- **Legacy:** an empty minimal v2 prerequisite lets the supplied Release 1 DDL execute. The cutover view reads v3. No historical v2 data migration or projection is claimed.
- **Audit:** correlated snapshots survive deleted plans. Provision production append-only database permissions separately; local `sa` is a development bootstrap login.
- **Membership:** the attached SQL's independent `ROUND` per enabled membership is preserved. It can increase or decrease aggregate totals (31 shift minutes across two memberships become 16+16; capacity 8 across three becomes 3+3+3). Regression tests pin this supplied behavior; it is not conservation of person totals. Free time and locks remain per person across memberships.
- **Rounded slot move:** transfers `MIN(slot minutes, source planned minutes)` while preserving slot duration/identity. A 105-minute slot on a 97-minute plan moves 97 planned minutes. Converting it to a team removes the slot and deletes the zero-minute source. Merge and split paths are covered by live SQL API tests.

A nightly check at 02:00 in the business zone reports IDs from `dbo.vw_time_plan_inconsistency` without changing plans.

## Simulating calendar conflicts

Start dev mode normally: the dummy Hub rejects roughly 35% of create/update calls. To force
initial attempts to conflict, add `-Dcapacity.hub.dummy-conflict-probability=1` to the Quarkus
command; use `0` to disable rejection. Outcomes are per attempt, so a three-slot request may
have zero, one, or several rejections. After a simulated rejection, changing the start/end
of the same slot guarantees the next dummy create/update succeeds. This exemption is consumed
once; later edits use the configured probability again. Reusing the same time or creating a
new slot ID does not qualify. Simulation memory is local to the application instance and
resets on restart; it does not bypass real Hub responses or local availability validation.

The planner does not poll: the action that got a rejection opens the resolution dialog; a
rejection found later by the worker shows only as a mark on the next load. Rejected slots are
orange with a warning label. The dialog offers **Change time** (edit a draft, then Save schedules), **Remove slot**
(immediately saves the remaining slots with the current plan version), or **Keep locally**
(dismisses the warning for that slot/time for this page session; it does not create a Hub event).
Click a rejected slot to reopen the dialog. Closing the dialog also leaves the slot rejected.

`tests/hub-conflict-smoke.mjs` uses explicit API doubles to verify one rejection among three
slots from the action response, no dialog on reload, dismissal/reopening, changing time, removing only the rejected slot, current
versions, and mobile dialog layout. Serve the workspace on 127.0.0.1:8766 to run this smoke
function; it does not verify SQL Server or a live Hub.

## Verification

```sh
./mvnw -Dtest=PolicyTest,PlanningServicesTest test
node --test tests/time.test.mjs
node --check src/main/resources/META-INF/resources/planner.mjs
./mvnw -DskipTests package
```

`tests/ui-smoke.mjs` exports a Playwright smoke function using explicit API test doubles and the running app on 8081. It checks employee/work-order row counts, slot isolation, direct scheduling on the correct work-order row, empty employees, business-zone display/input, additive versions, schedule drafts/batching and mobile overflow. It does not verify the real API/database.

After real database startup and migration, run rollback-only SQL capacity assertions:

```sh
docker compose exec -T sqlserver sh -c '/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -b -d capacity_planning' < src/test/resources/db/capacity-regression.sql
```

Live SQL verification passes against the recreated database: six Liquibase changesets applied, three employees/four work orders/four initial plans, 7,671 ISO calendar days with no NULL offsets, and zero consistency violations. The rollback-only regression covers effective versions, duplicate/overlapping assignments, absences/events, cross-dealer participants, tombstones, dealer exceptions, productivity, supplied rounding and fail-closed NULL offsets.

```sh
python3 tests/live-smoke.py http://127.0.0.1:8081
python3 tests/live-move-smoke.py http://127.0.0.1:8081
```

These live checks create/delete only their own plans and refuse preexisting target data. Audit/outbox records intentionally remain. They verify atomic swaps and rollback, stale versions, rounded-slot moves, team conversion, split/merge, DROP, stable slot IDs, audit slot duration and two concurrent stale writers (one succeeds, one returns 409). They do not prove every concurrency or external integration scenario.

Verified: `./mvnw -q clean verify` (36 Java tests, zero failures, package built), three Node business-zone tests, planner syntax, SQL capacity regression and the real SQL-backed API smoke checks. Browser hierarchy/drag checks use explicit API doubles and are recorded separately from database proof.

For this verification session the application uses **http://localhost:8081** because port 8080 was already occupied. Start on that port with `./mvnw quarkus:dev -Ddebug=false -Dquarkus.http.port=8081`.

`tests/member-plan-drag-smoke.mjs` checks member-plan handles and drag events with explicit API doubles: full moves within/across teams, own-team unassignment, preview/cancel, conflict DROP, overbooking acceptance, same-assignee no-op, keyboard fallback and unsaved draft protection.
