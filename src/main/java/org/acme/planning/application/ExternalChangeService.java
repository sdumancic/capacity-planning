package org.acme.planning.application;

import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.time.LocalDate;
import java.util.*;

/**
 * Entry points for the absence, hub event and shift handlers (spec: Conflicts
 * from external changes).
 * The system never moves or deletes a planner's slot; it reports conflicts and
 * re-sends rejected slots.
 */
@ApplicationScoped
public class ExternalChangeService {
    private static final Logger LOG = Logger.getLogger(ExternalChangeService.class);
    private final PlanningRepository repo;
    private final PlanningLockRepository locks;
    private final TimeSlotService slots;

    public ExternalChangeService(PlanningRepository repo, PlanningLockRepository locks, TimeSlotService slots) {
        this.repo = repo;
        this.locks = locks;
        this.slots = slots;
    }

    /**
     * Call when an absence becomes APPROVED or changes while APPROVED, a hub event
     * changes, or a shift version changes;
     * {@code days} are the business days the changed interval touches. Returns the
     * slots no longer inside free time.
     */
    @Transactional
    public List<Map<String, Object>> onBlockingChange(int ppUserId, Collection<LocalDate> days) {
        var plans = lock(ppUserId, days);
        var conflicts = new ArrayList<Map<String, Object>>();
        for (var p : plans) {
            if (p.slots().isEmpty())
                continue;
            for (var c : slots.conflicts(p, Set.of(p.id()), List.of(p), new ArrayList<>())) {
                var item = new LinkedHashMap<String, Object>(c);
                item.put("timePlanId", p.id());
                conflicts.add(item);
                LOG.infof("Slot conflict after external change: timePlanId=%d slotId=%s reason=%s", p.id(),
                        c.get("slotId"), c.get("reason"));
            }
        }
        return conflicts;
    }

    /**
     * Call in the same transaction as the hub event change: a freed time may now
     * accept rejected slots.
     */
    @Transactional
    public List<Long> onHubEventRemovedOrMoved(int ppUserId, Collection<LocalDate> days) {
        var resent = new ArrayList<Long>();
        for (var p : lock(ppUserId, days)) {
            for (var s : p.slots()) {
                if ("REJECTED".equals(s.hubStatus())) {
                    // The worker retries them; no user waits for the result.
                    repo.enqueue(s.id(), ppUserId, p.day());
                    resent.add(s.id());
                }
            }
        }
        return resent;
    }

    private List<Plan> lock(int ppUserId, Collection<LocalDate> days) {
        var scopes = new ArrayList<Plan>();
        var byDay = new TreeMap<LocalDate, List<Plan>>();
        for (var day : new TreeSet<>(days))
            byDay.put(day, repo.personPlans(ppUserId, day));
        byDay.values().forEach(scopes::addAll);
        // Same lock names as the planner, in one sorted pass, then re-read under the
        // lock.
        locks.lockAssignees(scopes);
        var plans = new ArrayList<Plan>();
        for (var day : byDay.keySet())
            plans.addAll(repo.personPlans(ppUserId, day));
        return plans;
    }
}
