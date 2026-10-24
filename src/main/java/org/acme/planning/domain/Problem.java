package org.acme.planning.domain;

import java.util.Map;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, Object> details;
    public Problem(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public Problem(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static Problem bad(String message) {
        return new Problem(400, "INVALID_REQUEST", message);
    }
}
