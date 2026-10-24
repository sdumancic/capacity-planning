# Schema and scheduling pseudocode alignment

**Goal:** Replace the local project database with the supplied capacity schema and align API behavior with its pseudocode, preserving the current planner response contract.

**Authority:** `docs/capacity_schema.sql`, `docs/time_plan_scheduling_pseudocode.md`.

**Architecture:** Retain transactional application services, pure domain policies and JDBC output adapter. SQL capacity and shift resolution use the supplied dealer/range functions. Database reset is explicitly authorized for `capacity_planning` only. No Git repository is present.

## Tasks

- [x] Replace Liquibase schema/functions with attached SQL. Add explicit standalone prerequisites for Partner Profile, work orders and empty legacy v2; activate v3 view. Seed realistic versioned shifts/assignments, exceptions, absences and calendar participants. Calendar range 2020-01-01 through 2040-12-31 matches attached dimension.
- [x] Adapt JDBC queries to pp_dealer_id, pp_member_id, versioned shifts, user_absence and calendar_event/participants. Capacity maps fn_team_capacity_rows(dealer,from,to), preserving frontend fields. SQL regression exercises new signatures, effective version, tombstones, exception windows and person-wide blocking.
- [x] Align planning policies: exact machine error codes, final-state batch validation, positive changed duration, rounded slot transfer MIN(slot,planned), slot handling restrictions, clipped pending warnings, scheduled audit amounts, locking/enqueue transaction semantics. Preserve ISO Duration public request contract; pseudocode integer minutes are internal values.
- [x] Stop only project app, drop/recreate only capacity_planning, run Liquibase/demo startup and verify SQL/API/browser behavior. Run unit tests and real SQL smoke, including moves and rounding. Update README with actual evidence.

## Review focus

- Supplied SQL depends on upstream tables absent here; local definitions are explicit fixtures, not invented upstream contracts.
- New SQL range functions and shift resolution must agree with slot availability, including disabled assignments and dealer exceptions.
- Shared employee memberships and calendar participants must block across teams/dealers.
- Source deletion/target merge retain slot identity and advance versions without negative planned minutes.
- Hub endpoint/error contract remains unspecified; existing truthful pending outbox boundary is retained rather than inventing a remote API.
- Supplied membership ROUND allocation may change aggregate totals; document and test observed supplied behavior rather than silently retaining the previous allocation algorithm.

## Execution evidence

- Explicitly stopped the verified project app and dropped/recreated only database capacity_planning in the project Compose SQL Server instance.
- All six Liquibase changesets applied. Attached SQL is preserved byte-for-byte in 002; 006 fixes confirmed eager conversion and overlapping-shift counting defects without editing the attachment.
- ./mvnw -q clean verify: exit0; 36 Java tests, zero failures, package built.
- SQL regression: effective versions, assignment tombstones, event participant scope across dealers, pending/approved union, exceptions, productivity, supplied membership ROUND, overlapping assignments and explicit NULL-offset failure all passed.
- tests/live-smoke.py passed real SQL creation/schedules/atomic swap/stable IDs/failed-batch rollback/stale version/positive duration checks.
- tests/live-move-smoke.py passed real SQL rounded105/97 transfer, member/team conversions, partial/full merge, DROP, source deletion, audit slot duration and two simultaneous stale writers (200/409). Temporary plans cleaned up; audit/outbox retained.
- Three Node business-time tests and JS syntax passed. Browser ui-smoke and team-drag-smoke passed with explicit API test doubles, including planned-only team-chip conflict handling. No claim these browser mocks establish full-stack proof.
- Scoped review findings corrected and rereview clean. Existing target conflicts no longer prevent planned-only merges, while incoming overlap remains blocked.
- Runtime left at localhost:8081. Database has four demo plans, 7671 calendar days, no NULL offsets, zero consistency violations. External Hub transport/processor remains unimplemented pending an actual contract; responses stay PENDING truthfully.
