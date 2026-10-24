package org.acme.planning.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.planning.application.HubSyncRepository.Message;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/** Reconciles slots with Hub Calendar outside any planning transaction (spec: Hub calendar integration). */
@ApplicationScoped
public class HubSyncService {
    private static final Logger LOG = Logger.getLogger(HubSyncService.class);
    static final String KEY = "service-portal:slot:";
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1));
    static final int MAX_ATTEMPTS = 8;
    static final int BATCH = 20;
    private static final Duration WORKER_LOCK_TIMEOUT = Duration.ofSeconds(5);

    private final HubSyncRepository repo;
    private final PlanningLockRepository locks;
    private final HubCalendarClient hub;
    private final Duration budget;

    public HubSyncService(HubSyncRepository repo, PlanningLockRepository locks, HubCalendarClient hub,
            @ConfigProperty(name = "capacity.hub.sync-budget", defaultValue = "3s") Duration budget) {
        this.repo = repo;
        this.locks = locks;
        this.hub = hub;
        this.budget = budget;
    }

    enum Outcome { SYNCED, OVERLAP, RETRYABLE, FAILED, UNAVAILABLE }

    record Result(Outcome outcome, boolean slotDeleted, String error) {
        static Result of(Outcome outcome, String error) {
            return new Result(outcome, false, error);
        }
    }

    /** Replaces the pre-commit PENDING snapshot of an action result with the outcome of syncNow. */
    public Map<String, Object> afterCommit(Map<String, Object> result) {
        var ids = new LinkedHashSet<Long>();
        if (result.get("hubSync") instanceof List<?> sync) {
            for (var item : sync) {
                if (item instanceof Map<?, ?> m && m.get("slotId") instanceof Number id) ids.add(id.longValue());
            }
        }
        if (ids.isEmpty()) return result;
        var out = new LinkedHashMap<>(result);
        out.put("hubSync", syncNow(ids));
        return out;
    }

    /** Runs after COMMIT in the request: own messages only, 3 s budget, never throws. */
    public List<Map<String, Object>> syncNow(Collection<Long> slotIds) {
        try {
            var rows = repo.claim(slotIds);
            Instant deadline = Instant.now().plus(budget);
            var groups = groups(rows);
            if (groups.size() == 1) {
                processGroup(groups.getFirst(), deadline);
            } else if (!groups.isEmpty()) {
                // Order matters only inside one person-day; different person-days run in parallel.
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (var group : groups) executor.submit(() -> processGroup(group, deadline));
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("Hub synchronization after commit failed; the worker continues", failure);
        }
        return statuses(slotIds);
    }

    /** Scheduled worker: due messages and everything system handlers enqueued. */
    public void work() {
        for (var group : groups(repo.claimDue(BATCH))) processGroup(group, null);
        repo.oldestPending().filter(t -> t.isBefore(Instant.now().minus(Duration.ofMinutes(5))))
                .ifPresent(t -> LOG.errorf("ALERT Hub outbox: oldest PENDING message is from %s", t));
    }

    private List<Map<String, Object>> statuses(Collection<Long> slotIds) {
        Map<Long, HubSyncRepository.Status> statuses;
        try {
            statuses = repo.statuses(slotIds);
        } catch (RuntimeException failure) {
            LOG.warn("Cannot read Hub synchronization state", failure);
            statuses = Map.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (long id : new TreeSet<>(slotIds)) {
            var s = statuses.get(id);
            var item = new LinkedHashMap<String, Object>();
            item.put("slotId", id);
            // No sync row left: the slot was deleted and its event removed.
            item.put("status", s == null ? "SYNCED" : s.status());
            if (s != null && s.errorCode() != null && !"PENDING".equals(s.status())) item.put("errorCode", s.errorCode());
            out.add(item);
        }
        return out;
    }

    private static List<List<Message>> groups(List<Message> rows) {
        var groups = new LinkedHashMap<String, List<Message>>();
        for (var r : rows) groups.computeIfAbsent(r.userId() + ":" + r.day(), k -> new ArrayList<>()).add(r);
        return new ArrayList<>(groups.values());
    }

    void processGroup(List<Message> rows, Instant deadline) {
        var first = rows.getFirst();
        var timeout = deadline == null ? WORKER_LOCK_TIMEOUT : Duration.between(Instant.now(), deadline);
        var remaining = new ArrayList<>(rows);
        try (var lock = locks.lockHubUserDay(first.userId(), first.day(), timeout.isNegative() ? Duration.ZERO : timeout)
                .orElse(null)) {
            if (lock == null) {
                remaining.forEach(repo::release);
                return;
            }
            // Free times before they are taken again: deletes, then updates, then creates.
            var ordered = new ArrayList<Message>();
            var kind = new HashMap<Long, Integer>();
            for (var r : rows) {
                var job = repo.job(r.slotId());
                kind.put(r.id(), job.event() == null ? 0 : job.hubEventId() != null ? 1 : 2);
                ordered.add(r);
            }
            ordered.sort(Comparator.comparing((Message r) -> kind.get(r.id())).thenComparing(Message::id));
            var retry = new ArrayList<Message>();
            boolean open = false;
            for (int i = 0; i < ordered.size(); i++) {
                var r = ordered.get(i);
                if (open || expired(deadline)) {
                    release(r, remaining);
                    continue;
                }
                var result = reconcile(r.slotId());
                if (result.outcome() == Outcome.UNAVAILABLE) open = true;
                if (result.outcome() == Outcome.OVERLAP && i < ordered.size() - 1) {
                    // Another message of this person-day may free the time (swap); try once more at the end.
                    retry.add(r);
                    continue;
                }
                finish(r, result, remaining);
            }
            for (var r : retry) {
                if (open || expired(deadline)) release(r, remaining);
                else finish(r, reconcile(r.slotId()), remaining);
            }
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "Hub synchronization of user %d on %s stopped", first.userId(), first.day());
            for (var r : remaining) {
                try {
                    repo.release(r);
                } catch (RuntimeException ignored) {
                    // The 60 s claim expires and the worker reclaims the row.
                }
            }
        }
    }

    private static boolean expired(Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    private void release(Message r, List<Message> remaining) {
        repo.release(r);
        remaining.remove(r);
    }

    Result reconcile(long slotId) {
        var job = repo.job(slotId);
        String id = job.hubEventId();
        try {
            if (job.event() == null) {
                // Slot deleted: find the event by key if a crash lost the link.
                if (id == null) id = hub.findByExternalId(KEY + slotId).map(HubCalendarClient.HubEvent::id).orElse(null);
                if (id != null) delete(id);
                return new Result(Outcome.SYNCED, true, null);
            }
            if (id != null) {
                try {
                    hub.update(id, job.event());
                    return Result.of(Outcome.SYNCED, null);
                } catch (HubCalendarException e) {
                    if (e.statusCode() != 404) throw e;
                    repo.eventId(slotId, null);
                    id = null;
                }
            }
            var created = hub.create(job.event());
            if (created == null || created.id() == null || created.id().isBlank()) {
                throw new IllegalStateException("Hub create returned no event ID");
            }
            id = created.id();
            repo.eventId(slotId, id);
            // An earlier attempt may have created an older state under the same key.
            if (!job.event().equals(created.event())) hub.update(id, job.event());
            return Result.of(Outcome.SYNCED, null);
        } catch (HubCalendarException e) {
            if (e.statusCode() == 409 && job.event() != null) return overlap(slotId, id);
            return classify(e);
        } catch (HubCircuitOpenException e) {
            return Result.of(Outcome.UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            return classify(e);
        }
    }

    private Result overlap(long slotId, String id) {
        if (id != null) {
            // A refused update would leave the event at the old time and, after a move, with the old member.
            try {
                delete(id);
            } catch (HubCircuitOpenException e) {
                return Result.of(Outcome.UNAVAILABLE, e.getMessage());
            } catch (RuntimeException e) {
                LOG.warnf("Hub overlap cleanup failed for slotId=%d; retaining event ID for retry", slotId);
                return classify(e);
            }
            repo.eventId(slotId, null);
        }
        return Result.of(Outcome.OVERLAP, "HUB_OVERLAP");
    }

    private void delete(String id) {
        try {
            hub.delete(id);
        } catch (HubCalendarException e) {
            if (e.statusCode() != 404) throw e;
        }
    }

    static boolean retryable(Throwable t) {
        if (t instanceof HubCalendarException e) {
            int s = e.statusCode();
            return s >= 500 || s == 408 || s == 429 || s < 400;
        }
        return !(t instanceof HubCircuitOpenException);
    }

    private static Result classify(RuntimeException e) {
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Result.of(retryable(e) ? Outcome.RETRYABLE : Outcome.FAILED, error);
    }

    private void finish(Message r, Result result, List<Message> remaining) {
        switch (result.outcome()) {
            case SYNCED -> repo.synced(r, result.slotDeleted());
            case OVERLAP -> repo.rejected(r);
            case UNAVAILABLE -> repo.release(r);
            case RETRYABLE -> {
                if (r.attempts() >= MAX_ATTEMPTS) {
                    repo.dead(r, "HUB_SYNC_FAILED", result.error());
                    LOG.errorf("ALERT Hub outbox message %d (slotId=%d) is DEAD after %d attempts: %s",
                            r.id(), r.slotId(), r.attempts(), result.error());
                } else {
                    repo.retry(r, BACKOFF.get(Math.max(0, r.attempts() - 1)), result.error());
                }
            }
            case FAILED -> {
                repo.dead(r, "HUB_REQUEST_REJECTED", result.error());
                LOG.errorf("ALERT Hub outbox message %d (slotId=%d) is DEAD: %s", r.id(), r.slotId(), result.error());
            }
        }
        remaining.remove(r);
    }
}
