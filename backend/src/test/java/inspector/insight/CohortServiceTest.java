package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import inspector.dto.CohortDto;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.CohortRepository;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.SessionEntity_;
import inspector.store.VocabularyService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cohort domain without an HTTP layer: which cohort becomes the baseline when nobody
 * names one, what the screen is told about the basis it is showing, and what happens to the
 * two values a caller can get wrong ({@code groupBy} and {@code baseline}).
 *
 * <p>All of this was previously reachable only through {@code /api/cohorts} against a booted
 * context and an indexed two-corpus fixture — including the arithmetic, which is why
 * {@code CohortsRateMathTest} exists. The repository is mocked here and says exactly what the
 * SQL would have said; the end-to-end file keeps the claim that SQL and JSON agree.
 */
class CohortServiceTest {

    private static final InsightFilter NOTHING_SELECTED =
            new InsightFilter(null, null, null, null, null, null, null, null);
    private static final InsightFilter WINDOW_SELECTED =
            new InsightFilter(null, 1_700_000_000_000L, null, null, null, null, null, null);

    private final CohortRepository repository = mock(CohortRepository.class);
    private final CohortService service = new CohortService(repository, vocabulary(),
            inspector.TestPipeline.properties("fixtures/sessions", "unknown", true), ReadSnapshot.none());

    @Test
    void theBaselineIsTheCohortWithTheMostToolCallsAndTiesBreakOnKey() {
        assertThat(CohortService.defaultBaseline(List.of(
                new CohortRepository.Cohort("b", 3, 9, 3, 2, 0, 0),
                new CohortRepository.Cohort("a", 11, 32, 9, 4, 0, 0))))
                .isEqualTo("a");
        assertThat(CohortService.defaultBaseline(List.of(
                new CohortRepository.Cohort("v0.2", 1, 9, 1, 1, 0, 0),
                new CohortRepository.Cohort("v0.1", 1, 9, 1, 1, 0, 0))))
                .as("equal tool calls, so the earlier key wins rather than the stream order")
                .isEqualTo("v0.1");
    }

    @Test
    void ratesAndDeltasAreComputedAgainstTheChosenBaseline() {
        given(SessionEntity_.HARNESS_VERSION, false,
                new CohortRepository.Cohort("0.1.5-rc.2", 11, 32, 9, 4, 0, 0),
                new CohortRepository.Cohort("0.1.4", 3, 9, 3, 2, 0, 0));

        final CohortDto.Page page = service.cohorts(NOTHING_SELECTED, "harnessVersion", null);

        assertThat(page.baseline()).isEqualTo("0.1.5-rc.2");
        final CohortDto second = page.cohorts().get(1);
        assertThat(second.findingsPerKCalls()).isEqualTo(333.33);
        assertThat(second.violationRatePerK()).isEqualTo(222.22);
        assertThat(second.findingsPerKCallsDelta()).isEqualTo(52.08);
        assertThat(second.violationRatePerKDelta()).isEqualTo(97.22);
        assertThat(page.basisNote())
                .doesNotContain("one-row")
                .contains("chosen by highest tool-call count");
    }

    @Test
    void anExplicitBaselineFlipsTheDeltasAndStopsClaimingItWasChosen() {
        given(SessionEntity_.HARNESS_VERSION, false,
                new CohortRepository.Cohort("0.1.5-rc.2", 11, 32, 9, 4, 0, 0),
                new CohortRepository.Cohort("0.1.4", 3, 9, 3, 2, 0, 0));

        final CohortDto.Page page = service.cohorts(NOTHING_SELECTED, "harnessVersion", "0.1.4");

        assertThat(page.baseline()).isEqualTo("0.1.4");
        assertThat(page.basisNote())
                .as("two cohorts, no filter, declared versions and a baseline the caller named:"
                        + " there is nothing left to explain, so the note stays absent")
                .isNull();
        final CohortDto main = page.cohorts().getFirst();
        assertThat(main.findingsPerKCallsDelta()).isEqualTo(-52.08);
        assertThat(main.violationRatePerKDelta()).isEqualTo(-97.22);
        assertThat(page.cohorts().get(1).findingsPerKCallsDelta()).isEqualTo(0.0);
    }

    /**
     * A cohort with no observed calls has no rate. It still appears — dropping it would make
     * the axis look shorter than it is — but it gets no delta, because there is nothing to be
     * more or less than.
     */
    @Test
    void aCohortWithNoObservedCallsHasNoRateAndNoDeltas() {
        given(SessionEntity_.HARNESS_VERSION, false,
                new CohortRepository.Cohort("0.1.5-rc.2", 11, 32, 9, 4, 0, 0),
                new CohortRepository.Cohort("0.9.0", 2, 0, 1, 1, 0, 0));

        final CohortDto orphan = service.cohorts(NOTHING_SELECTED, "harnessVersion", null)
                .cohorts().get(1);

        assertThat(orphan.findingsPerKCalls()).isNull();
        assertThat(orphan.violationRatePerK()).isNull();
        assertThat(orphan.findingsPerKCallsDelta()).isNull();
        assertThat(orphan.findingsPerKCalls()).isNull();
    }

