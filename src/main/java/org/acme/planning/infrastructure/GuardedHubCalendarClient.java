package org.acme.planning.infrastructure;

import io.smallrye.faulttolerance.api.Guard;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.planning.application.HubCalendarClient;
import org.acme.planning.application.HubCalendarException;
import org.acme.planning.application.HubCircuitOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Circuit breaker on Hub calls: open after 5 failures or timeouts in a row, half-open after 30 s. */
@ApplicationScoped
public class GuardedHubCalendarClient implements HubCalendarClient {
    private final HubCalendarClient delegate;
    private final Guard guard = Guard.create()
            .withDescription("hub-calendar")
            .withCircuitBreaker()
                .name("hub-calendar")
                .requestVolumeThreshold(5)
                .failureRatio(1.0)
                .delay(30, ChronoUnit.SECONDS)
                .successThreshold(1)
                // Overlaps, unknown events and other client errors are answers, not outages.
                .when(GuardedHubCalendarClient::outage)
                .done()
            .build();

    public GuardedHubCalendarClient(DummyHubCalendarClient delegate) {
        this.delegate = delegate;
    }

    static boolean outage(Throwable t) {
        if (t instanceof HubCalendarException e) {
            int s = e.statusCode();
            return s >= 500 || s == 408 || s == 429;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private <T> T call(Callable<T> call) {
        try {
            return guard.call(call, (Class<T>) Object.class);
        } catch (CircuitBreakerOpenException e) {
            throw new HubCircuitOpenException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public HubEvent create(CalendarEvent event) {
        return call(() -> delegate.create(event));
    }

    @Override
    public void update(String eventId, CalendarEvent event) {
        call(() -> {
            delegate.update(eventId, event);
            return null;
        });
    }

    @Override
    public void delete(String eventId) {
        call(() -> {
            delegate.delete(eventId);
            return null;
        });
    }

    @Override
    public Optional<HubEvent> findByExternalId(String externalId) {
        return call(() -> delegate.findByExternalId(externalId));
    }
}
