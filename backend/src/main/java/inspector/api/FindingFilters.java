package inspector.api;

import java.util.ArrayList;
import java.util.List;

/**
 * The single WHERE contract all read endpoints share (DESIGN.md §7).
 *
 * <p>The clause and its parameter list are built here, in one place, and no
 * filter value ever reaches SQLite except as a {@code ?} bind parameter —
 * the clause text is assembled only from fixed column fragments. The column
 * the time window applies to depends on the table the query is rooted in;
 * the alias/time-column variants keep the one filter shape across the
 * finding, session, tool_call and step roots.
 *
 * <p>The contract is unit-tested against injection payloads in
 * {@code FindingFiltersTest}: the value never appears in the clause, only in
 * the parameter list, in the documented order.
 */
public final class FindingFilters {

    /** A built WHERE clause with its parameter list, in bind order. */
    public record Sql(String where, List<Object> params) {

        /** True when the clause is empty, i.e. the query is unfiltered. */
        public boolean isEmpty() {
            return where.isEmpty();
        }

        /** The clause for SQL assembly: empty, or {@code "where ..."}. */
        public String asWhere() {
            return isEmpty() ? "" : "where " + where;
        }
    }

    private final Long from;
    private final Long to;
    private final String schema;
    private final String model;
    private final String preset;
    private final String harnessVersion;

    public FindingFilters(final InsightFilter filter) {
        this.from = filter.from();
        this.to = filter.to();
        this.schema = filter.schema();
        this.model = filter.model();
        this.preset = filter.preset();
        this.harnessVersion = filter.harnessVersion();
    }

    /** True when any of the six shared values would add a clause. */
    public boolean isActive() {
        return from != null || to != null || schema != null || model != null
                || preset != null || harnessVersion != null;
    }

    /**
     * WHERE for queries rooted at finding {@code f} with the session joined
     * as {@code s}. The time window applies to the event time.
     */
    public Sql forFinding() {
        return build("f.occurred_at");
    }

    /**
     * WHERE for queries rooted at session {@code s}. The time window applies
     * to the session start.
     */
    public Sql forSession() {
        return build("s.started_at");
    }

    /**
     * WHERE for queries rooted at tool_call {@code t} with the session joined
     * as {@code s}. The time window applies to the call start.
     */
    public Sql forToolCall() {
        return build("t.started_at");
    }

    /**
     * WHERE for queries rooted at step {@code st} with the session joined as
     * {@code s}. The time window applies to the step start.
     */
    public Sql forStep() {
        return build("st.started_at");
    }

    /**
     * WHERE for the findings page: the six shared values plus the
     * findings-axis filters. Bind order: from, to, schema, model, preset,
     * harnessVersion, plane, detector, code, session.
     */
    public Sql forFindings(final String plane, final String detector, final String code, final String session) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        timeClauses("f.occurred_at", clauses, params);
        sessionClauses(clauses, params);
        axisClause(clauses, params, "f.plane", plane);
        axisClause(clauses, params, "f.detector", detector);
        axisClause(clauses, params, "f.code", code);
        axisClause(clauses, params, "f.session_id", session);
        return new Sql(String.join(" and ", clauses), List.copyOf(params));
    }

    private Sql build(final String timeColumn) {
        final List<String> clauses = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        timeClauses(timeColumn, clauses, params);
        sessionClauses(clauses, params);
        return new Sql(String.join(" and ", clauses), List.copyOf(params));
    }

    private void timeClauses(final String timeColumn, final List<String> clauses, final List<Object> params) {
        if (from != null) {
            clauses.add(timeColumn + " >= ?");
            params.add(from);
        }
        if (to != null) {
            clauses.add(timeColumn + " <= ?");
            params.add(to);
        }
    }

    /** The session-side filters; the session is always joined as {@code s}. */
    private void sessionClauses(final List<String> clauses, final List<Object> params) {
        // "schema" is a SQLite keyword, so it stays quoted in the fragment
        axisClause(clauses, params, "s.\"schema\"", schema);
        axisClause(clauses, params, "s.model", model);
        axisClause(clauses, params, "s.agent_preset", preset);
        axisClause(clauses, params, "s.harness_version", harnessVersion);
    }

    /** One {@code column = ?} clause for a present value. The value binds, never splices. */
    private static void axisClause(
            final List<String> clauses, final List<Object> params, final String column, final String value) {
        if (value != null) {
            clauses.add(column + " = ?");
            params.add(value);
        }
    }
}
