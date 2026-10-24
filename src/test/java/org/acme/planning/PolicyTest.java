package org.acme.planning;

import org.junit.jupiter.api.Test;
import org.acme.planning.domain.Policy;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    @Test
    void unionMergesAdjacentAndOverlappingShifts() {
        var a = Instant.EPOCH;
        var shifts = java.util.List.of(new org.acme.planning.domain.Models.Interval(1L, a, a.plusSeconds(3600), "SHIFT", null), new org.acme.planning.domain.Models.Interval(2L, a.plusSeconds(3600), a.plusSeconds(7200), "SHIFT", null), new org.acme.planning.domain.Models.Interval(3L, a.plusSeconds(1800), a.plusSeconds(5400), "SHIFT", null));
        var merged = Policy.union(shifts);
        assertEquals(1, merged.size());
        assertEquals(a.plusSeconds(7200), merged.getFirst().end());
    }

    @Test
    void exactMinutesOnly() {
        assertEquals(97, Policy.minutes("PT1H37M", false));
        assertThrows(RuntimeException.class, () -> Policy.minutes("PT1.5S", false));
        assertThrows(RuntimeException.class, () -> Policy.minutes("PT0S", false));
    }

    @Test
    void rounding() {
        assertEquals(105, Policy.ceil15(97));
        assertEquals(0, Policy.ceil15(0));
    }

    @Test
    void touchingIntervalsDoNotOverlap() {
        assertFalse(Policy.overlap(Instant.EPOCH, Instant.EPOCH.plusSeconds(900), Instant.EPOCH.plusSeconds(900), Instant.EPOCH.plusSeconds(1800)));
    }

    @Test
    void gridAndDayAreBusinessLocal() {
        var z = ZoneId.of("Europe/Zagreb");
        Policy.grid(Instant.parse("2026-10-26T06:00:00Z"), Instant.parse("2026-10-26T06:15:00Z"), LocalDate.parse("2026-10-26"), z);
        assertThrows(RuntimeException.class, () -> Policy.grid(Instant.parse("2026-10-26T06:01:00Z"), Instant.parse("2026-10-26T06:15:00Z"), LocalDate.parse("2026-10-26"), z));
    }
    @Test void midnightEndBelongsToFollowingBusinessDay() {
        var day = LocalDate.of(2026,10,26); var zone = ZoneId.of("Europe/Zagreb");
        var start = day.atTime(23,45).atZone(zone).toInstant();
        var end = day.plusDays(1).atStartOfDay(zone).toInstant();
        var error = assertThrows(org.acme.planning.domain.Problem.class,() -> Policy.grid(start,end,day,zone));
        assertEquals("INVALID_SLOT",error.code);
    }

}
