package inspector.api.dto;

import java.util.List;
import java.util.Map;

/**
 * The dashboard tile board (DESIGN.md §7): tiles, plane mix, top detectors, the
 * two daily chart series, step throughput grouped by schema and timing source,
 * and the filter vocabulary the rail needs.
 *
 * <p>No evidence text anywhere — that is only ever on {@code /api/findings/{id}}.
 */
public record OverviewDto(
        Tiles tiles,
        Map<String, Long> planeMix,
        List<DetectorCount> topDetectors,
        List<DayPoint> series,
        List<ThroughputRow> throughput,
        Vocabulary vocabulary) {

    /** Headline counters over the filtered index. */
    public record Tiles(long sessions, long findings, long toolCalls, long steps) {
    }

    /** One detector and how many findings it produced, count descending. */
    public record DetectorCount(String detector, long count) {
    }

    /**
     * One UTC day of the two chart series: findings and tool calls.
     * Days with neither are absent.
     */
    public record DayPoint(String day, long findings, long toolCalls) {
    }

    /**
     * Step throughput for one (schema, timing source) bucket. Rates are per
     * 1,000 tool calls on the findings side; here the raw medians are picked
     * from the ordered step rows in the repository (never 0/0 — an empty
     * bucket reads null).
     */
    public record ThroughputRow(
            String schema,
            String timingSource,
            long steps,
            Double medianDecodeTps,
            Double medianTtftMs) {
    }
}
