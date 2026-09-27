package com.routeriskadvisor.provider;

/**
 * Checked exception thrown by provider calls. Carries a discriminator indicating whether the
 * underlying cause was a {@link Kind#TIMEOUT} or a {@link Kind#FAILURE} (failure response), so the
 * service layer can choose the correct degrade-vs-abort policy.
 */
public class ProviderException extends Exception {

    /**
     * Discriminates the cause of a provider failure so callers can distinguish a bounded-wait
     * timeout from an outright failure response.
     */
    public enum Kind {
        /** The provider call exceeded its configured time budget. */
        TIMEOUT,
        /** The provider returned or raised a failure response. */
        FAILURE
    }

    private final Kind kind;

    public ProviderException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }

    public ProviderException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * @return whether this exception was caused by a timeout or a failure response
     */
    public Kind kind() {
        return kind;
    }
}
