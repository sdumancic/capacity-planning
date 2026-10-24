package org.acme.planning.application;

/** A Hub adapter maps a non-success HTTP response to this exception. */
public final class HubCalendarException extends RuntimeException {
    private final int statusCode;

    public HubCalendarException(int statusCode) {
        super("Hub Calendar returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
