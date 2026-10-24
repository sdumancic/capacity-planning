package org.acme.planning.application;

import org.acme.planning.domain.Models.*;
import java.time.Duration;
import java.util.*;

public final class PlanningViews {
    private PlanningViews() {
    }

    public static Map<String, Object> plan(Plan p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("timePlanId", p.id());
        m.put("workOrderId", p.workOrderId());
        m.put("teamId", p.teamId());
        m.put("memberId", p.memberId());
        m.put("day", p.day());
        m.put("version", p.version());
        m.put("plannedDuration", Duration.ofMinutes(p.plannedMinutes()).toString());
        m.put("unscheduledDuration", Duration.ofMinutes(Math.max(0, p.plannedMinutes()-p.scheduled())).toString());
        m.put("slots", p.slots().stream().map(s -> Map.of("id", s.id(), "start", s.start(), "end", s.end(), "duration", Duration.ofMinutes(s.minutes()).toString(), "hubStatus", s.hubStatus() == null?"PENDING":s.hubStatus())).toList());
        return m;
    }
}
