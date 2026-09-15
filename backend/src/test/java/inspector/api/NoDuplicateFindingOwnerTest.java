package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The one-owner-per-source-event invariant (plan rule 1, Task 7 deferred it here): no two
 * findings share {@code (session_id, source_file, seq)}. The specific detector claims its code
 * and {@code ErrorPlaneDetector} skips exactly the codes claimed elsewhere, so one source
 * error produces one finding — this test defends that against the indexed fixture corpora.
 *
 * <p>The ledger is closed by design, and the test states both halves: a finding that carries a
 * {@code seq} must be unique per (session, stream, seq), and the only seq-less findings are the
 * turn-level {@code fatal-turn} findings, which claim a turn rather than a source event.
 *
 * <p>Non-vacuity is asserted, not assumed: the corpus must contain a stream whose findings come
 * from two different detectors at different seqs (the case the invariant permits), and the
 * duplicate query itself is proven to catch a violation it is shown a planted duplicate row.
 *
 * <p>No Spring context: both committed corpora are indexed into a {@code @TempDir} database via
 * {@code IndexedCorpus}; nothing here writes anywhere inside the repository.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class NoDuplicateFindingOwnerTest {

    @TempDir
    static Path temp;

    private JdbcTemplate jdbc;

    @BeforeAll
    void indexBothCorpora() {
        final Path dbFile = temp.resolve("owner.sqlite");
        IndexedCorpus.indexBoth(dbFile);
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
    }

    /** The owner check: (session, stream, seq) groups with more than one finding. */
    private List<Map<String, Object>> duplicateOwners() {
        return jdbc.queryForList(
                "select session_id, source_file, seq, count(*) as n from finding"
                        + " where seq is not null"
                        + " group by session_id, source_file, seq"
                        + " having count(*) > 1");
    }

    @Test
    void noTwoFindingsShareOneSourceEvent() {
        assertThat(duplicateOwners()).as("two findings claiming one source event").isEmpty();
    }

    @Test
    void theOnlySeqlessFindingsAreTurnLevelFatalTurns() {
        // the ledger is closed: a NULL seq is either a fatal turn (turn-level, no event seq by
        // design) or a finding that claims no source event at all — which nothing else does
        assertThat(count("select count(*) from finding where seq is null and detector <> 'fatal-turn'"))
                .isZero();
        assertThat(count("select count(*) from finding where seq is not null and detector = 'fatal-turn'"))
                .isZero();
    }

    @Test
    void theCheckIsNotVacuous() {
        // (1) the invariant is checked against real multi-detector data: at least one stream
        // carries findings from two different detectors at different seqs — exactly the shape
        // the grouping distinguishes from a violation
        final long multiDetectorStreams = count(
                "select count(*) from (select session_id, source_file from finding"
                        + " where seq is not null"
                        + " group by session_id, source_file"
                        + " having count(distinct detector) >= 2 and count(distinct seq) >= 2)");
        assertThat(multiDetectorStreams)
                .as("at least one stream must hold two detectors' findings at different seqs")
                .isGreaterThan(0);

        // (2) the query can distinguish: plant a duplicate — the same (session, stream, seq)
        // claimed by a second detector — and it must surface; then remove it again
        final long plantedId = count("select coalesce(max(id), 0) + 1 from finding");
        jdbc.update(
                "insert into finding (id, session_id, source_file, detector, plane, code, seq,"
                        + " occurred_at, summary) select ?, session_id, source_file,"
                        + " 'planted-duplicate', plane, code, seq, occurred_at, summary"
                        + " from finding where seq is not null limit 1",
                plantedId);
        try {
            assertThat(duplicateOwners()).as("the owner query must catch a planted duplicate")
                    .hasSize(1);
        } finally {
            jdbc.update("delete from finding where id = ?", plantedId);
        }
        assertThat(duplicateOwners()).isEmpty();
    }

    private long count(final String sql, final Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
