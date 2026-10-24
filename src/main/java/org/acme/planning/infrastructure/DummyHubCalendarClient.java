package org.acme.planning.infrastructure;

import org.acme.planning.application.HubCalendarClient;
import org.acme.planning.application.HubCalendarException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/** Simulation only: no network requests; live events are kept in memory per application instance. */
@ApplicationScoped
@Typed(DummyHubCalendarClient.class)
public class DummyHubCalendarClient implements HubCalendarClient {
    private static final Logger LOG = Logger.getLogger(DummyHubCalendarClient.class);

    private final double conflictProbability;
    // Simulation state only: a changed time following a simulated rejection succeeds once.
    private final ConcurrentMap<String, CalendarEvent> rejectedEvents = new ConcurrentHashMap<>();
    // Live events by Hub ID and by external key, like Hub's unique index on live keys.
    private final ConcurrentMap<String, HubEvent> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> keys = new ConcurrentHashMap<>();

    public DummyHubCalendarClient(
            @ConfigProperty(name = "capacity.hub.dummy-conflict-probability", defaultValue = "0") double conflictProbability) {
        if (!Double.isFinite(conflictProbability) || conflictProbability < 0 || conflictProbability > 1) {
            throw new IllegalArgumentException("Dummy Hub conflict probability must be between 0 and 1");
        }
        this.conflictProbability = conflictProbability;
    }

    private void simulateConflict(CalendarEvent event) {
        var rejected = rejectedEvents.get(event.externalId());
        if (rejected != null && (!rejected.start().equals(event.start()) || !rejected.end().equals(event.end()))
                && rejectedEvents.remove(event.externalId(), rejected)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < conflictProbability) {
            rejectedEvents.put(event.externalId(), event);
            LOG.infof("Dummy Hub simulated HTTP 409 externalId=%s", event.externalId());
            throw new HubCalendarException(409);
        }
    }

    @Override
    public synchronized HubEvent create(CalendarEvent event) {
        // Idempotent by key: the live event is returned before the overlap check.
        var live = findByExternalId(event.externalId());
        if (live.isPresent()) {
            LOG.infof("Dummy Hub CREATE returned live eventId=%s externalId=%s", live.get().id(), event.externalId());
            return live.get();
        }
        simulateConflict(event);
        var created = new HubEvent("dummy-" + UUID.randomUUID(), event);
        events.put(created.id(), created);
        keys.put(event.externalId(), created.id());
        LOG.infof("Dummy Hub CREATE eventId=%s externalId=%s", created.id(), event.externalId());
        return created;
    }

    @Override
    public synchronized void update(String eventId, CalendarEvent event) {
        if (!events.containsKey(eventId)) throw new HubCalendarException(404);
        simulateConflict(event);
        events.put(eventId, new HubEvent(eventId, event));
        LOG.infof("Dummy Hub UPDATE eventId=%s externalId=%s", eventId, event.externalId());
    }

    @Override
    public synchronized void delete(String eventId) {
        var removed = events.remove(eventId);
        if (removed == null) throw new HubCalendarException(404);
        keys.remove(removed.event().externalId(), eventId);
        LOG.infof("Dummy Hub DELETE eventId=%s", eventId);
    }

    @Override
    public Optional<HubEvent> findByExternalId(String externalId) {
        return Optional.ofNullable(keys.get(externalId)).map(events::get);
    }
}
