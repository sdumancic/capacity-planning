# Capacity Planning Implementation Plan

**Goal:** Implement the supplied Work Order Planning & Scheduling specification as a standalone Quarkus application backed by SQL Server and Liquibase, with repeatable local dummy data.

**Architecture:** Pure Java scheduling policies, transactional application services, JDBC persistence adapters and REST DTOs. SQL functions compute capacity without storing totals. Local upstream fixture tables replace systems absent from this starter; hub synchronization stays pending without a real hub contract.

**Tech Stack:** Existing Java 25 / Quarkus 3.40.1.1, SQL Server Docker Compose, Liquibase, Jackson, JUnit.

**Spec:** `docs/Work Order Planning & Scheduling.md`

## Constraints and decisions

- UTC instants, integer minutes, ISO durations, business-zone local dates; required configured IANA zone.
- SQL Server transaction-owned application locks serialize affected people/teams and days; optimistic versions reject stale edits.
- No v2 exists here; no fabricated migration or legacy endpoints.
- Upstream tables are explicitly local fixture contracts. Equal membership capacity shares resolve the specification's open split question.
- No hub endpoint/status contract exists: durable pending outbox is implemented; never report fictitious sync success.
- No Git repository exists in the workspace; work directly in the supplied project without commits or worktrees.
- This is local software; bind to loopback until deployment authentication and upstream contracts are supplied.

## Review focus

- Overlapping blocked intervals must count once; pending absence must not reduce capacity.
- Batch swaps validate final state and roll back completely on any failure.
- Different memberships of the same person share availability and write locks.
- DST dates and a browser outside the configured business zone must render correctly.
- Merge rounding and moving a rounded-up slot must not create negative planned minutes.

## Task 1: Database and computed capacity

- [x] Add Compose SQL Server with persistent volume, health check and database bootstrap.
- [x] Add Liquibase schema, SQL capacity functions, local fixture tables, and opt-in deterministic dummy data.
- [x] Add required business-zone/calendar initializer; calculate UTC noon offsets on zone change.
- [ ] Verify Compose config and migrations against SQL Server where Docker is available.

## Task 2: Planning and scheduling API

- [x] Add typed domain models, interval policy and duration validation with focused tests.
- [x] Add JDBC persistence port/adapter, transactional planning, duration change, deletion, single and batch schedules.
- [x] Add full/partial moves, preview, slot moves, audit snapshots and durable pending hub outbox.
- [x] Add read endpoints for planner configuration, fixtures, capacity, plans, absences and audit history.
- [ ] Verify stale versions, slot ownership/grid/window/free intervals, merge limits and overbooking.

## Task 3: Local planner and delivery verification

- [x] Add a browser planner for day capacity, planning, scheduling drafts, moves and audit reads, using configured business zone.
- [x] Document startup, fixtures, API examples and unresolved integration boundaries.
- [ ] Run unit tests, build and SQL Server-backed API checks; record actual results and limitations.

## Execution evidence

- Final `./mvnw -q clean verify`: exit 0; 23 Java tests, zero failures, package built.
- Node business-time tests: 3 passed; planner syntax valid.
- Browser smoke passed against explicit API test doubles; desktop and 390px layout inspected.
- Compose configuration valid. Actual SQL Server container exits 139: QEMU target signal 11 on ARM64. No claim that Liquibase or dummy seed ran. SQL regression script and full-stack transaction/concurrency checks remain pending on a working SQL Server runtime.
- Review findings fixed and scoped re-review passed: UTC JDBC reads/writes, team+member preview capacity, audit deltas, adjacent shift union, disabled unscheduling, membership rounding, calendar range.
- Nightly consistency view/reporting implemented; hub API/worker and upstream event consumers remain external integration boundaries as documented in README.

## Live SQL verification after Rosetta

Rosetta enabled on VZ, SQL Server healthy. All four migrations applied including seed. Calendar initialization required explicit SQL BEGIN TRANSACTION before sp_getapplock; fixed this in initializer and planning locks. SQL capacity regression passed. API smoke verified create, schedules, atomic swap, stable IDs, failed-batch rollback, stale versions and duration validation with targeted cleanup. Database has3 employees,4 orders,4 initial plans,36,890 calendar rows and zero consistency violations. Application left on localhost:8081 because8080 is occupied. Full concurrency and external integration proof remain outside these checks.
