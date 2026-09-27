package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import inspector.dto.OverviewDto;
import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.OverviewRepository;
import inspector.store.VocabularyService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dashboard's one piece of real arithmetic: two SQL series become one chart, day by day.
 *
 * <p>The shared filter is rooted per table (§7) — the window means finding event time, session
 * start, call start and step start — and that rooting now lives in the repository, where the
 * Criteria query knows its root; {@code OverviewRootingTest} proves it against real rows. What
 * is left here is that every tile is asked with the filter the caller sent, unchanged.
 */
class OverviewServiceTest {

    private static final InsightFilter NOTHING_SELECTED =
            InsightFilter.none();

    private final OverviewRepository repository = mock(OverviewRepository.class);
    private final OverviewService service = new OverviewService(repository, vocabulary(), ReadSnapshot.none());

    @Test
    void theTwoSeriesMergeInDayOrderAndDaysWithNothingAreAbsent() {
        final List<OverviewDto.DayPoint> merged = OverviewService.mergeSeries(
                List.of(new OverviewRepository.SeriesPointRow("2026-09-03", 1),
                        new OverviewRepository.SeriesPointRow("2026-09-01", 2)),
                List.of(new OverviewRepository.SeriesPointRow("2026-09-02", 7),
                        new OverviewRepository.SeriesPointRow("2026-09-01", 3)));

        assertThat(merged).containsExactly(
                new OverviewDto.DayPoint("2026-09-01", 2, 3),
                new OverviewDto.DayPoint("2026-09-02", 0, 7),
                new OverviewDto.DayPoint("2026-09-03", 1, 0));
        assertThat(merged).extracting(OverviewDto.DayPoint::day).isSorted();
    }

    @Test
    void repeatedRowsForOneDaySumIntoThatDay() {
        assertThat(OverviewService.mergeSeries(
                List.of(new OverviewRepository.SeriesPointRow("2026-09-01", 2),
                        new OverviewRepository.SeriesPointRow("2026-09-01", 5)),
                List.of()))
                .containsExactly(new OverviewDto.DayPoint("2026-09-01", 7, 0));
    }

    @Test
    void anEmptyPairOfSeriesIsAnEmptyChartNotADayOfZeros() {
        assertThat(OverviewService.mergeSeries(List.of(), List.of())).isEmpty();
    }

    @Test
    void everyTileIsAskedWithTheFilterTheCallerSent() {
        when(repository.sessionCount(any())).thenReturn(11L);
        when(repository.findingCount(any())).thenReturn(9L);
        when(repository.toolCallCount(any())).thenReturn(32L);
        when(repository.stepCount(any())).thenReturn(18L);
        when(repository.planeMix(any())).thenReturn(List.of());
        when(repository.topDetectors(any())).thenReturn(List.of());
        when(repository.findingSeries(any())).thenReturn(List.of());
        when(repository.toolCallSeries(any())).thenReturn(List.of());
        when(repository.throughput(any())).thenReturn(List.of());

        final InsightFilter window = new InsightFilter(
                1_700_000_000_000L, 1_800_000_000_000L, null, null, null, null, null, null);
        final OverviewDto dto = service.overview(window);

        assertThat(dto.tiles()).isEqualTo(new OverviewDto.Tiles(11, 9, 32, 18));
        verify(repository).sessionCount(window);
        verify(repository).findingCount(window);
        verify(repository).toolCallCount(window);
        verify(repository).stepCount(window);
        verify(repository).uncodedCount(window);
    }

    /**
     * The rail is built from what the index contains (§7), so the response carries it: the
     * screen would otherwise offer values that are not there and reject the ones that are.
     */
    @Test
    void theResponseCarriesTheVocabularyItWasValidatedAgainst() {
        when(repository.sessionCount(any())).thenReturn(0L);
        when(repository.findingCount(any())).thenReturn(0L);
        when(repository.toolCallCount(any())).thenReturn(0L);
        when(repository.stepCount(any())).thenReturn(0L);
        when(repository.planeMix(any())).thenReturn(List.of());
        when(repository.topDetectors(any())).thenReturn(List.of());
        when(repository.findingSeries(any())).thenReturn(List.of());
        when(repository.toolCallSeries(any())).thenReturn(List.of());
        when(repository.throughput(any())).thenReturn(List.of());

        assertThat(service.overview(NOTHING_SELECTED).vocabulary().detectors())
                .containsExactly("stamp-guard");
    }

    private static VocabularyService vocabulary() {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(new Vocabulary(
                List.of("V0", "V3"), List.of("model-a", "unknown"), List.of("default"),
                List.of("0.1.5-rc.2"), List.of("FS_STALE_VERSION"),
                List.of("stamp-guard"), List.of("demo-local"), List.of("orchestrator"), List.of("s-01")));
        return service;
    }
}
