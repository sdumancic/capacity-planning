package org.acme.planning.infrastructure;

import org.acme.planning.application.*;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

/** Outbox worker: what the request did not finish, crashed processors and system handler changes. */
@ApplicationScoped
public class HubSyncDispatcher {
    private static final Logger LOG = Logger.getLogger(HubSyncDispatcher.class);
    private final HubSyncService sync;

    public HubSyncDispatcher(HubSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(every = "${capacity.hub.worker-interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void work() {
        try {
            sync.work();
        } catch (RuntimeException failure) {
            // Claimed rows stay locked for 60 s and are reclaimed by the next poll.
            LOG.warn("Hub outbox worker failed; retrying on the next poll", failure);
        }
    }
}
