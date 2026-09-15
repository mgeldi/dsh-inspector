package inspector.store;

import inspector.api.FindingFilters;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The cohorts comparison queries (DESIGN.md §7): raw counters per cohort
 * key, no rates. Rates are findings per 1,000 <i>observed</i> tool calls
 * ({@code outcome_only = 0}) and are computed by the controller, which
 * also knows which row is the baseline: a {@code tool/result} whose
 * {@code tool/call} never appeared is stored so no outcome is lost, but it
 * is not a call and must not sit in a denominator.
 *
 * <p>The axis column is a fixed fragment chosen by the controller from a
 * whitelist; nothing user-supplied is spliced into the SQL.
 */
@Component
public final class CohortRepository {

    private final JdbcClient jdbc;

    public CohortRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One cohort's raw counters. */
    public record Cohort(String key, long sessions, long toolCalls, long findings, long guardFindings) {
    }

    /** The cohort table for one axis, plus whether every version is inferred. */
    public record Result(List<Cohort> cohorts, boolean allVersionInferred) {
    }

    /**
     * @param axisColumn the whitelisted bare session column, e.g. {@code harness_version}
     * @param filters    the shared filter contract of §7, applied to <i>every</i> aggregate in
     *                   this table. A cohort rate that ignored the rail would describe a
     *                   different population than the dashboard above it, and the screen would
     *                   show two truths at once — which is the failure this endpoint shipped
     *                   with until the rail and the endpoint were finally connected.
     */
    public Result cohorts(final String axisColumn, final FindingFilters filters) {
        // One filter shape, four roots. The clause is built per root because the time column
        // differs — finding event time, session start, call time — while every aggregate joins
        // `session s`, so the facet predicates apply unchanged.
        final FindingFilters.Sql bySession = filters.forSession();
        final FindingFilters.Sql byFinding = filters.forFinding();
        // Observed calls only: the rate denominator must not count outcome-only rows
        final FindingFilters.Sql observed = filters.forToolCall().with("t.outcome_only = 0");
        final FindingFilters.Sql byGuard = byFinding.with("f.plane = 'GUARD'");

        // Distinct ids, not rows: `session` is keyed (id, source file), so a session stored
        // under both conventions is two rows. Counting rows here reports a session twice in
        // any cohort whose axis value both files share, and contradicts the Overview tile.
        final Map<String, Long> sessions = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(distinct s.id) as n "
                        + "from session s " + bySession.asWhere() + " group by 1", bySession.params());
        final Map<String, Long> toolCalls = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n "
                        + SqlSupport.TOOL_CALL_JOIN + " "
                        + observed.asWhere() + " group by 1", observed.params());
        final Map<String, Long> findings = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n "
                        + SqlSupport.FINDING_JOIN + " "
                        + byFinding.asWhere() + " group by 1", byFinding.params());
        final Map<String, Long> guardFindings = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n "
                        + SqlSupport.FINDING_JOIN + " "
                        + byGuard.asWhere() + " group by 1", byGuard.params());

        final TreeSet<String> keys = new TreeSet<>(sessions.keySet());
        keys.addAll(toolCalls.keySet());
        keys.addAll(findings.keySet());
        keys.addAll(guardFindings.keySet());

        final List<Cohort> cohorts = new ArrayList<>();
        for (final String key : keys) {
            cohorts.add(new Cohort(
                    key,
                    sessions.getOrDefault(key, 0L),
                    toolCalls.getOrDefault(key, 0L),
                    findings.getOrDefault(key, 0L),
                    guardFindings.getOrDefault(key, 0L)));
        }

        // The "is the version declared?" claim is made about the selection on screen, not
        // about the whole index: filtered down to one cohort, an all-inferred index would be a
        // sentence about data nobody is looking at.
        final int allInferred = jdbc.sql("select coalesce(min(s.version_inferred), 1) from session s "
                        + bySession.asWhere())
                .params(bySession.params())
                .query((RowMapper<Integer>) (rs, rowNum) -> rs.getInt(1))
                .single();
        return new Result(List.copyOf(cohorts), allInferred == 1);
    }

    private Map<String, Long> groupCount(final String sql, final List<Object> params) {
        final Map<String, Long> out = new HashMap<>();
        for (final Map<String, Object> row : jdbc.sql(sql).params(params).query().listOfRows()) {
            out.put((String) row.get("key"), ((Number) row.get("n")).longValue());
        }
        return out;
    }
}
