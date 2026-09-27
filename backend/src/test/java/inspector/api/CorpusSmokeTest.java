package inspector.api;

import inspector.TestPipeline;
import inspector.TestStore;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.ErrorPlaneDetector;
import inspector.detect.ErrorPlanes;
import inspector.detect.FatalTurnDetector;
import inspector.detect.RetryStormDetector;
import inspector.detect.StampGuardDetector;
import inspector.index.IndexService;
import inspector.index.IndexSummary;
import inspector.ingest.CorpusScanner;
import inspector.ingest.ShellAnalyzer;
import inspector.ingest.SessionIngestor;
import inspector.store.IndexWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Structure smoke over the frozen local corpus copy at {@code ../corpus/sessions}
 * (DESIGN.md §11's "--dsh-home smoke run"). Gated behind {@code -Dinspector.smoke=true} so a
 * plain {@code mvn test} never touches the corpus, and indexed with
 * {@code inspector.evidence.store=false} so no real command text is ever written anywhere: the
 * database lives in a {@code @TempDir}, never inside the repository.
 *
 * <p>The corpus is live personal data, so this test asserts <b>structure, never counts</b> —
 * count bands would drift as sessions are written. What it asserts: more than 150 streams
 * scanned, zero parse failures, every persisted {@code error_code} in
 * {@link ErrorPlanes#knownToolCodes()} or explicitly reported as unmapped (names only, in the
 * log line), every persisted {@code path_hint} relative, at least one {@code FS_STALE_VERSION}
 * stamp-guard finding, and zero {@code shell_evidence} rows while attributed findings still
 * carry {@code cause_seq}.
 *
 * <p><b>Counts and codes only, never content.</b> The log line reports numbers and taxonomy
 * codes; a message, a real absolute path or a project name from the corpus in a log line is a
 * leak (AGENTS.md).
 */
@EnabledIfSystemProperty(named = "inspector.smoke", matches = "true")
final class CorpusSmokeTest {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusSmokeTest.class);
    private static final Path CORPUS = Path.of("../corpus/sessions");

    @TempDir
    static Path temp;

    @Test
    void theCorpusIndexesCleanlyWithStructureIntact() throws Exception {
        final Path dbFile = temp.resolve("smoke.sqlite");
        // evidence storage disabled: no real command text is persisted, anywhere
        final TestStore store = TestStore.open(dbFile);
        final JdbcTemplate jdbc = store.jdbc();
        final IndexService service = TestPipeline.indexService(store,
                TestPipeline.properties(CORPUS.toString(), "smoke", false));
        final IndexSummary summary = service.run(CORPUS, "smoke");

        assertThat(summary.streams()).as("streams scanned").isGreaterThan(150);
        assertThat(summary.parseFailures()).as("parse failures").isZero();

        // every persisted error code is one §3.1 knows, or is named here as unmapped:
        // ErrorPlanes.ofToolCode silently defaults unknown codes to INFRASTRUCTURE, and a
        // silent default over live data is what this smoke run exists to catch
        final Set<String> errorCodes =
                new TreeSet<>(jdbc.queryForList(
                        "select distinct error_code from tool_call where error_code is not null",
                        String.class));
        final Set<String> unmapped = errorCodes.stream()
                .filter(code -> !ErrorPlanes.knownToolCodes().contains(code))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(errorCodes).as("the corpus really does persist tool errors").isNotEmpty();

        // every persisted path hint is project-relative; an absolute path would be a §4.1 breach
        final List<String> pathHints = jdbc.queryForList(
                "select path_hint from finding where path_hint is not null"
                        + " union select path_hint from tool_call where path_hint is not null"
                        + " union select path_hint from shell_evidence where path_hint is not null",
                String.class);
        assertThat(pathHints).as("the corpus really does persist path hints").isNotEmpty();
        assertThat(pathHints)
                .as("persisted path hints must be project-relative")
                .noneSatisfy(hint -> assertThat(hint).startsWith("/"));

        final long staleVersionFindings = count(
                "select count(*) from finding where code = 'FS_STALE_VERSION' and detector = 'stamp-guard'",
                jdbc);
        assertThat(staleVersionFindings).as("stamp-guard findings").isGreaterThanOrEqualTo(1);

        final long evidenceRows = count("select count(*) from shell_evidence", jdbc);
        final long attributed = count("select count(*) from finding where cause_seq is not null", jdbc);
        assertThat(evidenceRows)
                .as("evidence storage is disabled: no excerpt is persisted")
                .isZero();
        assertThat(attributed)
                .as("attributed findings still carry their causal sequence without the evidence")
                .isGreaterThanOrEqualTo(1);

        // tool-call row basis: some rows are tool/results whose tool/call never appeared
        // (outcome_only = 1). They are stored so no outcome is lost, but they are not
        // observed calls — the split is reported so the rate denominators stay honest
        final long toolCallRows = count("select count(*) from tool_call", jdbc);
        final long outcomeOnlyRows = count("select count(*) from tool_call where outcome_only = 1", jdbc);
        assertThat(toolCallRows).as("the corpus really does persist tool calls").isGreaterThan(0);
        assertThat(outcomeOnlyRows)
                .as("some rows are observed calls, not only orphan results")
                .isLessThan(toolCallRows);
        final List<java.util.Map<String, Object>> byConvention = jdbc.queryForList(
                "select s.\"schema\" as convention, count(*) as rows,"
                        + " sum(case when t.outcome_only = 1 then 1 else 0 end) as outcome_only"
                        + " from tool_call t join session s on s.id = t.session_id"
                        + " and s.source_file = t.source_file group by 1 order by 1");

        // counts and codes only — never content, never a corpus path
        LOG.info("smoke: streams={} sessions={} findings={} parseFailures={} errorCodes={} unmapped={} "
                        + "pathHints={} staleVersionFindings={} attributedWithCause={} evidenceRows={} "
                        + "toolCallRows={} outcomeOnlyRows={} byConvention={}",
                summary.streams(), summary.sessions(), summary.findings(), summary.parseFailures(),
                errorCodes.size(), unmapped, pathHints.size(), staleVersionFindings, attributed,
                evidenceRows, toolCallRows, outcomeOnlyRows, byConvention);
    }

    private static long count(final String sql, final JdbcTemplate jdbc) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
