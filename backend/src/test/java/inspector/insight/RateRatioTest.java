package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.insight.RateRatio.Comparison;
import inspector.insight.RateRatio.Verdict;
import org.junit.jupiter.api.Test;

/** The judge's arithmetic, on numbers worked by hand. */
final class RateRatioTest {

    @Test
    void equalRatesAreInconclusiveWithARatioOfOne() {
        final Comparison c = RateRatio.compare(50, 10_000, 50, 10_000);
        assertThat(c.ratio()).isEqualTo(1.0);
        assertThat(c.low()).isLessThan(1.0);
        assertThat(c.high()).isGreaterThan(1.0);
        assertThat(c.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    /** 100 vs 200 per 10,000: RR 2, log-interval ±1.96·√(1/100 + 1/200) → [1.573, 2.543]. */
    @Test
    void aDoubledRateOnEnoughEventsIsWorse() {
        final Comparison c = RateRatio.compare(100, 10_000, 200, 10_000);
        assertThat(c.ratio()).isEqualTo(2.0);
        assertThat(c.low()).isEqualTo(1.573);
        assertThat(c.high()).isEqualTo(2.543);
        assertThat(c.verdict()).isEqualTo(Verdict.WORSE);
        assertThat(RateRatio.compare(200, 10_000, 100, 10_000).verdict()).isEqualTo(Verdict.BETTER);
    }

    /** The same doubling on a handful of events is not evidence of anything. */
    @Test
    void aDoubledRateOnFewEventsIsInconclusive() {
        assertThat(RateRatio.compare(3, 1_000, 6, 1_000).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void aZeroOnOneSideIsCorrectedRatherThanDividedBy() {
        final Comparison c = RateRatio.compare(0, 1_000, 10, 1_000);
        // Haldane: 0.5 vs 10.5 → RR 21, and an interval that is wide, as it should be
        assertThat(c.ratio()).isEqualTo(21.0);
        assertThat(c.low()).isGreaterThan(1.0);
        assertThat(c.high()).isGreaterThan(300.0);
        assertThat(c.verdict()).isEqualTo(Verdict.WORSE);
    }

    @Test
    void noCallsIsNoDataAndNoEventsOnEitherSideIsInconclusive() {
        assertThat(RateRatio.compare(3, 0, 3, 100).verdict()).isEqualTo(Verdict.NO_DATA);
        assertThat(RateRatio.compare(0, 100, 0, 100))
                .isEqualTo(new Comparison(null, null, null, Verdict.INCONCLUSIVE));
    }

    /**
     * The same counts, clustered: a dispersion of 4 doubles the log-interval's half-width, and a
     * verdict that rested on treating one conversation's burst as independent evidence is gone.
     */
    @Test
    void dispersionWidensTheIntervalAndCanWithdrawAVerdict() {
        final Comparison plain = RateRatio.compare(40, 10_000, 70, 10_000);
        final Comparison clustered = RateRatio.compare(40, 10_000, 70, 10_000, 4.0);
        assertThat(plain.verdict()).isEqualTo(Verdict.WORSE);
        assertThat(clustered.ratio()).isEqualTo(plain.ratio());
        assertThat(Math.log(clustered.high() / clustered.ratio()))
                .isCloseTo(2 * Math.log(plain.high() / plain.ratio()), org.assertj.core.data.Offset.offset(0.01));
        assertThat(clustered.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(RateRatio.compare(40, 10_000, 70, 10_000, 0.3))
                .as("under-dispersion never narrows the interval").isEqualTo(plain);
    }

    /**
     * A session with an event but no observed call — a turn that died before its first tool call —
     * is one event from one place, which is what Poisson expects of one event. The earlier form gave
     * it a one-call exposure and divided by a near-zero expectation, and one isolated fatal turn read
     * as extreme clustering. Ten from one such session still do.
     */
    @Test
    void aLoneEventFromACallLessSessionIsNotABurstButTenAre() {
        final long[] lone = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        final long[] calls = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 0};
        assertThat(RateRatio.dispersion(lone, calls)).isEqualTo(1.0);

        final long[] burst = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 10};
        assertThat(RateRatio.dispersion(burst, calls)).isGreaterThan(4.0);
    }

    /**
     * The quantile the interval uses: Student's t on the degrees of freedom behind the variance, so
     * an estimate from a handful of sessions is not read with the normal distribution's confidence.
     */
    @Test
    void theQuantileIsStudentsTAndReachesTheNormalOneInTheLimit() {
        final org.assertj.core.data.Offset<Double> close = org.assertj.core.data.Offset.offset(0.001);
        assertThat(RateRatio.tQuantile(1)).isCloseTo(12.706, close);
        assertThat(RateRatio.tQuantile(5.9)).as("a fractional df rounds down").isCloseTo(2.571, close);
        assertThat(RateRatio.tQuantile(30)).isCloseTo(2.042, close);
        assertThat(RateRatio.tQuantile(31)).isCloseTo(2.040, close);
        assertThat(RateRatio.tQuantile(60)).isCloseTo(2.000, close);
        assertThat(RateRatio.tQuantile(120)).isCloseTo(1.980, close);
        assertThat(RateRatio.tQuantile(Double.POSITIVE_INFINITY)).isCloseTo(1.960, close);
    }

    @Test
    void fewDegreesOfFreedomWidenTheIntervalAndCanWithdrawAVerdict() {
        final Comparison normal = RateRatio.compare(40, 10_000, 70, 10_000, 1.0);
        final Comparison few = RateRatio.compare(40, 10_000, 70, 10_000, 1.0, 2);
        assertThat(normal.verdict()).isEqualTo(Verdict.WORSE);
        assertThat(few.ratio()).isEqualTo(normal.ratio());
        assertThat(few.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(RateRatio.compare(40, 10_000, 70, 10_000, 1.0, Double.POSITIVE_INFINITY)).isEqualTo(normal);
    }

    /** Events one to a session are not clustered, however the calls fall. */
    @Test
    void oneEventPerSessionIsNeverDispersedEvenInCallLessSessions() {
        assertThat(RateRatio.dispersion(new long[] {1, 0, 0, 0, 0, 0}, new long[] {0, 5, 5, 5, 5, 5})).isEqualTo(1.0);
        assertThat(RateRatio.dispersion(new long[] {1, 1, 0, 1}, new long[] {0, 0, 90, 10})).isEqualTo(1.0);
        assertThat(RateRatio.dispersion(new long[] {2, 0, 0, 0}, new long[] {0, 5, 5, 5})).isGreaterThan(1.0);
    }

    @Test
    void belowOneDegreeOfFreedomThereIsNoIntervalAndNoVerdict() {
        final Comparison none = RateRatio.compare(0, 1_000, 30, 1_000, 30.0, 0.0);
        assertThat(none.ratio()).isNotNull();
        assertThat(none.low()).isNull();
        assertThat(none.high()).isNull();
        assertThat(none.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void evenlySpreadEventsHaveNoExtraDispersionAndOneSessionsBurstHasALot() {
        final long[] calls = {100, 100, 100, 100};
        assertThat(RateRatio.dispersion(new long[] {2, 2, 2, 2}, calls)).isEqualTo(1.0);
        assertThat(RateRatio.dispersion(new long[] {8, 0, 0, 0}, calls)).isEqualTo(8.0);
        assertThat(RateRatio.dispersion(new long[] {8}, new long[] {100}))
                .as("one session cannot show how events spread").isEqualTo(1.0);
    }
}
