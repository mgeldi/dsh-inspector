package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import inspector.query.FindingFilters;
import inspector.query.InsightFilter;
import inspector.dto.FindingDetailDto;
import inspector.detect.Category;
import inspector.detect.Finding;
import inspector.detect.Plane;
import inspector.index.IndexService;
import inspector.ingest.Convention;
import inspector.ingest.ErrorEvent;
import inspector.ingest.FatalTurn;
import inspector.ingest.FileTouch;
import inspector.ingest.RedactedExcerpt;
import inspector.ingest.RetryEvent;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StepRecord;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import inspector.ingest.VerbClass;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.FileCopyUtils;

/**
 * No Spring context on purpose: the writer is exercised by hand against a SQLite file in a
 * temp dir, with schema.sql executed directly. That is faster than booting the context and
 * keeps the assertions pointed at the SQL, where the idempotence actually lives.
 *
 * <p>Every path in this test is invented.
 */
final class IndexWriterTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String VERSION = "1.4.2-test";

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;
    private IndexWriter writer;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void createDatabase() throws IOException {
        dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("index.sqlite"));
        jdbc = new JdbcTemplate(dataSource);
        writer = new IndexWriter(jdbc, new DataSourceTransactionManager(dataSource));
        executeSchema(jdbc);
    }

    /** The shipped DDL, statement by statement — the same source the app applies. */
    private void executeSchema(final JdbcTemplate jdbc) throws IOException {
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            final String ddl = FileCopyUtils.copyToString(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            for (final String statement : ddl.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        }
    }

    @Test
    void writingTheSameStreamTwiceLeavesExactlyOneSetOfRows() {
        writeAll();
        writeAll();

        assertThat(count("session")).isEqualTo(1);
        assertThat(count("step")).isEqualTo(2);
        assertThat(count("tool_call")).isEqualTo(4);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("shell_evidence")).isEqualTo(1);
        // no row of the stream appears twice under its own key
        assertThat(jdbc.queryForList(
                "select session_id, source_file, turn, step from step"
                        + " group by session_id, source_file, turn, step having count(*) > 1"))
                .isEmpty();
        assertThat(jdbc.queryForList(
                "select session_id, source_file, detector, seq from finding"
                        + " group by session_id, source_file, detector, seq having count(*) > 1"))
                .isEmpty();
        // the second pass's delete did not orphan evidence rows
        assertThat(jdbc.queryForObject(
                "select count(*) from shell_evidence where finding_id not in (select id from finding)",
                Integer.class))
                .isZero();
    }

    @Test
    void theSessionRowCarriesTheParameterizedHarnessVersionAndTheInferredFlag() {
        writeAll();

        assertThat(jdbc.queryForMap("select * from session")).satisfies(row -> {
            assertThat(row.get("harness_version")).isEqualTo(VERSION);
            assertThat(row.get("version_inferred")).isEqualTo(1);
            assertThat(row.get("indexed_at")).isEqualTo(T0 + 999);
            assertThat(row.get("fatal_turns")).isEqualTo(1);
        });
    }

    @Test
    void noPersistedPathHintStartsWithASlashWhenTheInputsWereAbsolutePath() {
        // the fixture hands the writer only absolute paths — under the project root and
        // under a different root entirely — and none may survive that far
        writeAll();

        final List<String> hints = new ArrayList<>();
        hints.addAll(jdbc.queryForList("select path_hint from tool_call where path_hint is not null",
                String.class));
        hints.addAll(jdbc.queryForList("select path_hint from finding where path_hint is not null",
                String.class));
        hints.addAll(jdbc.queryForList("select path_hint from shell_evidence where path_hint is not null",
                String.class));
        assertThat(hints).isNotEmpty()
                .allSatisfy(hint -> assertThat(hint).doesNotStartWith("/"));
    }

    @Test
    void pathsUnderTheRootBecomeRelativeAndOtherRootsKeepTheirLastSegmentOnly() {
        writeAll();

        assertThat(jdbc.queryForList(
                "select name, path_hint from tool_call where path_hint is not null order by seq"))
                .containsExactly(
                        Map.of("name", "read", "path_hint", "App.java"),
                        Map.of("name", "edit", "path_hint", "App.java"),
                        Map.of("name", "write", "path_hint", "missing.txt"));
        // the bash call carried no path at all — it stays NULL, not a guess
        assertThat(jdbc.queryForObject(
                "select path_hint from tool_call where name = 'bash'", Object.class))
                .isNull();
        assertThat(jdbc.queryForList("select detector, path_hint from finding order by id"))
                .containsExactly(
                        Map.of("detector", "stamp-guard", "path_hint", "App.java"),
                        Map.of("detector", "error-plane", "path_hint", "missing.txt"));
        assertThat(jdbc.queryForList("select path_hint from shell_evidence"))
                .containsExactly(Map.of("path_hint", "App.java"));
    }

    @Test
    void withEvidenceStorageDisabledFindingsKeepTheirAttributionAndSeqs() {
        writer.writeStream(source(), facts(), findings(), VERSION, false, T0 + 999);

        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("shell_evidence")).isZero();
        assertThat(jdbc.queryForList(
                "select category, confidence, seq, stale_seq, cause_seq from finding"
                        + " where detector = 'stamp-guard'"))
                .containsExactly(Map.of(
                        "category", "DIRECT_MUTATION", "confidence", 0.9,
                        "seq", 9, "stale_seq", 5, "cause_seq", 7));
    }

    @Test
    void evidenceRowsAreStoredAgainstTheFindingThatOwnsThem() {
        writeAll();

        assertThat(jdbc.queryForList(
                "select e.seq, e.verb_class, e.path_hint, e.excerpt_redacted"
                        + " from shell_evidence e join finding f on f.id = e.finding_id"
                        + " where f.detector = 'stamp-guard'"))
                .containsExactly(Map.of(
                        "seq", 7, "verb_class", "MUTATING", "path_hint", "App.java",
                        "excerpt_redacted", "sed -i 's/a/b/' App.java"));
    }

    @Test
    void twoFindingsFromTheSameSessionKeepTheirDistinctEventTimes() {
        // the seam correction: occurred_at is the event time, persisted verbatim. If a time
        // ever got interpolated from the session's start or end, these two would collapse to
        // one value and this test would fail.
        writeAll();

        assertThat(jdbc.queryForList("select occurred_at from finding order by id", Long.class))
                .containsExactly(T0 + 700, T0 + 900);
    }

    @Test
    void metaSchemaVersionIsSeededAndSurvivesASecondSeed() {
        writer.seedMeta("1");
        writer.seedMeta("1");

        assertThat(jdbc.queryForList("select key, value from meta"))
                .containsExactly(Map.of("key", "schema_version", "value", "1"));
    }

    @Test
    void aFreshDatabaseReportsZeroSessions() {
        assertThat(writer.countSessions()).isZero();
    }

    @Test
    void aDatabaseWithOneIndexedStreamReportsOneSession() {
        writeAll();

        assertThat(writer.countSessions()).isEqualTo(1);
    }

    @Test
    void theSessionTableCarriesNoCwdColumn() {
        // cwd exists in memory on SessionRecord so the writer can relativize paths; it must
        // never become a column, or the working directory leaks into the database
        writeAll();

        assertThat(jdbc.queryForObject(
                "select sql from sqlite_master where type = 'table' and name = 'session'", String.class))
                .doesNotContain("cwd");
    }

    @Test
    void anOrphanResultRowIsStoredMarkedAndExcludedFromTheCallCounts() {
        // The measured defect at the store level: a tool/result whose tool/call never appeared
        // in the stream. The row is stored — no outcome is lost — but it is not a call: it is
        // marked outcome_only and excluded from the tile count and the rate denominators. The
        // complement, the call that did get its result, is not marked, so the marker cannot
        // drift into marking everything.
        final StreamFacts orphan = new StreamFacts(
                new SessionRecord("s-1", "session.jsonl.zstd", "demo-app", "V0", T0, T0 + 60_000,
                        null, null, "model-a", 131072, 0, "/home/dev/demo"),
                List.of(),
                List.of(
                        new ToolCallRecord(0, 0, 5, "edit", T0 + 200, T0 + 300, 100L, null,
                                "/home/dev/demo/App.java", false),
                        new ToolCallRecord(0, 0, 9, null, null, T0 + 500, null, null, null, true)),
                List.of(),
                List.of(),
                List.of(new ErrorEvent(5, 0, 0, "edit", "FS_NOT_FOUND",
                        "/home/dev/demo/App.java", T0 + 300)),
                List.of(),
                List.of(),
                0L);
        writer.writeStream(source(), orphan,
                List.of(new Finding("error-plane", Plane.MODEL_MISUSE, null, "FS_NOT_FOUND", null,
                        "/home/dev/demo/App.java", 5, null, null, T0 + 300,
                        "edit returned FS_NOT_FOUND", List.of())),
                VERSION, false, T0 + 999);

        // stored: both rows are in the table — the orphan's outcome is not lost
        assertThat(count("tool_call")).isEqualTo(2);

        // marked: exactly the orphan row, with the null-start consequences. The name stays
        // null as a consequence of the missing call; the column is the marker.
        final List<Map<String, Object>> rows = jdbc.queryForList(
                "select seq, name, started_at, ended_at, duration_ms, outcome_only"
                        + " from tool_call order by seq");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.get("seq")).isEqualTo(5);
            assertThat(row.get("name")).isEqualTo("edit");
            assertThat(row.get("started_at")).isEqualTo(T0 + 200);
            assertThat(row.get("outcome_only")).isEqualTo(0);
        });
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.get("seq")).isEqualTo(9);
            assertThat(row.get("name")).isNull();
            assertThat(row.get("started_at")).isNull();
            assertThat(row.get("ended_at")).isEqualTo(T0 + 500);
            assertThat(row.get("duration_ms")).isNull();
            assertThat(row.get("outcome_only")).isEqualTo(1);
        });

        // excluded from the tile count: the overview's tool-call tile counts observed calls,
        // not rows
        final JdbcClient client = JdbcClient.create(dataSource);
        assertThat(new OverviewRepository(client)
                .toolCallCount(new FindingFilters.Sql("", List.of())))
                .as("the tile counts observed calls")
                .isEqualTo(1);

        // excluded from the rate denominators: the cohort's toolCalls is exactly the value the
        // controller divides findings by — one observed call, not two rows. With the orphan
        // counted the rate would read 500.0 instead of 1000.0.
        assertThat(new CohortRepository(client)
                .cohorts("harness_version", new FindingFilters(
                        new InsightFilter(null, null, null, null, null, null)))
                .cohorts())
                .singleElement()
                .satisfies(cohort -> {
                    assertThat(cohort.toolCalls()).as("the rate denominator").isEqualTo(1);
                    assertThat(cohort.findings()).as("the numerator is unchanged").isEqualTo(1);
                    assertThat(cohort.guardFindings()).isZero();
                });

        // the finding on the matched row is unaffected: the seq join still resolves its tool
        final FindingDetailDto detail =
                new FindingRepository(client).detail(1L).orElseThrow();
        assertThat(detail.tool()).isEqualTo("edit");
    }

    @Test
    void aStaleSchemaVersionEmptiesEveryTableAndSeedsTheCurrentOne() {
        // The defect a schema bump used to be: tool_call gained a column, the DDL is
        // CREATE TABLE IF NOT EXISTS, and a database written by the older build meets the
        // next index run with "no such column". The version row is the hook: a mismatch
        // invalidates the whole index, it does not migrate it.
        writeAll();
        writer.seedMeta("0");

        final int discarded = writer.resetIfStale(IndexService.SCHEMA_VERSION);

        assertThat(discarded).isEqualTo(1);
        assertThat(count("session")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("tool_call")).isZero();
        assertThat(count("finding")).isZero();
        assertThat(count("shell_evidence")).isZero();
        assertThat(writer.countSessions()).isZero();
        assertThat(jdbc.queryForList("select key, value from meta"))
                .containsExactly(Map.of("key", "schema_version", "value", IndexService.SCHEMA_VERSION));
    }

    @Test
    void aDatabaseAtTheCurrentVersionIsNotReset() {
        // A reboot of a working install must not throw the index away. This is the test
        // that keeps the feature from becoming "reset everything": with the version check
        // inverted, every boot of a healthy install would discard a good index and pay for
        // a rescan nobody asked for.
        writeAll();
        writer.seedMeta(IndexService.SCHEMA_VERSION);

        final int discarded = writer.resetIfStale(IndexService.SCHEMA_VERSION);

        assertThat(discarded).isZero();
        assertThat(count("session")).isEqualTo(1);
        assertThat(count("step")).isEqualTo(2);
        assertThat(count("tool_call")).isEqualTo(4);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("shell_evidence")).isEqualTo(1);
        assertThat(jdbc.queryForList("select key, value from meta"))
                .containsExactly(Map.of("key", "schema_version", "value", IndexService.SCHEMA_VERSION));
    }

    @Test
    void aDatabaseWithNoSchemaVersionRowIsTreatedAsStale() {
        // "No value stored" is a mismatch, not a default to keep: a database that predates
        // the version row is emptied, re-seeded, and left usable.
        writeAll();

        final int discarded = writer.resetIfStale(IndexService.SCHEMA_VERSION);

        assertThat(discarded).isEqualTo(1);
        assertThat(count("session")).isZero();
        assertThat(jdbc.queryForList("select key, value from meta"))
                .containsExactly(Map.of("key", "schema_version", "value", IndexService.SCHEMA_VERSION));
    }

    @Test
    void anIndexBuiltFromAnotherCorpusIsResetRatherThanServed() {
        // The defect this closes: run.sh demo, then run.sh live, both on the default database
        // file. The gate read "sessions present" as "already indexed" and served 389 findings
        // from the real corpus to a run configured for the fixtures — a dashboard quietly
        // describing data it had not read. Provenance, not population, decides reuse.
        writeAll();
        writer.seedCorpus(temp.resolve("corpus-a").toString());

        final int discarded = writer.resetIfCorpusChanged(temp.resolve("corpus-b"));

        assertThat(discarded).isEqualTo(1);
        assertThat(count("session")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("tool_call")).isZero();
        assertThat(count("finding")).isZero();
        assertThat(count("shell_evidence")).isZero();
    }

    @Test
    void anIndexBuiltFromTheCorpusNowConfiguredIsNotReset() {
        // The other half: a reboot against the same corpus keeps its index. Without this the
        // provenance check would degrade into "wipe on every boot" and the gate would buy
        // nothing.
        writeAll();
        final Path corpus = temp.resolve("corpus-a");
        writer.seedCorpus(corpus.toString());

        final int discarded = writer.resetIfCorpusChanged(corpus);

        assertThat(discarded).isZero();
        assertThat(count("session")).isEqualTo(1);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("shell_evidence")).isEqualTo(1);
    }

    @Test
    void theSameCorpusSpelledDifferentlyIsRecognisedAsOneDirectory() {
        // run.sh hands the JVM a relative path and the record holds an absolute one; a check
        // on raw strings would wipe a good index over a "./" nobody meant as a new corpus.
        final Path corpus = temp.resolve("corpus-a");
        writeAll();
        writer.seedCorpus(corpus.toAbsolutePath().normalize().toString());

        assertThat(writer.resetIfCorpusChanged(
                Path.of(temp.toString(), "corpus-a", "..", "corpus-a"))).isZero();
        assertThat(count("session")).isEqualTo(1);
    }

    @Test
    void anIndexOfUnknownProvenanceIsResetRatherThanServed() {
        // A database written before the corpus was recorded has nothing to compare against.
        // Guessing "same corpus" is what produced the bug; the safe reading is that an index
        // which cannot say where it came from does not describe the run.
        writeAll();

        assertThat(writer.resetIfCorpusChanged(temp.resolve("corpus-a"))).isEqualTo(1);
        assertThat(count("session")).isZero();
    }

    @Test
    void theResetKeepsForeignKeysEnabled() throws IOException {
        // Production turns the guard on in the JDBC URL; a single-connection pool means the
        // reset runs on the same connection the assertions read, so a "fix" that disables
        // the guard to dodge a constraint error cannot hide. The delete order is
        // child-to-parent precisely so no such fix is ever wanted.
        final HikariDataSource pooled = new HikariDataSource();
        pooled.setJdbcUrl("jdbc:sqlite:" + temp.resolve("fk.sqlite") + "?foreign_keys=on");
        pooled.setMaximumPoolSize(1);
        try {
            final JdbcTemplate fk = new JdbcTemplate(pooled);
            executeSchema(fk);
            final IndexWriter fkWriter = new IndexWriter(fk, new DataSourceTransactionManager(pooled));
            fkWriter.writeStream(source(), facts(), findings(), VERSION, true, T0 + 999);
            fkWriter.seedMeta("0");

            assertThat(fk.queryForObject("pragma foreign_keys", Integer.class)).isEqualTo(1);

            final int discarded = fkWriter.resetIfStale(IndexService.SCHEMA_VERSION);

            assertThat(discarded).isEqualTo(1);
            assertThat(fk.queryForObject("select count(*) from shell_evidence", Integer.class)).isZero();
            assertThat(fk.queryForObject("pragma foreign_keys", Integer.class))
                    .as("the guard the reset ran under stays on")
                    .isEqualTo(1);
        } finally {
            pooled.close();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private void writeAll() {
        writer.writeStream(source(), facts(), findings(), VERSION, true, T0 + 999);
    }

    private int count(final String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private SessionSource source() {
        return new SessionSource(temp.resolve("demo-app/s-1/session.jsonl.zstd"),
                "s-1", "demo-app", Convention.V0, "session.jsonl.zstd");
    }

    private StreamFacts facts() {
        return new StreamFacts(
                new SessionRecord("s-1", "session.jsonl.zstd", "demo-app", "V0", T0, T0 + 60_000,
                        null, null, "model-a", 131072, 1, "/home/dev/demo"),
                List.of(
                        new StepRecord(0, 0, T0 + 100, T0 + 2000, 1000, 700, 350.0, 500, "chunk-events"),
                        new StepRecord(1, 0, T0 + 3000, null, 1000, 100, null, null, "none")),
                List.of(
                        new ToolCallRecord(0, 0, 5, "read", T0 + 200, T0 + 300, 100L, null,
                                "/home/dev/demo/App.java", false),
                        new ToolCallRecord(0, 0, 7, "bash", T0 + 400, T0 + 500, 100L, null, null,
                                false),
                        new ToolCallRecord(0, 0, 9, "edit", T0 + 600, T0 + 700, 100L,
                                "FS_STALE_VERSION", "/home/dev/demo/App.java", false),
                        new ToolCallRecord(1, 0, 11, "write", T0 + 800, T0 + 900, 100L,
                                "FS_NOT_FOUND", "/home/dev/other/missing.txt", false)),
                List.of(new FileTouch(5, "/home/dev/demo/App.java", FileTouch.READ),
                        new FileTouch(9, "/home/dev/demo/App.java", FileTouch.WRITE)),
                List.of(new ShellEvidence(7, Set.of("/home/dev/demo/App.java"), VerbClass.MUTATING,
                        new RedactedExcerpt("sed -i 's/a/b/' App.java"))),
                List.of(new ErrorEvent(9, 0, 0, "edit", "FS_STALE_VERSION",
                        "/home/dev/demo/App.java", T0 + 700),
                        new ErrorEvent(11, 1, 0, "write", "FS_NOT_FOUND",
                                "/home/dev/other/missing.txt", T0 + 900)),
                List.of(new FatalTurn(2, "media_budget_exceeded", T0 + 55_000)),
                List.of(new RetryEvent(13, 2, 0, "TIMEOUT", T0 + 56_000)),
                0L);
    }

    private List<Finding> findings() {
        return List.of(
                new Finding("stamp-guard", Plane.GUARD, Category.DIRECT_MUTATION, "FS_STALE_VERSION",
                        0.9, "/home/dev/demo/App.java", 9, 5, 7, T0 + 700,
                        "App.java refused: stamp stale since seq 5 (read); consistent with a mutating"
                                + " command at seq 7 (absolute path match)",
                        List.of(new ShellEvidence(7, Set.of("/home/dev/demo/App.java"),
                                VerbClass.MUTATING, new RedactedExcerpt("sed -i 's/a/b/' App.java")))),
                new Finding("error-plane", Plane.MODEL_MISUSE, null, "FS_NOT_FOUND", null,
                        "/home/dev/other/missing.txt", 11, null, null, T0 + 900,
                        "write returned FS_NOT_FOUND", List.of()));
    }
}
