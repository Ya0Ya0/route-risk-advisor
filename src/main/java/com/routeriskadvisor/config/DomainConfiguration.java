package com.routeriskadvisor.config;

import com.routeriskadvisor.domain.DefaultInsuranceAdvisor;
import com.routeriskadvisor.domain.DefaultRouteClassifier;
import com.routeriskadvisor.domain.InsuranceAdvisor;
import com.routeriskadvisor.domain.RiskScorer;
import com.routeriskadvisor.domain.RouteClassifier;
import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the pure domain collaborators as Spring beans so the application services can inject
 * them. The domain classes ({@link ServiceAreaValidator}, {@link RiskScorer},
 * {@link DefaultRouteClassifier}, {@link DefaultInsuranceAdvisor}) intentionally carry no Spring
 * stereotype — they depend only on the provider interfaces (Req 5.6) — so their bean wiring lives
 * here, alongside {@link ProviderConfiguration} which supplies the provider beans they consume.
 *
 * <p>Also enables {@link TimeoutProperties} binding for the {@code route-risk-advisor.timeouts.*}
 * tree used by the service layer to enforce per-step budgets.
 *
 * <p>Every bean here is guarded with {@link ConditionalOnMissingBean} so this configuration is
 * additive: if another configuration (e.g. one introduced for the safest-route flow) already
 * contributes an equivalent bean, that one wins and no duplicate-bean conflict occurs.
 */
@Configuration
@EnableConfigurationProperties(TimeoutProperties.class)
public class DomainConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceAreaValidator serviceAreaValidator() {
        return new ServiceAreaValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public RiskScorer riskScorer() {
        return new RiskScorer();
    }

    @Bean
    @ConditionalOnMissingBean
    public RouteClassifier routeClassifier(
        CrashDataProvider crashDataProvider,
        CrimeDataProvider crimeDataProvider,
        FireDataProvider fireDataProvider,
        RiskScorer riskScorer
    ) {
        return new DefaultRouteClassifier(crashDataProvider, crimeDataProvider, fireDataProvider, riskScorer);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsuranceAdvisor insuranceAdvisor() {
        return new DefaultInsuranceAdvisor();
    }
}
