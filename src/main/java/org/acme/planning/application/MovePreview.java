package org.acme.planning.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /v3/work-order-time-plans/{id}/move/preview}. Durations serialize as ISO 8601
 * strings ("PT1H30M"). Values marked "for this request" use the requested amount, or the whole planned
 * time when the request sent no duration.
 */
public record MovePreview(Source source, Target target, Slots slots) {
    /**
     * The time plan being moved.
     *
     * @param version             sent back by the move request as {@code expectedVersion}
     * @param unscheduledDuration what can be moved: max and default of the amount field
     */
    public record Source(int version, Duration plannedDuration, Duration scheduledDuration,
            Duration unscheduledDuration) {
    }

    /**
     * The team or member that receives the minutes.
     *
     * @param gains             false when the target gains no minutes (member to own team); then no capacity check
     * @param bookedDuration    the target's planned time that day on all work orders, before the move
     * @param availableDuration target capacity minus booked, before the move
     * @param fits              whether the amount of this request fits the target's capacity
     * @param existingTimePlan  the target's time plan of this work order that day; null when it has none
     */
    public record Target(boolean gains, Duration bookedDuration, Duration availableDuration, boolean fits,
            ExistingTimePlan existingTimePlan) {
    }

    /** @param plannedAfterDuration planned time of this plan after the move, for the amount of this request */
    public record ExistingTimePlan(long timePlanId, Duration plannedDuration, Duration plannedAfterDuration) {
    }

    public record Slots(String outcome, int count, List<Map<String, Object>> conflicts,
            List<Map<String, Object>> pendingAbsenceWarnings, boolean durationExceeded) {
    }
}
