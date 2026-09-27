package com.routeriskadvisor.config;

import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.RoutingProvider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup validator that halts the application if the provider configuration does not resolve
 * cleanly (Req 5.7).
 *
 * <p>Each of the five provider interfaces ({@link GeocodingProvider}, {@link RoutingProvider},
 * {@link CrashDataProvider}, {@link CrimeDataProvider}, {@link FireDataProvider}) is bound to a
 * concrete implementation through the {@code route-risk-advisor.providers.*} configuration keys and
 * Spring {@code @ConditionalOnProperty} (see {@link ProviderConfiguration}). When a configured
 * implementation name matches no registered bean — for example a future {@code google} geocoding
 * value that ships no corresponding bean — Spring silently registers <em>no</em> bean for that
 * interface rather than failing on its own. This validator closes that gap: after all singletons are
 * instantiated it confirms that each interface resolves to exactly one instantiable bean, and
 * otherwise throws so that startup halts before any route request can be processed.
 *
 * <p>The thrown {@link ProviderConfigurationException} names both the interface and the unresolved
 * implementation (the value read from {@code route-risk-advisor.providers.*}, or {@code placeholder}
 * when the key is absent), satisfying the naming requirement of Req 5.7.
 *
 * <p>Implemented as a {@link SmartInitializingSingleton} so the check runs during context
 * refresh, after conditional beans have been resolved; throwing here aborts
 * {@code SpringApplication.run} with the descriptive cause.
 */
@Component
public class ProviderStartupValidator implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final Environment environment;

    public ProviderStartupValidator(ListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * Verifies every provider interface resolves to exactly one bean; throws
     * {@link ProviderConfigurationException} naming the interface and its configured (unresolved)
     * implementation otherwise.
     */
    void validate() {
        for (ProviderBinding binding : ProviderBinding.values()) {
            String configuredImplementation = configuredImplementation(binding);
            int beanCount = beanFactory.getBeanNamesForType(binding.interfaceType()).length;

            if (beanCount == 0) {
                throw new ProviderConfigurationException(
                    "No implementation could be resolved for provider interface '"
                        + binding.interfaceType().getName()
                        + "' (configuration key '" + binding.configKey()
                        + "' = '" + configuredImplementation
                        + "'): the configured implementation '" + configuredImplementation
                        + "' is not available. Startup halted.");
            }

            if (beanCount > 1) {
                throw new ProviderConfigurationException(
                    "Provider interface '" + binding.interfaceType().getName()
                        + "' (configuration key '" + binding.configKey()
                        + "' = '" + configuredImplementation
                        + "') resolved to " + beanCount
                        + " beans; exactly one instantiable bean is required. Startup halted.");
            }
        }
    }

    /**
     * Returns the configured implementation name for a binding, defaulting to {@code placeholder}
     * when the key is absent (mirroring {@code matchIfMissing = true} in
     * {@link ProviderConfiguration}).
     */
    private String configuredImplementation(ProviderBinding binding) {
        return environment.getProperty(binding.configKey(), "placeholder");
    }

    /**
     * The five interface-to-configuration-key bindings validated at startup.
     */
    enum ProviderBinding {
        GEOCODING(GeocodingProvider.class, "route-risk-advisor.providers.geocoding"),
        ROUTING(RoutingProvider.class, "route-risk-advisor.providers.routing"),
        CRASH(CrashDataProvider.class, "route-risk-advisor.providers.crash"),
        CRIME(CrimeDataProvider.class, "route-risk-advisor.providers.crime"),
        FIRE(FireDataProvider.class, "route-risk-advisor.providers.fire");

        private final Class<?> interfaceType;
        private final String configKey;

        ProviderBinding(Class<?> interfaceType, String configKey) {
            this.interfaceType = interfaceType;
            this.configKey = configKey;
        }

        Class<?> interfaceType() {
            return interfaceType;
        }

        String configKey() {
            return configKey;
        }
    }

    /** Convenience view of the five bindings for tests and diagnostics. */
    static List<Class<?>> validatedInterfaces() {
        List<Class<?>> interfaces = new ArrayList<>();
        for (ProviderBinding binding : ProviderBinding.values()) {
            interfaces.add(binding.interfaceType());
        }
        return interfaces;
    }
}
