package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import inspector.dto.CohortDto;
import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.CohortRepository;
import inspector.store.VocabularyService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one-row cohort (DESIGN.md §7: "the screen states which basis it is showing"). A single
 * stream indexed on its own makes every axis single-valued, and the response has to say so
 * instead of pretending to compare: the answer is a description of one cohort, not a
 * regression analysis.
 *
 * <p>This was an end-to-end test that indexed one fixture stream into its own database to get
 * one row out of SQL. The repository is what decides there is one row, so the repository says
 * it here — the same assertions, the same service, no context and no index. The axis name
 * reaching SQL as the mapped column is asserted too, since that mapping is the whitelist.
 */
class OneRowCohortsTest {

    private static final InsightFilter NOTHING_SELECTED =
            new InsightFilter(null, null, null, null, null, null);

    private final CohortRepository repository = mock(CohortRepository.class);
    private final CohortService service = new CohortService(repository, vocabulary(
            new Vocabulary(List.of("V0"), List.of("model-a"), List.of("default"),
                    List.of("0.1.5-rc.2"), List.of("FS_STALE_VERSION"),
                    List.of("stamp-guard"), List.of("s-01"))));

    @Test
    void oneRowCohortStatesItsBasis() {
        when(repository.cohorts(eq("agent_preset"), any()))
                .thenReturn(new CohortRepository.Result(
                        List.of(new CohortRepository.Cohort("default", 1, 12, 4, 2)), true));

        final CohortDto.Page page = service.cohorts(NOTHING_SELECTED, "preset", null);

        assertThat(page.groupBy()).isEqualTo("preset");
        assertThat(page.cohorts()).hasSize(1);
        final CohortDto only = page.cohorts().getFirst();
        assertThat(only.key()).isEqualTo("default");
        assertThat(only.findingsPerKCalls()).isEqualTo(333.33);
        // the only cohort is its own baseline, so its deltas against itself are zero
        assertThat(page.baseline()).isEqualTo("default");
        assertThat(only.findingsPerKCallsDelta()).isEqualTo(0.0);
        assertThat(only.violationRatePerKDelta()).isEqualTo(0.0);

        assertThat(page.basisNote())
                .contains("one-row cohort")
                .contains("description, not a comparison")
                // the single session is version_inferred, and the baseline was still chosen
                .contains("inferred")
                .contains("chosen by highest tool-call count");
        // and the query value became the mapped column, not the axis name
        verify(repository).cohorts(eq("agent_preset"), any());
    }

    private static VocabularyService vocabulary(final Vocabulary vocabulary) {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(vocabulary);
        return service;
    }
}
