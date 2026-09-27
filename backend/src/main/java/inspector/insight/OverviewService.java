package inspector.insight;

import inspector.dto.BreakdownDto;
import inspector.dto.OverviewDto;
import inspector.dto.VocabularyOptions;
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
 * <p>The four tiles take a <i>different</i> WHERE each, because the filter has to be rooted in the
 * table each aggregate counts from (§7) — a finding's event time, a session's start, a call's
 * start, a step's start. The repository roots it; this class hands every read the same filter.
 */
@Service
public final class OverviewService {

    /**
     * How many codes the board shows. Enough to cover what a corpus actually emits without
     * turning a summary panel into a second findings table — the table is one click away,
     * and that click is the point of the list.
     */
    private static final int TOP_CODES = 8;

    private final OverviewRepository overviewRepository;
    private final VocabularyService vocabularyService;
    private final ReadSnapshot snapshot;

    public OverviewService(final OverviewRepository overviewRepository,
                           final VocabularyService vocabularyService, final ReadSnapshot snapshot) {
        this.overviewRepository = overviewRepository;
        this.vocabularyService = vocabularyService;
        this.snapshot = snapshot;
    }

    /** The board, read from one snapshot of the index (ReadSnapshot). */
    public OverviewDto overview(final InsightFilter filter) {
        return snapshot.read(() -> board(filter));
    }

    private OverviewDto board(final InsightFilter filter) {
        final Vocabulary vocabulary = vocabularyService.vocabulary();
        filter.validate(vocabulary);

        final OverviewDto.Tiles tiles = new OverviewDto.Tiles(
                overviewRepository.sessionCount(filter),
                overviewRepository.findingCount(filter),
                overviewRepository.toolCallCount(filter),
                overviewRepository.stepCount(filter));

        final Map<String, Long> planeMix = new LinkedHashMap<>();
        for (final OverviewRepository.PlaneMixRow row : overviewRepository.planeMix(filter)) {
            planeMix.put(row.plane(), row.count());
        }

        final List<OverviewDto.DetectorCount> topDetectors = new ArrayList<>();
        for (final OverviewRepository.DetectorCountRow row : overviewRepository.topDetectors(filter)) {
            topDetectors.add(new OverviewDto.DetectorCount(row.detector(), row.count()));
        }

        final List<OverviewDto.CodeCount> topCodes = new ArrayList<>();
        for (final OverviewRepository.CodeCountRow row : overviewRepository.topCodes(filter, TOP_CODES)) {
            topCodes.add(new OverviewDto.CodeCount(row.code(), row.count()));
        }

        final List<OverviewDto.ThroughputRow> throughput = new ArrayList<>();
        for (final OverviewRepository.ThroughputBucket bucket : overviewRepository.throughput(filter)) {
            // A null median means "this bucket measured nothing", and it crosses the boundary as
            // null: 0 would be a speed the index never measured, and the frontend renders the two
            // differently ("n/a" next to a number a reviewer could disprove).
            throughput.add(new OverviewDto.ThroughputRow(
                    bucket.schema(),
                    bucket.timingSource(),
                    bucket.steps(),
                    bucket.medianDecodeTps(),
                    bucket.medianTtftMs()));
        }

        return new OverviewDto(
                tiles,
                planeMix,
                topDetectors,
                topCodes,
                overviewRepository.uncodedCount(filter),
                mergeSeries(overviewRepository.findingSeries(filter),
                        overviewRepository.toolCallSeries(filter)),
                throughput,
                wireVocabulary(vocabulary));
    }

    /**
     * Findings by kind, with each kind's rate per 1,000 observed calls of the same selection — the
     * denominator a harness change is judged against.
     */
    public List<BreakdownDto> breakdown(final InsightFilter filter) {
        return snapshot.read(() -> kinds(filter));
    }

    private List<BreakdownDto> kinds(final InsightFilter filter) {
        filter.validate(vocabularyService.vocabulary());
        final long calls = overviewRepository.toolCallCount(filter);
        return overviewRepository.breakdown(filter).stream()
                .map(row -> new BreakdownDto(row.detector(), row.plane(), row.category(), row.code(),
                        row.detail(), row.count(), CohortService.rate(row.count(), calls)))
                .toList();
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
                vocabulary.detectors(),
                vocabulary.providers(),
                vocabulary.roles());
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
