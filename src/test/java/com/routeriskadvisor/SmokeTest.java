package com.routeriskadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying that the test suite is wired and non-empty.
 *
 * <p>It exercises both the JUnit 5 (Jupiter) engine and the jqwik property engine to confirm
 * the JUnit Platform runs both. No domain logic is implemented or exercised here.
 */
class SmokeTest {

    @Test
    void junit5EngineRuns() {
        assertThat(1 + 1).isEqualTo(2);
    }

    @Property(tries = 100)
    boolean jqwikEngineRuns(@ForAll @IntRange(min = 0, max = 100) int score) {
        return score >= 0 && score <= 100;
    }
}
