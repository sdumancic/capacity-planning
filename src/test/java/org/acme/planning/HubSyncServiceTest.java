package org.acme.planning;

import org.acme.planning.application.*;
import org.acme.planning.application.HubCalendarClient.CalendarEvent;
import org.acme.planning.application.HubCalendarClient.HubEvent;
import org.acme.planning.application.HubSyncRepository.*;
import org.acme.planning.domain.Models.Plan;
import org.acme.planning.infrastructure.DummyHubCalendarClient;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HubSyncServiceTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    static final Instant T8 = Instant.parse("2026-10-26T08:00:00Z");

    static CalendarEvent event(long slot, int hour) {
        return new CalendarEvent("service-portal:slot:" + slot, 452, 1505,
                T8.plusSeconds(3600L * (hour - 8)), T8.plusSeconds(3600L * (hour - 7)));
    }

    /** Outbox, slot_hub_sync and the desired slot state in memory. */
    static class State implements HubSyncRepository, PlanningLockRepository {
        final Map<Long, CalendarEvent> slots = new HashMap<>();   // live local slots
        final Map<Long, String> hubIds = new HashMap<>();
        final Map<Long, Status> sync = new HashMap<>();
        final Map<Long, String> outbox = new LinkedHashMap<>();   // message id -> status
        final Map<Long, Message> messages = new HashMap<>();
        final Map<Long, Duration> backoff = new HashMap<>();
        long sequence;
        boolean lockGranted = true;

        long enqueue(long slot, int attempts) {
            long id = ++sequence;
            messages.put(id, new Message(id, slot, 452, DAY, attempts));
            outbox.put(id, "PENDING");
            sync.put(slot, new Status("PENDING", null));
            return id;
        }

        private Message claimed(Message m) {
            var c = new Message(m.id(), m.slotId(), m.userId(), m.day(), m.attempts() + 1);
            messages.put(m.id(), c);
            outbox.put(m.id(), "PROCESSING");
            return c;
        }

        public List<Message> claim(Collection<Long> slotIds) {
            return messages.values().stream().filter(m -> "PENDING".equals(outbox.get(m.id())) && slotIds.contains(m.slotId()))
                    .sorted(Comparator.comparing(Message::id)).map(this::claimed).toList();
        }
        public List<Message> claimDue(int limit) {
            return messages.values().stream().filter(m -> "PENDING".equals(outbox.get(m.id())))
                    .sorted(Comparator.comparing(Message::id)).limit(limit).map(this::claimed).toList();
        }
        public Job job(long slotId) {
            return new Job(slotId, hubIds.get(slotId), slots.get(slotId));
        }
        public void eventId(long slotId, String id) {
            if (id == null) hubIds.remove(slotId); else hubIds.put(slotId, id);
        }
        public void synced(Message m, boolean deleted) {
            outbox.put(m.id(), "DONE");
            if (deleted) sync.remove(m.slotId()); else sync.put(m.slotId(), new Status("SYNCED", null));
        }
        public void rejected(Message m) {
            outbox.put(m.id(), "DONE");
            sync.put(m.slotId(), new Status("REJECTED", "HUB_OVERLAP"));
        }
        public void retry(Message m, Duration b, String error) {
            outbox.put(m.id(), "PENDING");
            backoff.put(m.id(), b);
        }
        public void dead(Message m, String code, String error) {
            outbox.put(m.id(), "DEAD");
            sync.put(m.slotId(), new Status("FAILED", code));
        }
        public void release(Message m) {
            outbox.put(m.id(), "PENDING");
            messages.put(m.id(), new Message(m.id(), m.slotId(), m.userId(), m.day(), m.attempts() - 1));
        }
        public Map<Long, Status> statuses(Collection<Long> slotIds) {
            var out = new HashMap<Long, Status>();
            for (long id : slotIds) if (sync.containsKey(id)) out.put(id, sync.get(id));
            return out;
        }
        public Optional<Instant> oldestPending() { return Optional.empty(); }
        public void lockAssignees(Collection<Plan> plans) {}
        public void lockPlans(Collection<Long> ids) {}
        public Optional<Held> lockHubUserDay(int u, LocalDate d, Duration t) {
            return lockGranted ? Optional.of(() -> {}) : Optional.empty();
        }
    }

    /** A Hub that refuses overlapping events of one participant, like the real one. */
    static class Calendar implements HubCalendarClient {
        final Map<String, CalendarEvent> events = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        int creates;
        Integer writeStatus;
        Integer deleteStatus;
        RuntimeException failure;
        boolean overlaps = true;

        private void check(String self, CalendarEvent e) {
            if (failure != null) throw failure;
            if (writeStatus != null) throw new HubCalendarException(writeStatus);
            if (overlaps && events.entrySet().stream().anyMatch(x -> !x.getKey().equals(self)
                    && x.getValue().userId() == e.userId()
                    && x.getValue().start().isBefore(e.end()) && e.start().isBefore(x.getValue().end()))) {
                throw new HubCalendarException(409);
            }
        }
        public HubEvent create(CalendarEvent e) {
            calls.add("create " + e.externalId());
            var live = findByExternalId(e.externalId());
            if (live.isPresent()) return live.get();
            check(null, e);
            String id = "test-event-" + ++creates;
            events.put(id, e);
            return new HubEvent(id, e);
        }
        public void update(String id, CalendarEvent e) {
            calls.add("update " + e.externalId());
            if (!events.containsKey(id)) throw new HubCalendarException(404);
            check(id, e);
            events.put(id, e);
        }
        public void delete(String id) {
            calls.add("delete " + id);
            if (failure != null) throw failure;
            if (deleteStatus != null) throw new HubCalendarException(deleteStatus);
            if (events.remove(id) == null) throw new HubCalendarException(404);
        }
        public Optional<HubEvent> findByExternalId(String key) {
            return events.entrySet().stream().filter(x -> x.getValue().externalId().equals(key))
                    .map(x -> new HubEvent(x.getKey(), x.getValue())).findFirst();
        }
    }

    final State state = new State();
    final Calendar hub = new Calendar();
    final HubSyncService service = new HubSyncService(state, state, hub, Duration.ofSeconds(3));

    String status(long slot) {
        var s = state.sync.get(slot);
        return s == null ? null : s.status();
    }

    @Test void createsThenUpdatesSameEventAndDeletesItWithTheSyncRow() {
        state.slots.put(21L, event(21, 8));
        state.enqueue(21, 0);
        var result = service.syncNow(List.of(21L));
        assertEquals(List.of(Map.of("slotId", 21L, "status", "SYNCED")), result);
        String id = state.hubIds.get(21L);

        state.slots.put(21L, event(21, 9));
        state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertEquals(id, state.hubIds.get(21L));
        assertEquals(event(21, 9), hub.events.get(id));
        assertEquals(1, hub.creates);

        state.slots.remove(21L);
        state.enqueue(21, 0);
        assertEquals("SYNCED", service.syncNow(List.of(21L)).getFirst().get("status"));
        assertTrue(hub.events.isEmpty());
        assertFalse(state.sync.containsKey(21L), "slot_hub_sync row is deleted with the event");
    }

    @Test void swapOfOnePersonDayIsSyncedByOrderingAndRetry() {
        // A 8-9 -> 9-10, B 9-10 -> 8-9 (spec: Order per person and day).
        state.slots.put(21L, event(21, 8));
        state.slots.put(31L, event(31, 9));
        state.enqueue(21, 0);
        state.enqueue(31, 0);
        service.syncNow(List.of(21L, 31L));
        String oldA = state.hubIds.get(21L);
        state.slots.put(21L, event(21, 9));
        state.slots.put(31L, event(31, 8));
        state.enqueue(21, 0);
        state.enqueue(31, 0);
        var result = service.syncNow(List.of(21L, 31L));
        assertEquals("SYNCED", result.get(0).get("status"));
        assertEquals("SYNCED", result.get(1).get("status"));
        assertNotEquals(oldA, state.hubIds.get(21L), "A's stale event is deleted and recreated");
        assertEquals(event(21, 9), hub.events.get(state.hubIds.get(21L)));
        assertEquals(event(31, 8), hub.events.get(state.hubIds.get(31L)));
    }

    @Test void overlapRejectsAndDeletesTheStaleEvent() {
        state.slots.put(21L, event(21, 8));
        state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        hub.events.put("foreign", new CalendarEvent("other", 452, 1, event(21, 9).start(), event(21, 9).end()));
        state.slots.put(21L, event(21, 9));
        state.enqueue(21, 0);
        var result = service.syncNow(List.of(21L));
        assertEquals(Map.of("slotId", 21L, "status", "REJECTED", "errorCode", "HUB_OVERLAP"), result.getFirst());
        assertNull(state.hubIds.get(21L));
        assertEquals(Set.of("foreign"), hub.events.keySet());
    }

    @Test void failedOverlapCleanupKeepsIdAndRetries() {
        state.slots.put(21L, event(21, 8));
        state.hubIds.put(21L, "kept");
        hub.events.put("kept", event(21, 8));
        long m = state.enqueue(21, 0);
        hub.writeStatus = 409;
        hub.deleteStatus = 503;
        service.syncNow(List.of(21L));
        assertEquals("kept", state.hubIds.get(21L));
        assertEquals("PENDING", state.outbox.get(m));
        assertEquals("PENDING", status(21));
    }

    @Test void serverErrorsBackOffAndDieAfterTheEighthAttempt() {
        state.slots.put(21L, event(21, 8));
        long first = state.enqueue(21, 0);
        hub.writeStatus = 503;
        service.syncNow(List.of(21L));
        assertEquals("PENDING", state.outbox.get(first));
        assertEquals(Duration.ofSeconds(5), state.backoff.get(first));
        long last = state.enqueue(21, 7);
        service.syncNow(List.of(21L));
        assertEquals("DEAD", state.outbox.get(last));
        assertEquals("FAILED", status(21));
    }

    @Test void networkErrorsAreRetryable() {
        state.slots.put(21L, event(21, 8));
        long m = state.enqueue(21, 2);
        hub.failure = new IllegalStateException("connection reset");
        service.syncNow(List.of(21L));
        assertEquals("PENDING", state.outbox.get(m));
        assertEquals(Duration.ofMinutes(2), state.backoff.get(m));
    }

    @Test void otherClientErrorsAreDeadAtOnce() {
        state.slots.put(21L, event(21, 8));
        long m = state.enqueue(21, 0);
        hub.writeStatus = 422;
        service.syncNow(List.of(21L));
        assertEquals("DEAD", state.outbox.get(m));
        assertEquals("FAILED", status(21));
    }

    @Test void unknownEventOnUpdateIsRecreated() {
        state.slots.put(21L, event(21, 8));
        state.hubIds.put(21L, "gone");
        state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertEquals("SYNCED", status(21));
        assertNotEquals("gone", state.hubIds.get(21L));
        assertEquals(event(21, 8), hub.events.get(state.hubIds.get(21L)));
    }

    @Test void createReturningAnOlderLiveEventUpdatesIt() {
        // A crash after Hub created the event but before its id was saved.
        hub.events.put("crashed", event(21, 8));
        state.slots.put(21L, event(21, 10));
        state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertEquals("crashed", state.hubIds.get(21L));
        assertEquals(event(21, 10), hub.events.get("crashed"));
    }

    @Test void deletedSlotWithoutSavedIdIsFoundByKey() {
        hub.events.put("crashed", event(21, 8));
        state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertTrue(hub.events.isEmpty());
    }

    @Test void deleteOfAnUnknownEventIsSuccess() {
        state.hubIds.put(21L, "gone");
        long m = state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertEquals("DONE", state.outbox.get(m));
    }

    @Test void openCircuitReleasesWithoutCountingAnAttempt() {
        state.slots.put(21L, event(21, 8));
        long m = state.enqueue(21, 3);
        hub.failure = new HubCircuitOpenException(null);
        assertEquals("PENDING", service.syncNow(List.of(21L)).getFirst().get("status"));
        assertEquals("PENDING", state.outbox.get(m));
        assertEquals(3, state.messages.get(m).attempts());
    }

    @Test void exhaustedBudgetLeavesMessagesForTheWorker() {
        var slow = new HubSyncService(state, state, hub, Duration.ZERO);
        state.slots.put(21L, event(21, 8));
        long m = state.enqueue(21, 0);
        assertEquals("PENDING", slow.syncNow(List.of(21L)).getFirst().get("status"));
        assertEquals("PENDING", state.outbox.get(m));
        assertEquals(0, hub.creates);
        service.work();
        assertEquals("SYNCED", status(21));
    }

    @Test void heldPersonDayIsReleasedToTheWorker() {
        state.lockGranted = false;
        state.slots.put(21L, event(21, 8));
        long m = state.enqueue(21, 0);
        service.syncNow(List.of(21L));
        assertEquals("PENDING", state.outbox.get(m));
        assertEquals(0, hub.creates);
    }

    @Test void afterCommitReplacesThePendingSnapshot() {
        state.slots.put(21L, event(21, 8));
        state.enqueue(21, 0);
        var result = service.afterCommit(Map.of("timePlans", List.of(), "hubSync", List.of(Map.of("slotId", 21L, "status", "PENDING"))));
        assertEquals(List.of(Map.of("slotId", 21L, "status", "SYNCED")), result.get("hubSync"));
    }

    @Test void dummyConflictSimulationRejectsCreateAndUpdateButNeverDelete() {
        var dummy = new DummyHubCalendarClient(0);
        var created = dummy.create(event(21, 8));
        var conflicting = new DummyHubCalendarClient(1);
        assertEquals(409, assertThrows(HubCalendarException.class, () -> conflicting.create(event(22, 8))).statusCode());
        assertDoesNotThrow(() -> dummy.delete(created.id()));
        assertEquals(404, assertThrows(HubCalendarException.class, () -> dummy.delete(created.id())).statusCode());
    }

    @Test void dummyAcceptsChangedTimeAfterConflictEvenAtFullProbability() {
        var dummy = new DummyHubCalendarClient(1);
        assertThrows(HubCalendarException.class, () -> dummy.create(event(21, 8)));
        var created = assertDoesNotThrow(() -> dummy.create(event(21, 9)));
        // The exemption is consumed: future unrelated edits use the configured probability again.
        assertThrows(HubCalendarException.class, () -> dummy.update(created.id(), event(21, 10)));
    }

    @Test void dummyResolutionExemptionOnlyAppliesToSameSlotAndChangedTime() {
        var dummy = new DummyHubCalendarClient(1);
        assertThrows(HubCalendarException.class, () -> dummy.create(event(21, 8)));
        assertThrows(HubCalendarException.class, () -> dummy.create(event(21, 8)));
        assertThrows(HubCalendarException.class, () -> dummy.create(event(22, 9)));
        assertDoesNotThrow(() -> dummy.create(event(21, 9)));
    }

    @Test void dummyConflictProbabilityMustBeFiniteAndWithinBounds() {
        for (double value : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new DummyHubCalendarClient(value));
        }
    }

    @Test void dummyCreateIsIdempotentByKeyAndUnknownUpdateIs404() {
        var dummy = new DummyHubCalendarClient(0);
        var created = dummy.create(event(21, 8));
        assertEquals(created, dummy.create(event(21, 9)), "the live event is returned for the same key");
        assertNotEquals(created.id(), dummy.create(event(22, 8)).id());
        assertEquals(Optional.of(created), dummy.findByExternalId(event(21, 8).externalId()));
        assertEquals(404, assertThrows(HubCalendarException.class, () -> dummy.update("unknown", event(21, 8))).statusCode());
        dummy.delete(created.id());
        assertNotEquals(created.id(), dummy.create(event(21, 8)).id(), "a key can be reused after delete with a new id");
    }
}
