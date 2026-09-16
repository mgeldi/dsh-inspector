package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pure rate math behind the cohorts screen: rates are findings per 1,000 tool calls,
 * deltas are points on that scale, and a cohort with no tool calls has no rate — null,
 * never 0/0.
 *
 * <p>Plain JUnit, no application context. This arithmetic was previously asserted through
 * MockMvc against a booted Spring application, which is an expensive way to learn that
 * {@code Math.round} does what it says. {@code inspector.api.CohortsControllerTest} still
 * proves the same numbers come out of a real two-corpus index and reach the JSON — that is a
 * different claim, and it is the one that needs the context.
 */
class CohortsRateMathTest {

    @Test
    void aCohortWithoutToolCallsHasNoRateAndNoDelta() {
        assertThat(CohortService.rate(3, 0)).isNull();
        assertThat(CohortService.rate(0, 0)).isNull();
        assertThat(CohortService.delta(null, 281.25)).isNull();
        assertThat(CohortService.delta(333.33, null)).isNull();
        assertThat(CohortService.delta(null, null)).isNull();
    }

    @Test
    void ratesArePerThousandToolCallsRoundedToTwoDecimals() {
        assertThat(CohortService.rate(9, 32)).isEqualTo(281.25);
        assertThat(CohortService.rate(4, 32)).isEqualTo(125.0);
        assertThat(CohortService.rate(3, 9)).isEqualTo(333.33);
        assertThat(CohortService.rate(2, 9)).isEqualTo(222.22);
    }

    @Test
    void deltasArePercentagePointsBetweenTheRoundedRates() {
        assertThat(CohortService.delta(333.33, 281.25)).isEqualTo(52.08);
        assertThat(CohortService.delta(281.25, 333.33)).isEqualTo(-52.08);
        assertThat(CohortService.delta(222.22, 125.0)).isEqualTo(97.22);
        assertThat(CohortService.delta(125.0, 222.22)).isEqualTo(-97.22);
    }
}