    @Test
    void aFilteredPageAnnouncesThatEveryRateBelowIsForTheSubset() {
        given(SessionEntity_.HARNESS_VERSION, false,
                new CohortRepository.Cohort("0.1.5-rc.2", 4, 12, 3, 1, 0, 0));

        assertThat(service.cohorts(WINDOW_SELECTED, "harnessVersion", null).basisNote())
                .contains("shared filters are active")
                .contains("this selection");
    }

    /** Two ways to get an empty table, and the reader needs to know which one happened. */
    @Test
    void anEmptyIndexAndAFilterThatMatchesNothingSayDifferentThings() {
        given(SessionEntity_.HARNESS_VERSION, false);
        assertThat(service.cohorts(NOTHING_SELECTED, "harnessVersion", null).basisNote())
                .isEqualTo("the index is empty");
        assertThat(service.cohorts(WINDOW_SELECTED, "harnessVersion", null).basisNote())
                .isEqualTo("no sessions match the current filters");
        assertThat(service.cohorts(NOTHING_SELECTED, "harnessVersion", null).cohorts()).isEmpty();
    }

    @Test
    void anUnknownAxisIsRejectedWithTheAxesThatExist() {
        assertThatThrownBy(() -> service.cohorts(NOTHING_SELECTED, "1;drop table finding", null))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("groupBy");
                    assertThat(ex.value()).isEqualTo("1;drop table finding");
                    // in the order a person reads them, the same order the 400 prints
                    assertThat(ex.allowed()).containsExactly(
                            "harnessVersion", "model", "provider", "role", "schema", "preset");
                });
    }

    @Test
    void anUnknownBaselineIsRejectedWithTheCohortsThatAreOnScreen() {
        given(SessionEntity_.HARNESS_VERSION, false,
                new CohortRepository.Cohort("0.1.5-rc.2", 11, 32, 9, 4, 0, 0),
                new CohortRepository.Cohort("0.1.4", 3, 9, 3, 2, 0, 0));

        assertThatThrownBy(() -> service.cohorts(NOTHING_SELECTED, "harnessVersion", "9.9.9"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("baseline");
                    assertThat(ex.allowed()).containsExactly("0.1.4", "0.1.5-rc.2");
                });
    }

    /**
     * The axis value is a query parameter that becomes a SQL column name, so the mapping —
     * not the caller's string — has to be what reaches the repository. {@code schema} is the
     * awkward one: it is a quoted column.
     */
    @Test
    void theAxisReachesSqlAsTheMappedColumnAndNeverAsTheQueryValue() {
        given(SessionEntity_.SCHEMA, false, new CohortRepository.Cohort("V0", 8, 20, 5, 3, 0, 0));
        assertThat(service.cohorts(NOTHING_SELECTED, "schema", null).cohorts()).hasSize(1);

        given(SessionEntity_.MODEL, false, new CohortRepository.Cohort("model-a", 8, 20, 5, 3, 0, 0));
        assertThat(service.cohorts(NOTHING_SELECTED, "model", null).cohorts()).hasSize(1);
    }

    /** Every session has an inferred harness version: the screen has to say the axis is a guess. */
    @Test
    void inferredVersionsAreDeclaredInTheBasisNote() {
        given(SessionEntity_.HARNESS_VERSION, true,
                new CohortRepository.Cohort("0.1.5-rc.2", 11, 32, 9, 4, 0, 0),
                new CohortRepository.Cohort("0.1.4", 3, 9, 3, 2, 0, 0));

        assertThat(service.cohorts(NOTHING_SELECTED, "harnessVersion", null).basisNote())
                .contains("harness_version is inferred for every session");
    }

    private void given(final String axisColumn, final boolean allVersionInferred,
            final CohortRepository.Cohort... cohorts) {
        when(repository.cohorts(eq(axisColumn), any()))
                .thenReturn(new CohortRepository.Result(List.of(cohorts), allVersionInferred));
    }

    private VocabularyService vocabulary() {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(new Vocabulary(
                List.of("V0", "V3"), List.of("model-a", "unknown"), List.of("default"),
                List.of("0.1.4", "0.1.5-rc.2"), List.of("FS_STALE_VERSION"),
                List.of("stamp-guard"), List.of(), List.of(), List.of("s-01")));
        return service;
    }
}
