package com.routeriskadvisor.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Architecture test enforcing the pluggable data-provider abstraction constraint (Req 5.1, 5.6).
 *
 * <p>The domain orchestration components — {@code DefaultRouteClassifier} (Route_Classifier),
 * {@code DefaultRouteFinder} (Route_Finder), {@code DefaultInsuranceAdvisor} (Insurance_Advisor),
 * and {@code RiskScorer} — must reach external data exclusively through the provider
 * <em>interfaces</em> in {@code com.routeriskadvisor.provider}. They must never depend on any
 * concrete provider implementation (the placeholder implementations in
 * {@code com.routeriskadvisor.provider.placeholder}, and by extension any future real
 * implementation). This is what allows the provider implementation for each interface to be
 * chosen through configuration alone, without editing the source of these components (Req 5.6).
 */
class AbstractionConstraintArchitectureTest {

    /** Package holding concrete provider implementations that the domain must not depend on. */
    private static final String PLACEHOLDER_PACKAGE = "com.routeriskadvisor.provider.placeholder..";

    /** Fully qualified names of the four domain components subject to the abstraction constraint. */
    private static final String[] CONSTRAINED_DOMAIN_COMPONENTS = {
        "com.routeriskadvisor.domain.DefaultRouteClassifier",
        "com.routeriskadvisor.domain.DefaultRouteFinder",
        "com.routeriskadvisor.domain.DefaultInsuranceAdvisor",
        "com.routeriskadvisor.domain.RiskScorer",
    };

    /**
     * Imports the production classes only (test classes are excluded) so the rule reasons about
     * the shipped dependency graph.
     */
    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.routeriskadvisor");
    }

    @Test
    void domainComponentsDoNotDependOnConcreteProviderImplementations() {
        ArchRule rule = noClasses()
            .that().haveFullyQualifiedName(CONSTRAINED_DOMAIN_COMPONENTS[0])
            .or().haveFullyQualifiedName(CONSTRAINED_DOMAIN_COMPONENTS[1])
            .or().haveFullyQualifiedName(CONSTRAINED_DOMAIN_COMPONENTS[2])
            .or().haveFullyQualifiedName(CONSTRAINED_DOMAIN_COMPONENTS[3])
            .should().dependOnClassesThat().resideInAPackage(PLACEHOLDER_PACKAGE)
            .because(
                "Route_Classifier, Route_Finder, Insurance_Advisor, and RiskScorer must depend "
                    + "only on provider interfaces, never on concrete provider implementations "
                    + "(Req 5.1, 5.6)");

        rule.check(productionClasses());
    }
}
