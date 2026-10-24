package org.acme.infrastructure.config;

import java.time.LocalDate;

/** Inclusive supported business dates shared by calendar initialization and API validation. */
public final class CalendarRange {
    public static final LocalDate START = LocalDate.of(2020, 1, 1);
    public static final LocalDate END = LocalDate.of(2040, 12, 31);

    private CalendarRange() {}

    public static boolean contains(LocalDate day) {
        return day != null && !day.isBefore(START) && !day.isAfter(END);
    }
}
