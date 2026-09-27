package com.routeriskadvisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point for the Route Risk Advisor.
 *
 * <p>The Route Risk Advisor classifies the risk of driving routes within Miami-Dade County,
 * Florida and recommends appropriate car insurance coverage. All external data access is
 * performed behind provider abstraction interfaces; this version ships only deterministic
 * placeholder implementations.
 */
@SpringBootApplication
public class RouteRiskAdvisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteRiskAdvisorApplication.class, args);
    }
}
