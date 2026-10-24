package org.acme.planning.application;

import org.acme.planning.domain.*;
import org.acme.planning.domain.Models.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class CommonPlanningService {
    private final PlanningRepository repo;
    private final ZoneId zone;
    public CommonPlanningService(PlanningRepository repo,
            @ConfigProperty(name = "capacity.business-zone") String zone) {
        this.repo = repo;
        this.zone = ZoneId.of(zone);
    }

    ZoneId zone() {
        return zone;
    }

    void day(LocalDate day) {
        if (day == null || day.isBefore(LocalDate.of(2020, 1, 1)) || day.isAfter(LocalDate.of(2040, 12, 31))) {
            throw Problem.bad("Business day must be between 2020-01-01 and 2040-12-31");
        }
    }

    void version(Plan p, Integer expected) {
        if (expected == null) {
            throw Problem.bad("expectedVersion required");
        }
        if (p.version() != expected) {
            throw new Problem(409, "VERSION_CONFLICT", "Time plan changed; reload", Map.of("currentVersion", p.version()));
        }
    }

    Assignee valid(int order, Integer team, Integer member) {
        var a = repo.assignee(team, member);
        if (!a.enabled()) {
            throw Problem.bad("Team and member must be enabled");
        }
        if (repo.order(order).dealerId() != a.dealerId()) {
            throw Problem.bad("Assignee and work order must belong to the same dealer");
        }
        return a;
    }

    void limit(Plan p) {
        if (p.plannedMinutes()<0 || p.plannedMinutes()>525600) {
            throw Problem.bad("Planned duration out of bounds");
        }
        if (p.scheduled()>Policy.ceil15(p.plannedMinutes())) {
            throw new Problem(400, "DURATION_EXCEEDED", "Scheduled time exceeds rounded planned duration");
        }
        if (p.memberId() == null && !p.slots().isEmpty()) {
            throw Problem.bad("Team plans cannot have slots");
        }
    }

    void capacity(Plan source, Assignee target, LocalDate day, int gain, boolean allow) {
        var shortfall = capacityShortfall(source, target, day, gain);
        if (shortfall != null && !allow) {
            throw new Problem(409, "CAPACITY_EXCEEDED", "Target capacity requires explicit overbooking confirmation", shortfall);
        }
    }

    /** The first gaining target (team, then member) whose available minutes are below the gain; null when it fits. */
    Map<String, Object> capacityShortfall(Plan source, Assignee target, LocalDate day, int gain) {
        if (gain<=0) {
            return null;
        }
        var checks = new ArrayList<Capacity>();
        if (source == null || source.teamId() != target.teamId()) {
            checks.add(repo.capacity(target.teamId(), null, day));
        }
        if (target.memberId() != null && (source == null || !Objects.equals(source.memberId(), target.memberId()))) {
            checks.add(repo.capacity(target.teamId(), target.memberId(), day));
        }
        for (var c:checks) {
            if (c.availableMinutes()<gain) {
                return Map.of("availableDuration", Duration.ofMinutes(c.availableMinutes()).toString(), "requestedDuration", Duration.ofMinutes(gain).toString());
            }
        }
        return null;
    }

    /** Response of a write; {@code plans} are the saved states returned by the repository. */
    Map<String, Object> result(List<Plan> plans, List<Map<String, Object>> sync) {
        var warnings = new ArrayList<Map<String, Object>>();
        for (var p:plans) {
            if (p.memberId() != null && !p.slots().isEmpty()) {
                var a = repo.assignee(p.teamId(), p.memberId());
                var pending = repo.blocks(a.userId(), p.day(), true);
                for (var slot:p.slots()) {
                    for (var absence:pending) {
                        if (Policy.overlap(slot.start(), slot.end(), absence.start(), absence.end())) {
                            warnings.add(Map.of("code", "SLOT_OVERLAPS_PENDING_ABSENCE", "slotId", slot.id(), "absenceId", absence.id(), "from", slot.start().isAfter(absence.start()) ? slot.start() : absence.start(), "to", slot.end().isBefore(absence.end()) ? slot.end() : absence.end()));
                        }
                    }
                }
            }
        }
        return Map.of("timePlans", plans.stream().map(PlanningViews::plan).toList(), "warnings", warnings, "hubSync", sync);
    }

    void sync(Plan before, Plan after, List<Map<String, Object>> out) {
        syncStates(before == null ? List.of() : List.of(before), after == null ? List.of() : List.of(after), out);
    }

    void syncStates(List<Plan> before, List<Plan> after, List<Map<String, Object>> out) {
        var oldSlots = new HashMap<Long, Slot>();
        var oldOwners = new HashMap<Long, Plan>();
        var nextSlots = new HashMap<Long, Slot>();
        var nextOwners = new HashMap<Long, Plan>();
        for (var plan : before) {
            for (var slot : plan.slots()) {
                oldSlots.put(slot.id(), slot);
                oldOwners.put(slot.id(), plan);
            }
        }
        for (var plan : after) {
            for (var slot : plan.slots()) {
                nextSlots.put(slot.id(), slot);
                nextOwners.put(slot.id(), plan);
            }
        }
        var ids = new TreeSet<Long>();
        ids.addAll(oldSlots.keySet());
        ids.addAll(nextSlots.keySet());
        for (long id : ids) {
            var previous = oldSlots.get(id);
            var next = nextSlots.get(id);
            var owner = next == null ? oldOwners.get(id) : nextOwners.get(id);
            int user = repo.assignee(owner.teamId(), owner.memberId()).userId();
            if (previous != null && next != null && previous.start().equals(next.start()) && previous.end().equals(next.end())) {
                var old = oldOwners.get(id);
                if (repo.assignee(old.teamId(), old.memberId()).userId() == user) {
                    continue;
                }
            }
            repo.enqueue(id, user, owner.day());
            out.add(Map.of("slotId", id, "status", "PENDING"));
        }
    }

    public Map<String, Object> config() {
        return Map.of("businessZone", zone.toString(), "minDay", "2020-01-01", "maxDay", "2040-12-31");
    }
}
