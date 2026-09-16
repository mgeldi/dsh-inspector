package inspector.store;

import inspector.query.FindingFilters;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The findings table and the detail endpoint (DESIGN.md §7).
 *
 * <p>The list queries return the aggregate shape only — no evidence text.
 * The detail query is the single place in the read API that joins
 * {@code shell_evidence} and may return the redacted excerpts.
 *
 * <p>Everything this class hands back is a store row, never a wire record. The wire shape and
 * the {@code ResultSet} used to be the same types, which welded two things that change for
 * different reasons: renaming a JSON field edited a SQL mapper, and adding a column changed the
 * API. {@link FindingRow} is the row; {@code FindingsService} decides what a client sees.
 */
@Component
public final class FindingRepository {

    private static final String FINDING_COLUMNS =
            "f.id, f.session_id, f.detector, f.plane, f.category, f.code, f.confidence, f.path_hint, "
                    + "f.seq, f.stale_seq, f.cause_seq, f.occurred_at, f.summary";

    private final JdbcClient jdbc;

    public FindingRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One {@code finding} row: the 13 columns of {@code FINDING_COLUMNS}, named after the read
     * rather than after JSON. {@code occurredAt} is not null because the column is not null;
     * every nullable column is a boxed type, and a null {@code confidence} means unattributed,
     * not zero — which the wire record has to keep distinguishable too.
     */
    public record FindingRow(
            long id,
            String sessionId,
            String detector,
            String plane,
            String category,
            String code,
            Double confidence,
            String pathHint,
            Long seq,
            Long staleSeq,
            Long causeSeq,
            long occurredAt,
            String summary) {
    }

    /** One {@code shell_evidence} row of one finding, in shell order. */
    public record EvidenceRow(long seq, String verbClass, String pathHint, String excerptRedacted) {
    }

    /**
     * The detail read: the finding, the {@code tool_call.name} its {@code seq} points at (null
     * for seq-less findings and for seqs that are llm calls), and the evidence rows.
     */
    public record FindingDetailRow(FindingRow finding, String tool, List<EvidenceRow> evidence) {
    }

    /** Total rows matching the filters, all pages. */
    public long count(final FindingFilters.Sql where) {
        return SqlSupport.singleLong(jdbc, "select count(*) " + SqlSupport.FINDING_JOIN + " "
                + where.asWhere(), where.params());
    }

    /**
     * One page, zero-based. {@code orderBy} comes from the controller's
     * whitelist and always ends with the stable {@code f.id} tiebreaker.
     */
    public List<FindingRow> page(
            final FindingFilters.Sql where, final String orderBy, final int page, final int size) {
        final List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add((long) page * size);
        final String sql = "select " + FINDING_COLUMNS + " " + SqlSupport.FINDING_JOIN + " " + where.asWhere()
                + " order by " + orderBy + " limit ? offset ?";
        return jdbc.sql(sql).params(params)
                .query((RowMapper<FindingRow>) (rs, rowNum) -> toRow(rs))
                .list();
    }

    /**
     * The one endpoint that returns evidence text: the finding, the tool
     * call its {@code seq} points at (left join, null for seq-less findings
     * and llm seqs), and the redacted shell excerpts in shell order.
     */
    public Optional<FindingDetailRow> detail(final long id) {
        final String sql = "select " + FINDING_COLUMNS + ", t.name as tool "
                + "from finding f "
                + "left join tool_call t on t.session_id = f.session_id "
                + "and t.source_file = f.source_file and t.seq = f.seq "
                + "where f.id = ?";
        final FindingWithTool finding = jdbc.sql(sql).param(id)
                .query((RowMapper<FindingWithTool>) (rs, rowNum) ->
                        new FindingWithTool(toRow(rs), rs.getString("tool")))
                .optional()
                .orElse(null);
        if (finding == null) {
            return Optional.empty();
        }
        final List<EvidenceRow> evidence = jdbc.sql(
                        "select seq, verb_class, path_hint, excerpt_redacted from shell_evidence "
                                + "where finding_id = ? order by seq")
                .param(id)
                .query((RowMapper<EvidenceRow>) (rs, rowNum) -> new EvidenceRow(
                        rs.getLong("seq"),
                        rs.getString("verb_class"),
                        rs.getString("path_hint"),
                        rs.getString("excerpt_redacted")))
                .list();
        return Optional.of(new FindingDetailRow(finding.finding(), finding.tool(), evidence));
    }

    private record FindingWithTool(FindingRow finding, String tool) {
    }

    private FindingRow toRow(final ResultSet rs) throws SQLException {
        return new FindingRow(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("detector"),
                rs.getString("plane"),
                rs.getString("category"),
                rs.getString("code"),
                SqlSupport.asDouble(rs.getObject("confidence")),
                rs.getString("path_hint"),
                asLong(rs.getObject("seq")),
                asLong(rs.getObject("stale_seq")),
                asLong(rs.getObject("cause_seq")),
                rs.getLong("occurred_at"),
                rs.getString("summary"));
    }

    private static Long asLong(final Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
