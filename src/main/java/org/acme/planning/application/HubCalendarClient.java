package org.acme.planning.application;

import java.time.Instant;
import java.util.Optional;

/** Calendar integration port. The bundled adapter is a simulation, not a Hub API client. */
public interface HubCalendarClient {
    record CalendarEvent(String externalId, int userId, int workOrderId, Instant start, Instant end) {}

    /** A live Hub event; {@code event} is its current state, or null when Hub does not report it. */
    record HubEvent(String id, CalendarEvent event) {}

    /** Idempotent by externalId: an existing live event is returned before the overlap check. */
    // Non-success HTTP responses are reported as HubCalendarException(status): 409 = overlap, 404 = unknown event.
    HubEvent create(CalendarEvent event);
    void update(String eventId, CalendarEvent event);
    void delete(String eventId);
    Optional<HubEvent> findByExternalId(String externalId);
}
