package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pure rate math behind the cohorts screen: rates are findings per
 * 1,000 tool calls, deltas are points on that scale, and a cohort with no
 * tool calls has no rate — null, never 0/0.
 */
class CohortsRateMathTest {

    @Test
    void aCohortWithoutToolCallsHasNoRateAndNoDelta() {
        assertThat(CohortsController.rate(3, 0)).isNull();
        assertThat(CohortsController.rate(0, 0)).isNull();
        assertThat(CohortsController.delta(null, 281.25)).isNull();
        assertThat(CohortsController.delta(333.33, null)).isNull();
        assertThat(CohortsController.delta(null, null)).isNull();
    }

    @Test
    void ratesArePerThousandToolCallsRoundedToTwoDecimals() {
        assertThat(CohortsController.rate(9, 32)).isEqualTo(281.25);
        assertThat(CohortsController.rate(4, 32)).isEqualTo(125.0);
        assertThat(CohortsController.rate(3, 9)).isEqualTo(333.33);
        assertThat(CohortsController.rate(2, 9)).isEqualTo(222.22);
    }

    @Test
    void deltasArePercentagePointsBetweenTheRoundedRates() {
        assertThat(CohortsController.delta(333.33, 281.25)).isEqualTo(52.08);
        assertThat(CohortsController.delta(281.25, 333.33)).isEqualTo(-52.08);
        assertThat(CohortsController.delta(222.22, 125.0)).isEqualTo(97.22);
        assertThat(CohortsController.delta(125.0, 222.22)).isEqualTo(-97.22);
    }
}
