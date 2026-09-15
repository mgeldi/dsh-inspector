package inspector.store;

import inspector.api.FindingFilters;
import inspector.api.dto.OverviewDto;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The dashboard tile board queries (DESIGN.md §7). All queries are
 * read-only and share the one {@link FindingFilters} contract; the time
 * window is rooted at the table each tile measures.
 *
 * <p>Step throughput is grouped by schema and timing source, and the
 * medians are picked from the ordered rows here, never averaged from
 * aggregates: an empty bucket reads null, it does not divide by zero.
 */
@Component
public final class OverviewRepository {

    private static final String STEP_JOIN =
            "from step st join session s on s.id = st.session_id and s.source_file = st.source_file";

    private final JdbcClient jdbc;

    public OverviewRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PlaneMixRow(String plane, long count) {
    }

    public record DetectorCountRow(String detector, long count) {
    }

    /** One daily bucket: the UTC day and a row count. */
    public record SeriesPointRow(String day, long count) {
    }

    public long sessionCount(final FindingFilters.Sql where) {
        // Distinct ids, not rows: the session table is keyed by (id, sourceFile) because a
        // session can hold both log conventions, so count(*) would report 168 streams as 168
        // "sessions" next to a filter rail that lists 165. A tile the user can disprove by
        // looking at their own session list costs credibility in every other number here.
        return SqlSupport.singleLong(jdbc, "select count(distinct s.id) from session s "
                + where.asWhere(), where.params());
    }

    public long findingCount(final FindingFilters.Sql where) {
        return SqlSupport.singleLong(jdbc, "select count(*) " + SqlSupport.FINDING_JOIN + " "
                + where.asWhere(), where.params());
    }

    /**
     * The tool-call tile: observed tool calls only. A {@code tool/result} whose
     * {@code tool/call} never appeared is stored as a row ({@code outcome_only = 1}) so no
     * outcome is lost, but it is not a call — counting it would inflate every rate this
     * tile feeds, differently per convention.
     */
    public long toolCallCount(final FindingFilters.Sql where) {
        return SqlSupport.singleLong(jdbc, "select count(*) " + SqlSupport.TOOL_CALL_JOIN + " "
                + where.with("t.outcome_only = 0").asWhere(), where.params());
    }

    public long stepCount(final FindingFilters.Sql where) {
        return SqlSupport.singleLong(jdbc, "select count(*) " + STEP_JOIN + " " + where.asWhere(), where.params());
    }

    public List<PlaneMixRow> planeMix(final FindingFilters.Sql where) {
        final String sql = "select f.plane as plane, count(*) as n " + SqlSupport.FINDING_JOIN + " "
                + where.asWhere() + " group by f.plane order by f.plane";
        return jdbc.sql(sql).params(where.params())
                .query((RowMapper<PlaneMixRow>) (rs, rowNum) ->
                        new PlaneMixRow(rs.getString("plane"), rs.getLong("n")))
                .list();
    }

    /** Top detectors, count descending, id ascending to break ties. */
    public List<DetectorCountRow> topDetectors(final FindingFilters.Sql where) {
        final String sql = "select f.detector as detector, count(*) as n " + SqlSupport.FINDING_JOIN + " "
                + where.asWhere() + " group by f.detector order by n desc, f.detector asc";
        return jdbc.sql(sql).params(where.params())
                .query((RowMapper<DetectorCountRow>) (rs, rowNum) ->
                        new DetectorCountRow(rs.getString("detector"), rs.getLong("n")))
                .list();
    }

    /** Findings per UTC day, the first of the two chart series. */
    public List<SeriesPointRow> findingSeries(final FindingFilters.Sql where) {
        final String sql = "select date(f.occurred_at/1000.0, 'unixepoch') as day, count(*) as n "
                + SqlSupport.FINDING_JOIN + " " + where.asWhere() + " group by day order by day";
        return jdbc.sql(sql).params(where.params())
                .query((RowMapper<SeriesPointRow>) (rs, rowNum) ->
                        new SeriesPointRow(rs.getString("day"), rs.getLong("n")))
                .list();
    }

    /**
     * Tool calls per UTC day, the second of the two chart series. Observed calls only, on the
     * same basis as the tile: the marker is the exclusion, and the null-start guard only
     * protects the day bucketing from rows without a start.
     */
    public List<SeriesPointRow> toolCallSeries(final FindingFilters.Sql where) {
        final String sql = "select date(t.started_at/1000.0, 'unixepoch') as day, count(*) as n "
                + SqlSupport.TOOL_CALL_JOIN + " "
                + where.with("t.outcome_only = 0 and t.started_at is not null").asWhere()
                + " group by day order by day";
        return jdbc.sql(sql).params(where.params())
                .query((RowMapper<SeriesPointRow>) (rs, rowNum) ->
                        new SeriesPointRow(rs.getString("day"), rs.getLong("n")))
                .list();
    }

    /**
     * Step throughput per (schema, timing source) bucket. The medians are
     * picked from the ordered step rows (middle of the sorted non-null
     * values; the mean of the two middles for even counts); a bucket with
     * no measured value reads null.
     */
    public List<OverviewDto.ThroughputRow> throughput(final FindingFilters.Sql where) {
        final String sql = "select s.\"schema\" as schema, st.timing_source as source, st.decode_tps as tps, "
                + "st.ttft_ms as ttft " + STEP_JOIN + " " + where.asWhere();
        final Map<String, List<StepSample>> groups = new LinkedHashMap<>();
        jdbc.sql(sql).params(where.params())
                .query((RowMapper<StepSample>) (rs, rowNum) -> new StepSample(
                        rs.getString("schema"),
                        rs.getString("source"),
                        SqlSupport.asDouble(rs.getObject("tps")),
                        SqlSupport.asDouble(rs.getObject("ttft"))))
                .list()
                .forEach(sample -> groups.computeIfAbsent(sample.schema() + '\u0000' + sample.source(),
                        key -> new ArrayList<>()).add(sample));

        final List<OverviewDto.ThroughputRow> rows = new ArrayList<>();
        groups.values().forEach(samples -> rows.add(new OverviewDto.ThroughputRow(
                samples.get(0).schema(),
                samples.get(0).source(),
                samples.size(),
                median(samples.stream().map(StepSample::tps).filter(Objects::nonNull).toList()),
                median(samples.stream().map(StepSample::ttft).filter(Objects::nonNull).toList()))));
        rows.sort(Comparator.comparing(OverviewDto.ThroughputRow::schema)
                .thenComparing(OverviewDto.ThroughputRow::timingSource));
        return rows;
    }

    /** One step's measured throughput values; either may be null. */
    private record StepSample(String schema, String source, Double tps, Double ttft) {
    }

    /**
     * Median of the ordered values, or null for an empty input — never a
     * division by zero.
     */
    static Double median(final List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        final List<Double> sorted = values.stream().sorted().toList();
        final int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

}
