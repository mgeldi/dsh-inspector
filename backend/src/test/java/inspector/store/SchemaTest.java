package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The DDL on its own terms: what the schema file creates, whether running it twice is the same
 * as running it once, and whether the foreign keys it declares actually reject the rows they
 * claim to reject.
 *
 * <p>No Spring context. This used to be a boot test whose only reason for existing was to be
 * handed a {@code JdbcClient} — the same trade {@code QueryPlanTest} and {@code
 * IndexWriterTest} already make, applying the script to a temp file directly. What the context
 * proved is that the application applies the script when it boots, and that is
 * {@code ApplicationContextTest.contextLoadsWithTheSchemaApplied}, which keeps doing it. What
 * the context also has to prove — that the connections the application borrows are opened with
 * foreign keys on — is in that test too, because it is a property of the pool, not of the DDL.
 */
final class SchemaTest {

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void createTheDatabaseFromTheShippedScript() throws IOException {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        // Same URL shape the application uses. foreign_keys is a per-connection setting that
        // SQLite leaves off, so a DDL test that forgets it silently tests declarations that
        // are never enforced — which is the difference between this file proving a constraint
        // and proving that a CREATE TABLE statement parses.
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("schema.sqlite") + "?foreign_keys=on");
        jdbc = new JdbcTemplate(dataSource);
        applySchema();
    }

    private void applySchema() throws IOException {
        final String ddl;
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String statement : ddl.split(";")) {
            if (!statement.isBlank()) {
                final String sql = statement.trim();
                assertThatCode(() -> jdbc.execute(sql)).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void allSixTablesExist() {
        assertThat(jdbc.queryForList("select name from sqlite_master where type='table' "
                + "and name not like 'sqlite_%'", String.class))
                .contains("session", "step", "tool_call", "finding", "shell_evidence", "meta");
    }

    /**
     * The script runs on every boot against a database an older build may have created, so
     * every statement has to survive a second application. Re-running it over the schema it
     * just made is the only way that is checked.
     */
    @Test
    void ddlIsIdempotent() throws IOException {
        applySchema();
        assertThat(jdbc.queryForList("select name from sqlite_master where type='table' "
                + "and name not like 'sqlite_%'", String.class))
                .contains("session", "step", "tool_call", "finding", "shell_evidence", "meta");
    }

    /**
     * {@code path_hint} is a redacted fragment and is deliberately never indexed: an index
     * would put its values, verbatim, into a b-tree that ships inside the database file.
     */
    @Test
    void nothingIndexesPathHint() {
        assertThat(jdbc.queryForList("select sql from sqlite_master where type='index' "
                + "and sql is not null", String.class)).noneMatch(sql -> sql.contains("path_hint"));
    }

    @Test
    void everyChildTableDeclaresTheParentItWasWrittenAgainst() {
        // The shape, read back out of the database rather than out of schema.sql: which table
        // each child points at, and which columns carry the key. (id, source_file) rather than
        // id alone because one session id can appear in both log conventions, so a key that
        // omits source_file would let a stream vouch for a different stream's rows.
        assertThat(parentTablesOf("step")).containsExactly("session");
        assertThat(parentTablesOf("tool_call")).containsExactly("session");
        assertThat(parentTablesOf("finding")).containsExactly("session");
        assertThat(parentTablesOf("shell_evidence")).containsExactly("finding");

        assertThat(childKeysOf("finding"))
                .containsExactlyInAnyOrder(
                        Map.entry("session_id", "id"), Map.entry("source_file", "source_file"));
    }

    @Test
    void aFindingWhoseStreamIsNotInTheIndexIsRejected() {
        assertThatThrownBy(() -> insertFinding("session-that-was-never-indexed", "nowhere.jsonl"))
                .hasMessageContaining("FOREIGN KEY");
    }

    @Test
    void aSessionStillHoldingFindingsCannotBeDeleted() {
        // The invariant IndexWriter's delete ordering claims: child rows first, then the stream.
        // Delete in the other order and this is the error, which is the whole reason the wipe
        // and the per-stream delete are written child-to-parent.
        insertSession("session-a", "a.jsonl");
        insertFinding("session-a", "a.jsonl");

        assertThatThrownBy(() -> jdbc.update("delete from session where id = ?", "session-a"))
                .hasMessageContaining("FOREIGN KEY");
    }

    @Test
    void evidenceForRowThatIsNotAFindingIsRejected() {
        insertSession("session-a", "a.jsonl");
        insertFinding("session-a", "a.jsonl");
        final long findingId = jdbc.queryForObject("select id from finding", Long.class);

        assertThatThrownBy(() -> jdbc.update("insert into shell_evidence (finding_id, seq, verb_class)"
                + " values (?, 1, 'delete')", findingId + 1000)).hasMessageContaining("FOREIGN KEY");
    }

    /**
     * The constraint is a declaration; the pragma is the enforcement — and the pragma belongs to
     * a connection, not to a database (sqlite.org/foreignkeys.html §2: "must be enabled
     * separately for each database connection"). Run on one borrowed connection, this rejects
     * the orphan with the pragma on and accepts the identical row with it off.
     *
     * <p>That is also the failure mode the production URL exists to prevent: an {@code ON}
     * stated once in documentation would leave whichever connection never got it back to
     * accepting orphan findings. {@code ApplicationContextTest} asserts the pragma on the
     * connections the application's own pool hands out.
     */
    @Test
    void thePragmaEnablesTheConstraintNotTheDeclaration() {
        // One physical connection, suppressClose so JdbcTemplate does not hand the pragma and
        // the insert to two different ones — DriverManagerDataSource would, and the test would
        // then prove nothing about either connection.
        final SingleConnectionDataSource oneConnection =
                new SingleConnectionDataSource("jdbc:sqlite:" + temp.resolve("schema.sqlite"), true);
        try {
            final JdbcTemplate single = new JdbcTemplate(oneConnection);

            single.execute("pragma foreign_keys=off");
            assertThat(single.queryForObject("pragma foreign_keys", Integer.class)).isZero();
            insertFindingOn(single, "session-that-was-never-indexed", "nowhere.jsonl");
            assertThat(single.queryForObject("select count(*) from finding", Integer.class)).isEqualTo(1);

            single.execute("pragma foreign_keys=on");
            assertThat(single.queryForObject("pragma foreign_keys", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> insertFindingOn(single, "also-never-indexed", "nowhere.jsonl"))
                    .hasMessageContaining("FOREIGN KEY");
        } finally {
            oneConnection.destroy();
        }
    }

    /**
     * The parents a child points at. One pragma row per column <em>pair</em>, so a composite key
     * reports its parent twice; distinct because "which table" is the question here, and the
     * columns are asserted separately below.
     */
    private List<String> parentTablesOf(final String child) {
        return jdbc.queryForList("pragma foreign_key_list(" + child + ")").stream()
                .map(row -> (String) row.get("table"))
                .distinct()
                .toList();
    }

    private List<Map.Entry<String, String>> childKeysOf(final String child) {
        return jdbc.queryForList("pragma foreign_key_list(" + child + ")").stream()
                .map(row -> Map.entry((String) row.get("from"), (String) row.get("to")))
                .toList();
    }

    private void insertSession(final String id, final String sourceFile) {
        jdbc.update("insert into session (id, source_file, project_slug, schema, started_at, indexed_at)"
                        + " values (?, ?, 'invented-project', 'v2', ?, ?)",
                id, sourceFile, 1_760_000_000_000L, 1_760_000_000_000L);
    }

    private void insertFinding(final String sessionId, final String sourceFile) {
        insertFindingOn(jdbc, sessionId, sourceFile);
    }

    /** The same insert for either connection: a finding is the smallest child row there is. */
    private static void insertFindingOn(final JdbcTemplate target, final String sessionId,
                                        final String sourceFile) {
        target.update("insert into finding (session_id, source_file, detector, plane, occurred_at, summary)"
                        + " values (?, ?, 'error-prior', 'tool', ?, ?)",
                sessionId, sourceFile, 1_760_000_000_000L, "an invented finding summary");
    }
}
