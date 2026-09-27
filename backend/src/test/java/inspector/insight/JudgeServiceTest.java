package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import inspector.dto.JudgeDto;
import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.CohortRepository;
import inspector.store.CohortRepository.SessionCalls;
import inspector.store.CohortRepository.SessionFindings;
import inspector.store.VocabularyService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The judge's reading of clustered evidence, over hand-built cohort tables. */
final class JudgeServiceTest {

    private final CohortRepository repository = mock(CohortRepository.class);
    private final JudgeService service = new JudgeService(repository, vocabulary(), ReadSnapshot.none());

    /**
     * Ten findings from one session that has no observed call in the selection — a turn that died
     * before its first tool call, say — are one place, not ten independent observations. Left out
     * of the dispersion, they made the candidate "worse" with an interval that excluded 1.
     */
    @Test
    void findingsFromASessionWithNoCallsCountAsTheBurstTheyAre() {
        final List<SessionCalls> calls = new ArrayList<>();
        final List<SessionFindings> found = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(new SessionCalls("v1", "a-" + i, 100));
            calls.add(new SessionCalls("v2", "b-" + i, 100));
            found.add(new SessionFindings("v1", "a-" + i, "INFRASTRUCTURE", "TIMEOUT", 1));
        }
        found.add(new SessionFindings("v2", "b-dead", "INFRASTRUCTURE", "TIMEOUT", 30));
        when(repository.cohorts(eq("harnessVersion"), any())).thenReturn(new CohortRepository.Result(List.of(
                new CohortRepository.Cohort("v1", 10, 1_000, 10, 0, 0, 10),
                new CohortRepository.Cohort("v2", 11, 1_000, 30, 0, 0, 30)), true));
        when(repository.callsPerSession(eq("harnessVersion"), any())).thenReturn(calls);
        when(repository.findingsPerSession(eq("harnessVersion"), any())).thenReturn(found);
        when(repository.codeCounts(eq("harnessVersion"), any())).thenReturn(List.of(
                new CohortRepository.CodeCount("v1", "TIMEOUT", 10), new CohortRepository.CodeCount("v2", "TIMEOUT", 30)));

        final JudgeDto judge = service.judge(InsightFilter.none(), "harnessVersion", "v1", "v2");

