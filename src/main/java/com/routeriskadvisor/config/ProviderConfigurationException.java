package com.routeriskadvisor.config;

/**
 * Thrown at startup when a provider interface cannot be resolved to exactly one instantiable bean
 * (Req 5.7).
 *
 * <p>The message names the offending interface and the configured implementation that could not be
 * resolved. Because this is thrown from a {@code SmartInitializingSingleton} during context refresh,
 * it propagates out of {@code SpringApplication.run} and halts startup before any route request is
 * processed.
 */
public class ProviderConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProviderConfigurationException(String message) {
        super(message);
    }
}
