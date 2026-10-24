package org.acme.planning.application;

import org.acme.planning.domain.Models.*;
import java.time.*;
import java.util.*;

public interface PlanningRepository {
    Plan find(long id);
    List<Plan> list(LocalDate day, Integer workOrderId);
    /** Member plans of every membership of one person on one day. */
    List<Plan> personPlans(int user, LocalDate day);
    Plan target(int order, int team, Integer member, LocalDate day);
    Order order(int id);
    Assignee assignee(Integer team, Integer member);
    Capacity capacity(int team, Integer member, LocalDate day);
    List<Interval> shifts(Assignee a, LocalDate day);
    List<Interval> blocks(int user, LocalDate day, boolean pending);
    List<Interval> booked(int user, LocalDate day, Set<Long> excludedPlans);
    Plan save(Plan plan);
    void delete(Plan plan);
    void enqueue(long slot, int user, LocalDate day);
    void log(String operation, Plan before, Plan after, Long related, UUID correlation, Integer minutes);
    default void logMove(String operation, Plan before, Plan after, Long related, UUID correlation, Integer minutes, Plan source, Plan target) {
        log(operation, before, after, related, correlation, minutes);
    }
    Map<String, Object> planner(LocalDate day, Integer dealerId, String zone);
    List<Map<String, Object>> logs(long id);
}