        final JudgeDto.Row total = judge.rows().getFirst();
        assertThat(total.rateRatio()).isEqualTo(3.0);
        assertThat(total.candidateSessions()).as("one session carried all of it").isEqualTo(1);
        // the burst side's variance is ~36× Poisson, the even side's 1×; weighted by their counts
        // that is ~9.8 on the interval — enough to take a threefold ratio back to inconclusive
        assertThat(total.dispersion()).isGreaterThan(5.0);
        assertThat(total.verdict()).isEqualTo("inconclusive");
    }

    private static JudgeService.Side side(final double phi, final long count, final long contributing, final long units) {
        return new JudgeService.Side(phi, count, contributing, units);
    }

    private static RateRatio.Verdict verdict(final long baseCount, final long candCount,
                                             final JudgeService.Side base, final JudgeService.Side cand) {
        final JudgeService.Spread spread = JudgeService.spread(base, cand);
        return RateRatio.compare(baseCount, 1_000, candCount, 1_000, spread.phi(), spread.degreesOfFreedom()).verdict();
    }

    /**
     * The interval takes each side's own clustering: an evenly spread side must not dilute a clustered
     * one, and two even sides leave the plain Poisson interval.
     */
    @Test
    void eachSideIsWeighedByItsOwnCount() {
        assertThat(JudgeService.spread(side(1.0, 40, 20, 30), side(1.0, 60, 25, 30)).phi()).isEqualTo(1.0);
        // (1/10 + 36/30) / (1/10 + 1/30) = 9.75
        assertThat(JudgeService.spread(side(1.0, 10, 5, 10), side(36.0, 30, 5, 10)).phi())
                .isCloseTo(9.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(JudgeService.spread(side(4.0, 0, 0, 5), side(4.0, 0, 0, 5)).phi()).isEqualTo(1.0);
    }

    /**
     * A side whose findings all sit in one session is one observation, however many findings it
     * holds: fifteen from one of forty conversations against none is not a regression the judge can
     * call. The Haldane half on the empty side used to dominate the variance.
     */
    @Test
    void oneSessionsBurstAgainstNothingIsNotAVerdict() {
        final long[] calls = new long[40];
        java.util.Arrays.fill(calls, 100);
        final long[] burst = new long[40];
        burst[7] = 15;
        final JudgeService.Spread spread = JudgeService.spread(side(1.0, 0, 0, 40),
                side(RateRatio.dispersion(burst, calls), 15, 1, 40));

        assertThat(spread.degreesOfFreedom()).isZero();
        assertThat(RateRatio.compare(0, 4_000, 15, 4_000, spread.phi(), spread.degreesOfFreedom()).verdict())
                .isEqualTo(RateRatio.Verdict.INCONCLUSIVE);
        assertThat(RateRatio.compare(0, 4_000, 15, 4_000, spread.phi()).verdict())
                .as("what the normal quantile said").isEqualTo(RateRatio.Verdict.WORSE);
    }

    /**
     * An empty side cannot show how the failure spreads, so it spreads like the side that can. With
     * the Poisson 1 on the empty side, twenty findings from two sessions — or ten from three —
     * against none were a "worse" verdict, and so were most bursts in a simulation with no
     * difference at all.
     */
    @Test
    void anEmptySideSpreadsLikeTheOtherAndABurstInTwoOrThreeSessionsDecidesNothing() {
        final long[] calls = new long[40];
        java.util.Arrays.fill(calls, 100);
        final long[] two = new long[40];
        two[1] = 10;
        two[2] = 10;
        final JudgeService.Spread spread = JudgeService.spread(side(1.0, 0, 0, 40),
                side(RateRatio.dispersion(two, calls), 20, 2, 40));
        assertThat(spread.phi()).as("the burst's φ, on both sides").isGreaterThan(9.0);
        assertThat(verdict(0, 20, side(1.0, 0, 0, 40), side(RateRatio.dispersion(two, calls), 20, 2, 40)))
                .isEqualTo(RateRatio.Verdict.INCONCLUSIVE);

        final long[] three = new long[40];
        three[1] = 4;
        three[2] = 3;
        three[3] = 3;
        assertThat(verdict(0, 10, side(1.0, 0, 0, 40), side(RateRatio.dispersion(three, calls), 10, 3, 40)))
                .isEqualTo(RateRatio.Verdict.INCONCLUSIVE);
    }

    /** A cohort of one session cannot estimate its own spread; its burst decides nothing either. */
    @Test
    void aOneSessionCohortIsNeverDecisive() {
        assertThat(JudgeService.spread(side(1.0, 12, 10, 30), side(1.0, 10, 1, 1)).degreesOfFreedom()).isZero();
    }

    /**
     * The interval never rests on more degrees of freedom than the sessions that contributed, less
     * the two rates. An estimate from few sessions comes out low exactly when the interval most needs
     * it; without the cap, six sessions a side called a difference that was not there in 7–12% of
     * simulated comparisons.
     */
    @Test
    void theDegreesOfFreedomNeverExceedTheContributingSessionsLessTwo() {
        assertThat(JudgeService.spread(side(1.0, 60, 30, 40), side(1.0, 20, 15, 40)).degreesOfFreedom())
                .as("spread as chance spreads it: the cap alone").isEqualTo(43.0);
        assertThat(JudgeService.spread(side(1.0, 8, 6, 20), side(1.0, 9, 6, 20)).degreesOfFreedom()).isEqualTo(10.0);
        assertThat(JudgeService.spread(side(1.0, 0, 0, 20), side(1.0, 1, 1, 20)).degreesOfFreedom())
                .as("one finding in all: no interval").isZero();
        assertThat(JudgeService.spread(side(4.0, 60, 6, 40), side(4.0, 60, 6, 40)).degreesOfFreedom())
                .isLessThanOrEqualTo(10.0);
    }

    /**
     * Findings spread across many sessions keep the verdict the counts support. A code that vanished
     * after a change can be called better, and so can one that fell from twenty to one: one finding is
     * one Poisson event, not an unestimable burst.
     */
    @Test
    void evidenceFromManySessionsKeepsItsVerdictDownToNoneOrOne() {
        assertThat(verdict(20, 0, side(1.2, 20, 10, 20), side(1.0, 0, 0, 20))).isEqualTo(RateRatio.Verdict.BETTER);
        final long[] calls = new long[20];
        java.util.Arrays.fill(calls, 50);
        final long[] one = new long[20];
        one[3] = 1;
        assertThat(verdict(20, 1, side(1.2, 20, 10, 20), side(RateRatio.dispersion(one, calls), 1, 1, 20)))
                .isEqualTo(RateRatio.Verdict.BETTER);
        assertThat(JudgeService.spread(side(1.0, 0, 0, 5), side(1.0, 0, 0, 5)).degreesOfFreedom()).isInfinite();
    }

    /**
     * A side whose findings sit in one session borrows the other side's spread rather than stopping
     * the comparison: two findings in one conversation against a thousand across thirty is decided.
     */
    @Test
    void aOneSessionSideAgainstOverwhelmingEvidenceIsStillDecided() {
        assertThat(verdict(2, 1_000, side(2.0, 2, 1, 30), side(3.0, 1_000, 30, 30))).isEqualTo(RateRatio.Verdict.WORSE);
    }

    private static VocabularyService vocabulary() {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(new Vocabulary(List.of(), List.of(), List.of(),
                List.of("v1", "v2"), List.of(), List.of(), List.of(), List.of(), List.of()));
        return service;
    }
}
