package org.acme.planning.domain;

import java.time.*;
import java.util.*;
import org.acme.planning.domain.Models.Interval;

public final class Policy {
    private Policy() {
    }

    public static int minutes(String value, boolean zero) {
        try {
            var d = Duration.parse(value);
            long n = d.toMinutes();
            if (d.getNano() != 0 || d.getSeconds() % 60 != 0 || n < (zero ? 0 : 1) || n > 525600) {
                throw new IllegalArgumentException();
            }
            return Math.toIntExact(n);
        } catch (Exception e) {
            throw Problem.bad("Duration must be ISO 8601, whole minutes, " + (zero ? "0" : "1") + "–525600 minutes");
        }
    }

    public static List<Interval> union(List<Interval> intervals) {
        var sorted = intervals.stream().filter(x -> x.end().isAfter(x.start()))
                .sorted(Comparator.comparing(Interval::start)).toList();
        var result = new ArrayList<Interval>();
        for (var interval : sorted) {
            if (result.isEmpty() || result.getLast().end().isBefore(interval.start())) {
                result.add(interval);
            } else {
                var previous = result.removeLast();
                result.add(new Interval(previous.id(), previous.start(),
                        previous.end().isAfter(interval.end()) ? previous.end() : interval.end(), previous.reason(),
                        previous.workOrderId()));
            }
        }
        return result;
    }

    public static int ceil15(int n) {
        return ((n + 14) / 15) * 15;
    }

    public static boolean overlap(Instant a, Instant b, Instant c, Instant d) {
        return a.isBefore(d) && c.isBefore(b);
    }

    public static void grid(Instant start, Instant end, LocalDate day, ZoneId zone) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new Problem(400, "INVALID_SLOT", "Slot end must follow start");
        }
        var a = start.atZone(zone);
        var b = end.atZone(zone);
        if (!a.toLocalDate().equals(day) || !b.toLocalDate().equals(day)) {
            throw new Problem(400, "INVALID_SLOT", "Slots must stay within the plan business day");
        }
        if (a.getMinute() % 15 != 0 || b.getMinute() % 15 != 0 || a.getSecond() != 0 || b.getSecond() != 0
                || a.getNano() != 0 || b.getNano() != 0) {
            throw new Problem(400, "INVALID_SLOT", "Slots require a 15 minute grid");
        }
    }
}
