package inspector.store;

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
     */
    public Result cohorts(final String axisColumn) {
        final Map<String, Long> sessions = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n from session s group by 1");
        // Observed calls only: the rate denominator must not count outcome-only rows
        final Map<String, Long> toolCalls = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n from tool_call t "
                        + "join session s on s.id = t.session_id and s.source_file = t.source_file "
                        + "where t.outcome_only = 0 group by 1");
        final Map<String, Long> findings = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n from finding f "
                        + "join session s on s.id = f.session_id and s.source_file = f.source_file group by 1");
        final Map<String, Long> guardFindings = groupCount(
                "select coalesce(s." + axisColumn + ", 'unknown') as key, count(*) as n from finding f "
                        + "join session s on s.id = f.session_id and s.source_file = f.source_file "
                        + "where f.plane = 'GUARD' group by 1");

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

        final int allInferred = jdbc.sql("select coalesce(min(version_inferred), 1) from session")
                .query((RowMapper<Integer>) (rs, rowNum) -> rs.getInt(1))
                .single();
        return new Result(List.copyOf(cohorts), allInferred == 1);
    }

    private Map<String, Long> groupCount(final String sql) {
        final Map<String, Long> out = new HashMap<>();
        for (final Map<String, Object> row : jdbc.sql(sql).query().listOfRows()) {
            out.put((String) row.get("key"), ((Number) row.get("n")).longValue());
        }
        return out;
    }
}
