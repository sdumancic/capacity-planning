package org.acme.planning.application;

/** Hub calls are suspended by the circuit breaker; the message is released without an attempt. */
public final class HubCircuitOpenException extends RuntimeException {
    public HubCircuitOpenException(Throwable cause) {
        super("Hub Calendar circuit breaker is open", cause);
    }
}
