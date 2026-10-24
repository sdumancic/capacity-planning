package org.acme.planning.application;

import org.acme.planning.domain.Models.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class TimePlanLogService {
    private final PlanningRepository repo;

    public TimePlanLogService(PlanningRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> logs(long id) {
        return repo.logs(id);
    }

    void log(String operation, Plan before, Plan after, Long related, UUID correlation, Integer minutes) {
        repo.log(operation, before, after, related, correlation, minutes);
    }

    void logMove(String operation, Plan before, Plan after, Long related, UUID correlation,
            Integer minutes, Plan source, Plan target) {
        repo.logMove(operation, before, after, related, correlation, minutes, source, target);
    }
}
