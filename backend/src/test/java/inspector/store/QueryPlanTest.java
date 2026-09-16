package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The read paths have to keep using the indexes they were written for.
 *
 * <p>An index that serves nothing costs write time on every re-index and leaves a standing
 * false claim about what the queries do — which is how {@code tool_call} ended up with one
 * index on {@code (name, error_code)}, a pair no query filters or joins on, while the three
 * columns every query does join on were unindexed and the detail endpoint scanned the whole
 * table per request. Nothing in the suite noticed, because no test looked.
 *
 * <p>So the plans themselves are the assertion. They are read from SQLite's own
 * {@code EXPLAIN QUERY PLAN}, over an empty database: the shapes here are chosen from the
 * schema rather than the row counts, so the check costs milliseconds and cannot rot into a
 * plan that is only true of one corpus.
 */
final class QueryPlanTest {

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void applyTheShippedSchema() throws IOException {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("plan.sqlite"));
        jdbc = new JdbcTemplate(dataSource);
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            final String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final String statement : ddl.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        }
    }

    /** SQLite's own description of how it will run this statement, one line per step. */
    private List<String> plan(final String sql) {
        return jdbc.query("explain query plan " + sql, (rs, rowNum) -> rs.getString("detail"));
    }

    @Test
    void theDetailJoinLooksTheToolCallUpInsteadOfScanningForIt() {
        // The one endpoint that joins tool_call. Before the join keys were indexed this line
        // read "SCAN t LEFT-JOIN" — the whole table, per request.
        final List<String> plan = plan("select f.id, t.name as tool from finding f"
                + " left join tool_call t on t.session_id = f.session_id"
                + " and t.source_file = f.source_file and t.seq = f.seq where f.id = 1");

        assertThat(plan).anyMatch(line -> line.contains("SEARCH t USING INDEX idx_tool_call_stream"));
        assertThat(plan).noneMatch(line -> line.contains("SCAN t"));
    }

    @Test
    void theDefaultFindingsSortComesOutOfTheIndexInOrder() {
        // order by occurred_at desc, id desc: the sort the findings page opens with. A temp
        // b-tree here means every page reads and sorts the entire finding table first.
        final List<String> plan = plan("select f.id from finding f"
                + " join session s on s.id = f.session_id and s.source_file = f.source_file"
                + " order by f.occurred_at desc, f.id desc limit 20 offset 0");

        assertThat(plan).noneMatch(line -> line.contains("TEMP B-TREE"));
        assertThat(plan).anyMatch(line -> line.contains("idx_finding_time"));
    }

    @Test
    void theStreamKeyOfEveryChildTableIsIndexable() {
        // A re-index deletes by (session_id, source_file) — per stream for the stream it is
        // rewriting, and once for the streams the corpus no longer holds. Unindexed, each of
        // those deletes reads the whole table, so the prune added on top of a re-index made
        // the write path slower in proportion to how much it removed.
        for (final String table : List.of("finding", "tool_call", "step")) {
            assertThat(plan("delete from " + table + " where session_id = 's' and source_file = 'f'"))
                    .as("delete from " + table)
                    .anyMatch(line -> line.startsWith("SEARCH " + table + " USING INDEX"));
        }
    }

    @Test
    void anIndexRetiredFromTheSchemaIsGoneFromADatabaseThatAlreadyHadIt() {
        // schema.sql is applied to databases that an older build created, and a reset only
        // empties tables — so removing a CREATE INDEX line does not remove the index. That is
        // why the DDL carries an explicit DROP, and this is the test that it stays there.
        jdbc.execute("create index if not exists idx_tool_call_name_error"
                + " on tool_call (name, error_code)");

        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            for (final String statement
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertThat(jdbc.queryForList("select name from sqlite_master where type = 'index'"
                + " and name = 'idx_tool_call_name_error'", String.class)).isEmpty();
    }
}
