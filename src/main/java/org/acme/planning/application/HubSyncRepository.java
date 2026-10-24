package org.acme.planning.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface HubSyncRepository {
    /** A claimed outbox row (PROCESSING, locked for 60 s); attempts already include this claim. */
    record Message(long id, long slotId, int userId, LocalDate day, int attempts) {}

    /** Current desired state of a slot; a null event means that the local slot was deleted. */
    record Job(long slotId, String hubEventId, HubCalendarClient.CalendarEvent event) {}

    record Status(String status, String errorCode) {}

    /** Claims the PENDING rows of these slots; rows held by another processor are skipped. */
    List<Message> claim(Collection<Long> slotIds);
    /** Claims up to {@code limit} due rows plus the other due rows of the same person-days. */
    List<Message> claimDue(int limit);
    Job job(long slotId);
    void eventId(long slotId, String hubEventId);
    void synced(Message message, boolean slotDeleted);
    void rejected(Message message);
    void retry(Message message, Duration backoff, String error);
    void dead(Message message, String errorCode, String error);
    /** Returns an unfinished row to PENDING, due now, without counting the attempt. */
    void release(Message message);
    /** Sync state per slot; a missing entry means the slot was deleted and its event removed. */
    Map<Long, Status> statuses(Collection<Long> slotIds);
    Optional<Instant> oldestPending();
}
