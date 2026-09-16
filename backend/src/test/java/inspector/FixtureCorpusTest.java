package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.ErrorPlaneDetector;
import inspector.detect.FatalTurnDetector;
import inspector.detect.RetryStormDetector;
import inspector.detect.StampGuardDetector;
import inspector.index.IndexService;
import inspector.ingest.CorpusScanner;
import inspector.ingest.ShellAnalyzer;
import inspector.ingest.SessionIngestor;
import inspector.store.IndexWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The committed fixture corpus, indexed into a temporary database and asserted on. This is the
 * acceptance proof that every planned scenario actually produces its planned finding — the
 * read-side tests in {@code inspector.api} build on exactly these rows.
 *
 * <p>No Spring context: the pipeline is hand-constructed over {@code fixtures/sessions} and
 * {@code fixtures/sessions-b} into a {@code @TempDir} SQLite file. The index service logs one
 * summary line per run; nothing here may write anywhere inside the repository.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FixtureCorpusTest {

    /** The two corpora stand in for two harness versions, so the cohort route has two rows. */
    private static final String MAIN_VERSION = "0.1.5-rc.2";
    private static final String SECOND_VERSION = "0.1.4";

    @TempDir
    static Path temp;

    private JdbcTemplate jdbc;
    private IndexService service;

    @BeforeAll
    void indexBothCorpora() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("corpus.sqlite"));
        jdbc = new JdbcTemplate(dataSource);
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            final String ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (final String statement : ddl.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        final ObjectMapper mapper = new ObjectMapper();
        final SessionIngestor ingestor = new SessionIngestor(mapper, new ShellAnalyzer(mapper));
        final StampGuardDetector stamp = new StampGuardDetector();
        final FatalTurnDetector fatal = new FatalTurnDetector();
        final RetryStormDetector retry = new RetryStormDetector();
        // Spring hands the error detector every other detector in the context; a hand-built list
        // has to say the same thing or it is testing a wiring the application never has.
        final List<Detector> detectors = List.of(stamp,
                new ErrorPlaneDetector(List.of(stamp, fatal, retry)), fatal, retry);
        final IndexWriter writer = new IndexWriter(jdbc, new DataSourceTransactionManager(dataSource));
        service = new IndexService(new CorpusScanner(), ingestor, detectors, writer,
                new InspectorProperties("fixtures/sessions", MAIN_VERSION,
                        new InspectorProperties.Evidence(true)));

        service.run(Path.of("fixtures/sessions"), MAIN_VERSION);
        service.run(Path.of("fixtures/sessions-b"), SECOND_VERSION);
    }

    private long count(final String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void theCorpusHasEveryPlannedStream() {
        assertThat(count("select count(*) from session")).isEqualTo(15);
        assertThat(count("select count(*) from session where source_file like '%session.jsonl.zstd%'"))
                .isEqualTo(11);
        assertThat(count("select count(*) from session where source_file like '%session.v3.jsonl.zstd%'"))
                .isEqualTo(4);
        // s-06 exists in both conventions: two rows, one session id.
        assertThat(count("select count(*) from session where id = 's-06'")).isEqualTo(2);
        assertThat(count("select count(distinct id) from session")).isEqualTo(14);
        assertThat(count("select count(distinct harness_version) from session")).isEqualTo(2);
    }

    @Test
    void findingsAppearOnAllThreePlanes() {
        final Map<String, Long> byPlane = new java.util.HashMap<>();
        jdbc.query("select plane, count(*) from finding group by plane",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        byPlane.put(rs.getString(1), rs.getLong(2)));
        assertThat(byPlane.keySet())
                .containsExactlyInAnyOrder("INFRASTRUCTURE", "GUARD", "MODEL_MISUSE");
        assertThat(byPlane.get("GUARD")).isEqualTo(6);
        assertThat(byPlane.get("INFRASTRUCTURE")).isEqualTo(4);
        assertThat(byPlane.get("MODEL_MISUSE")).isEqualTo(2);
    }

    @Test
    void theAttributionScenariosProduceTheirPlannedFindings() {
        // absolute-path attribution: HIGH confidence
        assertThat(count("select count(*) from finding where detector = 'stamp-guard'"
                + " and category = 'DIRECT_MUTATION' and confidence = 0.9")).isEqualTo(3);
        // s-02: git checkout in the window
        assertThat(count("select count(*) from finding where detector = 'stamp-guard'"
                + " and category = 'VCS_RESTORE' and confidence = 0.9 and session_id = 's-02'"))
                .isEqualTo(1);
        // s-03: the command only mentions the path -> EXTERNAL with no confidence,
        // and the stale touch is still recorded
        assertThat(count("select count(*) from finding where detector = 'stamp-guard'"
                + " and category = 'EXTERNAL' and confidence is null and session_id = 's-03'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from finding where session_id = 's-03' and stale_seq > 0"))
                .isEqualTo(1);
        // every stamp-guard finding carries a causal sequence, s-03's cause is null
        assertThat(count("select count(*) from finding where detector = 'stamp-guard' and cause_seq is null"))
                .isEqualTo(1);
    }

    @Test
    void theContaminationSessionYieldsExactlyOneFatalTurnFinding() {
        // one real fatal turn, three user messages merely citing the code: the §5.2 trap.
        assertThat(count("select count(*) from finding where detector = 'fatal-turn'"
                + " and session_id = 's-04'")).isEqualTo(1);
        assertThat(count("select count(*) from finding where detector = 'fatal-turn'"))
                .isEqualTo(1);
        final Map<String, Object> row = jdbc.queryForMap(
                "select code, plane, confidence from finding where detector = 'fatal-turn'");
        assertThat(row.get("code")).isEqualTo("media_budget_exceeded");
        assertThat(row.get("plane")).isEqualTo("INFRASTRUCTURE");
        assertThat(row.get("confidence")).isNull();
    }

    @Test
    void retryStormsKeepTheLastCode() {
        assertThat(count("select count(*) from finding where detector = 'retry-storm'"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select code from finding where detector = 'retry-storm' and session_id = 's-05'",
                String.class)).isEqualTo("TIMEOUT");
        assertThat(jdbc.queryForObject(
                "select code from finding where detector = 'retry-storm' and session_id = 's-13'",
                String.class)).isEqualTo("SERVER");
    }

    @Test
    void theErrorPlaneOwnsExactlyTheUnownedCodes() {
        assertThat(count("select count(*) from finding where detector = 'error-plane'"))
                .isEqualTo(4);
        assertThat(jdbc.queryForList(
                        "select code from finding where detector = 'error-plane' order by code",
                        String.class))
                .containsExactly("FS_NOT_FOUND", "FS_NOT_OBSERVED", "SEARCH_FAILED",
                        "WEB_PROVIDER_CREDENTIAL_MISSING");
        assertThat(count("select count(*) from finding where detector = 'error-plane'"
                + " and confidence is not null")).isZero();
    }

    @Test
    void eventTimesSpreadOverSeveralDays() {
        // findings must not collapse into one day: the 30-day UI filter sees a spread
        assertThat(count("select count(distinct date(occurred_at / 1000.0, 'unixepoch'))"
                + " from finding")).isGreaterThanOrEqualTo(8);
        assertThat(count("select count(distinct date(occurred_at / 1000.0, 'unixepoch'))"
                + " from finding where session_id = 's-04'")).isEqualTo(1);
    }

    @Test
    void theTimingInvariantsHold() {
        assertThat(count("select count(*) from step where ttft_ms is not null and ttft_ms < 0"))
                .isZero();
        assertThat(count("select count(*) from step where timing_source = 'none'"
                + " and (decode_tps is not null or ttft_ms is not null)")).isZero();
        // all three timing sources are reachable
        assertThat(jdbc.queryForList(
                        "select distinct timing_source from step order by timing_source", String.class))
                .containsExactly("chunk-events", "embedded-stream", "none");
        // s-08's untimed stream step really stored NULLs
        assertThat(count("select count(*) from step s join session x on x.id = s.session_id"
                + " and x.source_file = s.source_file where s.session_id = 's-08'"
                + " and s.timing_source = 'none' and s.decode_tps is null and s.ttft_ms is null"))
                .isEqualTo(1);
    }

    @Test
    void nullModelAndPresetBucketsStayReachable() {
        // s-09 has no request/context event; s-08's v3 header omits agentPreset
        assertThat(count("select count(*) from session where id = 's-09' and model is null"))
                .isEqualTo(1);
        assertThat(count("select count(*) from session where id = 's-08' and agent_preset is null"))
                .isEqualTo(1);
    }
}
