package org.acme.planning.domain;

import java.time.*;
import java.util.*;

public final class Models {
    private Models() {
    }

    public record Slot(Long id, Instant start, Instant end, String hubStatus) {
        public Slot(Long id, Instant start, Instant end) {
            this(id, start, end, "PENDING");
        }

        public int minutes() {
            if (start == null || end == null || !end.isAfter(start)) {
                throw Problem.bad("Slot requires start and end, with end after start");
            }
            try {
                return Math.toIntExact(Duration.between(start, end).toMinutes());
            } catch (ArithmeticException e) {
                throw Problem.bad("Slot duration is out of range");
            }
        }
    }

    public record Plan(long id, int workOrderId, int teamId, Integer memberId, LocalDate day, int plannedMinutes,
            int version, List<Slot> slots) {
        public int scheduled() {
            return slots.stream().mapToInt(Slot::minutes).sum();
        }

        public Plan change(int team, Integer member, int minutes, List<Slot> next) {
            return new Plan(id, workOrderId, team, member, day, minutes, version, next);
        }
    }

    public record Order(int id, int dealerId, String title, Instant dropoffAt, Instant pickupAt) {
    }

    public record Assignee(int teamId, Integer memberId, int dealerId, Integer userId, boolean enabled) {
    }

    public record Interval(Long id, Instant start, Instant end, String reason, Integer workOrderId) {
    }

    public record Capacity(int shiftMinutes, int blockedMinutes, int attendedMinutes, Integer productivityPercent,
            int capacityMinutes, int bookedMinutes, int availableMinutes, int pendingMinutes) {
        public Capacity(int capacityMinutes, int bookedMinutes, int availableMinutes) {
            this(capacityMinutes, 0, capacityMinutes, 100, capacityMinutes, bookedMinutes, availableMinutes, 0);
        }
    }

    public record Create(Integer workOrderId, Integer teamId, Integer memberId, LocalDate day, String duration,
            Boolean allowOverbooking, Integer expectedVersion) {
    }

    public record Change(Integer expectedVersion, String plannedDuration, Boolean allowOverbooking) {
    }

    public record Schedule(Long timePlanId, Integer expectedVersion, List<Slot> slots) {
    }

    public record Move(Integer expectedVersion, Integer targetTeamId, Integer targetMemberId, String duration,
            List<Slot> slots, String slotHandling, Boolean allowOverbooking, Instant start, Instant end) {
    }
}
