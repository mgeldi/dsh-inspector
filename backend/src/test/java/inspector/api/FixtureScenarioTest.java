package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The DESIGN.md §11 fixture scenarios, walked end to end over {@code fixtures/sessions} into a
 * {@code @TempDir} database. {@code FixtureCorpusTest} pins the same rows across <i>both</i>
 * corpora for the read-side tests; this class re-asserts the load-bearing scenarios on the main
 * corpus and adds what that class does not check from the privacy side:
 *
 * <ul>
 *   <li>no persisted string — {@code finding.summary} or {@code shell_evidence.excerpt_redacted}
 *       — carries an absolute path (§4.1: persisted text is generated prose and relative paths),
 *   <li>the {@code mention-without-mutation} finding has {@code confidence IS NULL} and zero
 *       evidence rows — the mention is <i>not</i> stored as evidence (§5.3),
 *   <li>the {@code vcs-restore} finding exists and its summary says the restore is legitimate,
 *   <li>the two-convention session is indexed as two streams with independent seq spaces (§3.2),
 *   <li>the documentation-contamination session yields exactly one {@code fatal-turn} finding
 *       no matter how many events merely mention the code (§5.2).</li>
 * </ul>
 *
 * <p>No Spring context: the pipeline is hand-constructed exactly like {@code IndexedCorpus};
 * nothing here writes anywhere inside the repository.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FixtureScenarioTest {

    /** A token that starts at the root of the filesystem: an absolute path anywhere in the text. */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![A-Za-z0-9_])/[A-Za-z0-9][A-Za-z0-9._/-]*");

    @TempDir
    static Path temp;

    private JdbcTemplate jdbc;

    @BeforeAll
    void indexMainCorpusWithEvidenceStored() {
        final Path dbFile = temp.resolve("scenario.sqlite");
        // evidence on: the path assertions must hold with excerpts actually persisted
        IndexedCorpus.index(dbFile, Path.of("fixtures/sessions"), IndexedCorpus.MAIN_VERSION, true);
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
    }

    private long count(final String sql, final Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void theMainCorpusIndexesWithThePlannedStreams() {
        // 11 sessions in 12 streams: s-06 exists in both conventions. A missing fixture file
        // would show up here, where the scenario tests below would only fail obscurely.
        assertThat(count("select count(*) from session")).isEqualTo(12);
        assertThat(count("select count(*) from finding")).isEqualTo(9);
    }

    @Test
    void theContaminationSessionYieldsExactlyOneFatalTurnFinding() {
        // s-04: one real fatal turn, and three user messages merely citing the code in
        // documentation. The mentions are events, not findings: §5.2's trap is that a naive
        // scan would count four.
        assertThat(count("select count(*) from finding where session_id = 's-04'")).isEqualTo(1);
        assertThat(count("select count(*) from finding where session_id = 's-04'"
                + " and detector = 'fatal-turn'")).isEqualTo(1);
        final Map<String, Object> row = jdbc.queryForMap(
                "select code, plane, confidence, seq from finding where session_id = 's-04'");
        assertThat(row.get("code")).isEqualTo("media_budget_exceeded");
        assertThat(row.get("plane")).isEqualTo("INFRASTRUCTURE");
        assertThat(row.get("confidence")).isNull();
        assertThat(row.get("seq")).isNull();
    }

    @Test
    void noPersistedStringCarriesAnAbsolutePath() {
        // §4.1: persisted text is a generated summary sentence or a project-relative path. The
        // excerpt is the one designed exception (a redacted command view, DESIGN.md §4.1), so it
        // may reference a path mid-command — what it must never be is an absolute path itself,
        // and a summary must never contain one at all.
        assertThat(jdbc.queryForList("select summary from finding", String.class))
                .as("finding.summary must not start with '/'")
                .allSatisfy(summary -> assertThat(summary).doesNotStartWith("/"));
        assertThat(jdbc.queryForList("select summary from finding", String.class))
                .as("finding.summary must contain no absolute path")
                .allSatisfy(summary -> assertThat(summary).doesNotMatch(ABSOLUTE_PATH));
        assertThat(jdbc.queryForList("select excerpt_redacted from shell_evidence", String.class))
                .as("shell_evidence.excerpt_redacted must not start with '/'")
                .allSatisfy(excerpt -> assertThat(excerpt).doesNotStartWith("/"));
        assertThat(jdbc.queryForList(
                        "select path_hint from finding where path_hint is not null", String.class))
                .as("finding.path_hint")
                .allSatisfy(hint -> assertThat(hint).doesNotStartWith("/"));
        assertThat(jdbc.queryForList(
                        "select path_hint from tool_call where path_hint is not null", String.class))
                .as("tool_call.path_hint")
                .allSatisfy(hint -> assertThat(hint).doesNotStartWith("/"));
        assertThat(jdbc.queryForList(
                        "select path_hint from shell_evidence where path_hint is not null", String.class))
                .as("shell_evidence.path_hint")
                .allSatisfy(hint -> assertThat(hint).doesNotStartWith("/"));

        // non-vacuity: the corpus really does persist evidence text and generated summaries
        assertThat(count("select count(*) from shell_evidence")).isGreaterThan(0);
        assertThat(count("select count(*) from finding")).isGreaterThan(0);
    }

    @Test
    void mentionWithoutMutationHasNullConfidenceAndZeroEvidenceRows() {
        // s-03: the in-window command only mentions the path (it executes a script that names it).
        // Classified external — §5.3: "the mention is not stored as evidence".
        final long findingId = jdbc.queryForObject(
                "select id from finding where session_id = 's-03' and detector = 'stamp-guard'",
                Long.class);
        final Map<String, Object> row =
                jdbc.queryForMap("select category, confidence, cause_seq, stale_seq from finding where id = ?",
                        findingId);
        assertThat(row.get("category")).isEqualTo("EXTERNAL");
        assertThat(row.get("confidence")).as("unattributed findings store NULL, never a guess").isNull();
        assertThat(row.get("cause_seq")).isNull();
        assertThat(row.get("stale_seq")).as("the stale touch is still derivable and recorded").isNotNull();
        assertThat(count("select count(*) from shell_evidence where finding_id = ?", findingId))
                .as("a mere mention is not stored as evidence")
                .isZero();
    }

    @Test
    void theVcsRestoreFindingExistsAndSaysTheRestoreIsLegitimate() {
        // s-02: git checkout in the window. Legitimate work the stamp cannot know about —
        // not a violation — and the summary must say so, not flag it.
        final Map<String, Object> row = jdbc.queryForMap(
                "select category, confidence, cause_seq, summary from finding"
                        + " where session_id = 's-02' and detector = 'stamp-guard'");
        assertThat(row.get("category")).isEqualTo("VCS_RESTORE");
        assertThat(row.get("confidence")).isEqualTo(0.9);
        assertThat(row.get("cause_seq")).isNotNull();
        assertThat((String) row.get("summary")).contains("legitimate");
    }

    @Test
    void theTwoConventionSessionIsTwoStreamsWithIndependentSeqSpaces() {
        // s-06: one session id, two files, one per convention (§3.2). Each file numbers its
        // events from 1, so the two files' tool_call seqs overlap — a merged or renumbered
        // space could not produce that overlap.
        assertThat(count("select count(*) from session where id = 's-06'")).isEqualTo(2);
        final List<String> files = jdbc.queryForList(
                "select distinct source_file from session where id = 's-06' order by source_file",
                String.class);
        assertThat(files).containsExactly("session.jsonl.zstd", "session.v3.jsonl.zstd");
        final long overlappingSeqs = count(
                "select count(*) from tool_call a join tool_call b on a.session_id = b.session_id"
                        + " and a.seq = b.seq"
                        + " where a.session_id = 's-06' and a.source_file <> b.source_file");
        assertThat(overlappingSeqs)
                .as("both files carry tool calls in the same (overlapping) seq space")
                .isGreaterThan(0);
        // and the finding sits on the v0 file, in that file's own seq space: the failed edit's
        // result is the v0 file's 11th event (session, 7 events of step 0, stepStart, call, result)
        final Map<String, Object> row =
                jdbc.queryForMap("select source_file, seq from finding where session_id = 's-06'");
        assertThat(row.get("source_file")).isEqualTo("session.jsonl.zstd");
        assertThat(((Number) row.get("seq")).intValue()).isEqualTo(11);
    }
}
