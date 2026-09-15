package inspector.api;

import inspector.api.dto.OverviewDto;
import inspector.api.dto.Vocabulary;
import inspector.store.OverviewRepository;
import inspector.store.VocabularyService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The dashboard tile board (DESIGN.md §7): one GET, the shared
 * {@link InsightFilter} binding, tiles, plane mix, top detectors, the two
 * daily chart series, step throughput, and the filter vocabulary the rail
 * needs. No evidence text — that is only ever on the findings detail.
 */
@RestController
@RequestMapping("/api/overview")
public final class OverviewController {

    private final OverviewRepository overviewRepository;
    private final VocabularyService vocabularyService;

    public OverviewController(
            final OverviewRepository overviewRepository, final VocabularyService vocabularyService) {
        this.overviewRepository = overviewRepository;
        this.vocabularyService = vocabularyService;
    }

    @GetMapping
    public OverviewDto overview(@ModelAttribute final InsightFilter filter) {
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
                mergeSeries(overviewRepository.findingSeries(findings), overviewRepository.toolCallSeries(toolCalls)),
                overviewRepository.throughput(steps),
                vocabulary);
    }

    /**
     * The two chart series as daily buckets: findings and tool calls per UTC
     * day, merged in day order; days with neither are absent.
     */
    private static List<OverviewDto.DayPoint> mergeSeries(
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
