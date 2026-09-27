package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.TestStore;
import inspector.detect.Finding;
import inspector.detect.Plane;
import inspector.ingest.Convention;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.StreamFacts;
import inspector.query.FindingsQuery;
import inspector.query.InsightFilter;
import inspector.store.entity.ToolCallId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The read paths have to keep using the indexes they were written for.
 *
 * <p>An index that serves nothing costs write time on every re-index and leaves a standing
 * false claim about what the queries do — which is how {@code tool_call} once had an index on
 * {@code (name, error_code)}, a pair no query filters or joins on, while the columns every query
 * joins on were unindexed. Nothing noticed, because no test looked.
 *
 * <p>So the plans themselves are the assertion, read from SQLite's own {@code EXPLAIN QUERY PLAN}
 * over an empty database — and read for the SQL Hibernate actually sends, captured with a
 * {@link StatementInspector} while the real repository method runs. A plan test over a
 * hand-written copy of a query proves something about the copy.
 */
final class QueryPlanTest {

    @TempDir
    Path temp;

    private final List<String> statements = new CopyOnWriteArrayList<>();
    private TestStore store;

    @BeforeEach
    void open() {
        store = TestStore.open(temp.resolve("plan.sqlite"), Map.of(
                "hibernate.session_factory.statement_inspector",
                (StatementInspector) sql -> {
                    statements.add(sql);
                    return sql;
                }));
    }

    @AfterEach
    void close() {
        store.close();
    }

    /** SQLite's own description of how it will run this statement, one line per step. */
    private List<String> plan(final String sql) {
        return store.jdbc().query("explain query plan " + sql, (rs, rowNum) -> rs.getString("detail"));
    }

    /** The statements a piece of repository work sent, in order. */
    private List<String> captured(final Runnable work) {
        statements.clear();
        work.run();
        return List.copyOf(statements);
    }

    @Test
    void theToolLookupOfADetailUsesThePrimaryKeyInsteadOfScanning() {
        // The detail's one read of tool_call. Before the join keys were indexed the equivalent
        // join read "SCAN t" — the whole table, per request.
        final String sql = captured(() -> store.toolCalls().nameAt(new ToolCallId("s", "f", 1)))
                .getFirst();

        assertThat(plan(sql)).anyMatch(line -> line.startsWith("SEARCH") && line.contains("INDEX"));
        assertThat(plan(sql)).noneMatch(line -> line.startsWith("SCAN"));
    }

    @Test
    void theDefaultFindingsSortComesOutOfTheIndexInOrder() {
        // order by occurred_at desc, id desc: the sort the findings page opens with. A temp
        // b-tree here means every page reads and sorts the entire finding table first.
        final String sql = pageQuery(null, Sort.by(Sort.Direction.DESC, "occurredAt", "id"));

        assertThat(plan(sql)).noneMatch(line -> line.contains("TEMP B-TREE"));
        assertThat(plan(sql)).anyMatch(line -> line.contains("idx_finding_time"));
    }

    /**
     * The same sort with a plane on it. It used to answer with {@code USE TEMP B-TREE FOR ORDER
     * BY}: the index behind it was {@code (plane, category, occurred_at)} and nothing filters
     * findings by category, so only the first ORDER BY term could come out of the index prefix.
     */
    @Test
    void thePlaneFilteredFindingsSortComesOutOfTheIndexInOrder() {
        final String sql = pageQuery("GUARD", Sort.by(Sort.Direction.DESC, "occurredAt", "id"));

        assertThat(plan(sql)).noneMatch(line -> line.contains("TEMP B-TREE"));
        assertThat(plan(sql)).anyMatch(line -> line.contains("idx_finding_plane_time"));
    }

    @Test
    void theStreamDeletesOfARewriteSearchInsteadOfScanning() {
        // A re-index deletes by (session_id, source_file) — per stream for the stream it is
        // rewriting, and once for the streams the corpus no longer holds. Unindexed, each of
        // those deletes reads the whole table.
        final List<String> deletes = captured(() -> store.writer().writeStream(source(), emptyStream(),
                        List.of(new Finding("error-plane", Plane.MODEL_MISUSE, null, "FS_NOT_FOUND", null,
                                null, null, 1, null, null, 1L, "a", List.of())),
                        "v", false, 1L)).stream()
                .filter(sql -> sql.startsWith("delete"))
                .toList();

        assertThat(deletes).hasSize(5);
        for (final String delete : deletes) {
            assertThat(plan(delete)).as(delete)
                    .noneMatch(line -> line.startsWith("SCAN") && !line.contains("CORRELATED"))
                    .anyMatch(line -> line.startsWith("SEARCH"));
        }
    }

    @Test
    void anIndexRetiredFromTheSchemaIsGoneFromADatabaseThatAlreadyHadIt() {
        // schema.sql is applied to databases that an older build created, and a reset only
        // empties tables — so removing a CREATE INDEX line does not remove the index. That is
        // why the DDL carries explicit DROPs, and this is the test that they stay there.
        store.jdbc().execute("create index if not exists idx_tool_call_name_error on tool_call (name, error_code)");
        store.jdbc().execute("create index if not exists idx_finding_occurred on finding (plane, category, occurred_at)");
        store.jdbc().execute("create index if not exists idx_tool_call_stream on tool_call (session_id, source_file, seq)");

        // a boot at the current version re-applies the DDL
        store.writer().resetIfStale(IndexWriter.SCHEMA_VERSION);

        assertThat(store.jdbc().queryForList("select name from sqlite_master where type = 'index' and name in"
                        + " ('idx_tool_call_name_error', 'idx_finding_occurred', 'idx_tool_call_stream')",
                String.class)).isEmpty();
        assertThat(store.jdbc().queryForList("select name from sqlite_master where type = 'index'"
                + " and name = 'idx_finding_plane_time'", String.class))
                .containsExactly("idx_finding_plane_time");
    }

    private String pageQuery(final String plane, final Sort sort) {
        return captured(() -> store.findings().page(
                        new FindingsQuery(InsightFilter.none(), plane, null, null, null),
                        PageRequest.of(0, 20, sort))).stream()
                .filter(sql -> sql.contains("order by"))
                .findFirst().orElseThrow();
    }

    private static SessionSource source() {
        return new SessionSource(Path.of("p/s/session.jsonl.zstd"), "s", "p", Convention.V0, "session.jsonl.zstd");
    }

    private static StreamFacts emptyStream() {
        return new StreamFacts(new SessionRecord("s", "session.jsonl.zstd", "p", "V0", 1L, null, null, null,
                null, null, null, 0, "/home/dev/p"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 0L);
    }
}
