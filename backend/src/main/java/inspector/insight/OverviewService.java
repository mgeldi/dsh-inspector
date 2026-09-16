package inspector.insight;

import inspector.dto.OverviewDto;
import inspector.dto.VocabularyOptions;
import inspector.query.FindingFilters;
import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.OverviewRepository;
import inspector.store.VocabularyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * The dashboard tile board (DESIGN.md §7): tiles, plane mix, top detectors, the two daily
 * chart series, step throughput, and the filter vocabulary the rail needs — over the
 * population the shared {@link InsightFilter} selects. No evidence text anywhere: that is
 * only ever on the findings detail (§4.1).
 *
 * <p>Four of the six numbers take a <i>different</i> WHERE, because the filter has to be
 * rooted in the table each aggregate counts from (§7). That rooting is what
 * {@link FindingFilters} exists for, and it is why this class asks for four {@code Sql}
 * fragments rather than one.
 */
@Service
public final class OverviewService {

    private final OverviewRepository overviewRepository;
    private final VocabularyService vocabularyService;

    public OverviewService(
            final OverviewRepository overviewRepository, final VocabularyService vocabularyService) {
        this.overviewRepository = overviewRepository;
        this.vocabularyService = vocabularyService;
    }

    public OverviewDto overview(final InsightFilter filter) {
        final Vocabulary vocabulary = vocabularyService.vocabulary();
        filter.validate(vocabulary);

        final FindingFilters filters = new FindingFilters(filter);
        final FindingFilters.Sql findings = filters.forFinding();
        final FindingFilters.Sql sessions = filters.forSession();
        final FindingFilters.Sql toolCalls = filters.forToolCall();
        final FindingFilters.Sql steps = filters.forStep();

        final OverviewDto.Tiles tiles = new OverviewDto.Tiles(
                overviewRepository.sessionCount(sessions),
                overviewRepository.findingCount(findings),
                overviewRepository.toolCallCount(toolCalls),
                overviewRepository.stepCount(steps));

        final Map<String, Long> planeMix = new LinkedHashMap<>();
        for (final OverviewRepository.PlaneMixRow row : overviewRepository.planeMix(findings)) {
            planeMix.put(row.plane(), row.count());
        }

        final List<OverviewDto.DetectorCount> topDetectors = new ArrayList<>();
        for (final OverviewRepository.DetectorCountRow row : overviewRepository.topDetectors(findings)) {
            topDetectors.add(new OverviewDto.DetectorCount(row.detector(), row.count()));
        }

        return new OverviewDto(
                tiles,
                planeMix,
                topDetectors,
                mergeSeries(overviewRepository.findingSeries(findings),
                        overviewRepository.toolCallSeries(toolCalls)),
                overviewRepository.throughput(steps),
                wireVocabulary(vocabulary));
    }

    /**
     * The domain vocabulary as it crosses the wire. The session ids are dropped here rather than
     * annotated away inside the record, because the record is what the filter contract validates
     * against and the payload is what a dashboard pays for: the two lists are allowed to differ,
     * and the place they differ should be a line of code, not a serializer's surprise.
     */
    private static VocabularyOptions wireVocabulary(final Vocabulary vocabulary) {
        return new VocabularyOptions(
                vocabulary.schemas(),
                vocabulary.models(),
                vocabulary.presets(),
                vocabulary.harnessVersions(),
                vocabulary.codes(),
                vocabulary.detectors());
    }

    /**
     * The two chart series as daily buckets: findings and tool calls per UTC day, merged in
     * day order; days with neither are absent.
     */
    static List<OverviewDto.DayPoint> mergeSeries(
            final List<OverviewRepository.SeriesPointRow> findings,
            final List<OverviewRepository.SeriesPointRow> toolCalls) {
        final Map<String, long[]> byDay = new TreeMap<>();
        for (final OverviewRepository.SeriesPointRow row : findings) {
            byDay.computeIfAbsent(row.day(), day -> new long[2])[0] += row.count();
        }
        for (final OverviewRepository.SeriesPointRow row : toolCalls) {
            byDay.computeIfAbsent(row.day(), day -> new long[2])[1] += row.count();
        }
        final List<OverviewDto.DayPoint> series = new ArrayList<>();
        byDay.forEach((day, counts) -> series.add(new OverviewDto.DayPoint(day, counts[0], counts[1])));
        return series;
    }
}
