package inspector.index;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.luben.zstd.ZstdOutputStream;
import inspector.config.InspectorProperties;
import inspector.config.StartupIndexRunner;
import inspector.detect.Detector;
import inspector.detect.ErrorPlaneDetector;
import inspector.detect.FatalTurnDetector;
import inspector.detect.RetryStormDetector;
import inspector.detect.StampGuardDetector;
import inspector.ingest.Convention;
import inspector.ingest.CorpusScanner;
import inspector.ingest.ShellAnalyzer;
import inspector.ingest.SessionIngestor;
import inspector.store.IndexWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole pipeline, hand-constructed over a temp corpus and a temp SQLite file — no Spring
 * context, so the assertions point at the orchestration and the SQL, not the wiring.
 *
 * <p>proj-a/s-1: one chunk-timed step, four tool calls, a refused edit with a mutating shell
 * cause (stamp-guard) and a refused write (error-plane). proj-b/s-2: one untimed step, three
 * retries in the same step (retry-storm). Every path and name is invented.
 */
final class IndexServiceTest {

    private static final long T0 = 1_760_000_000_000L;

    @TempDir
    Path temp;

    private Path corpus;
    private JdbcTemplate jdbc;
    private IndexWriter writer;
    private IndexService service;
    private ListAppender<ILoggingEvent> logAppender;
    private int seq;

    @BeforeEach
    void setUp() throws IOException {
        corpus = temp.resolve("corpus");
        writeS1(corpus);
        writeS2(corpus);

        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("index.sqlite"));
        jdbc = new JdbcTemplate(dataSource);
        writer = new IndexWriter(jdbc, new DataSourceTransactionManager(dataSource));
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            final String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final String statement : ddl.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        }

        final ObjectMapper mapper = new ObjectMapper();
        final SessionIngestor ingestor = new SessionIngestor(mapper, new ShellAnalyzer(mapper));
        final List<Detector> detectors = List.of(
                new ErrorPlaneDetector(new StampGuardDetector()),
                new StampGuardDetector(),
                new FatalTurnDetector(),
                new RetryStormDetector());
        service = new IndexService(new CorpusScanner(), ingestor, detectors, writer,
                propertiesOf(corpus));

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void anIndexRunReportsTheCorpusCounts() {
        final IndexSummary summary = service.run();

        assertThat(summary.streams()).isEqualTo(2);
        assertThat(summary.sessions()).isEqualTo(2);
        assertThat(summary.steps()).isEqualTo(2);
        assertThat(summary.toolCalls()).isEqualTo(4);
        assertThat(summary.findings()).isEqualTo(3);
        assertThat(summary.parseFailures()).isZero();
        assertThat(summary.durationMs()).isGreaterThanOrEqualTo(0);
        // the summary line is counts only — it is the index's only self-report
        assertThat(logMessages(Level.INFO))
                .anyMatch(msg -> msg.startsWith("indexed 2 streams, 3 findings in "));
    }

    @Test
    void aSecondRunOverTheSameCorpusChangesNothing() {
        service.run();
        final Map<String, Integer> before = counts();

        final IndexSummary second = service.run(corpus, "test-version");

        assertThat(counts()).isEqualTo(before);
        assertThat(second.streams()).isEqualTo(2);
        assertThat(second.sessions()).isEqualTo(2);
        assertThat(second.findings()).isEqualTo(3);
    }

    @Test
    void theAttributionSurvivesTheWholePipeline() {
        service.run();

        // stamp-guard: the refused edit (seq 10), the stale read (seq 6), the mutating
        // command that actually moved the stamp (seq 8)
        assertThat(jdbc.queryForList(
                "select category, confidence, seq, stale_seq, cause_seq, path_hint from finding"
                        + " where session_id = 's-1' and detector = 'stamp-guard'"))
                .containsExactly(Map.of(
                        "category", "DIRECT_MUTATION", "confidence", 0.9,
                        "seq", 10, "stale_seq", 6, "cause_seq", 8, "path_hint", "App.java"));
        // error-plane: the refused write, relativized against s-1's cwd
        assertThat(jdbc.queryForList(
                "select seq, path_hint, occurred_at from finding"
                        + " where session_id = 's-1' and detector = 'error-plane'"))
                .containsExactly(Map.of("seq", 12, "path_hint", "missing.txt", "occurred_at", T0 + 900));
        // retry-storm: three retries in one step, one finding carrying the last retry
        assertThat(jdbc.queryForList(
                "select plane, code, seq, occurred_at from finding where session_id = 's-2'"))
                .containsExactly(Map.of(
                        "plane", "INFRASTRUCTURE", "code", "TIMEOUT", "seq", 4,
                        "occurred_at", T0 + 1_000_300));
        // the cause command is the one and only evidence row
        assertThat(jdbc.queryForList(
                "select e.seq, e.verb_class, e.path_hint from shell_evidence e"
                        + " join finding f on f.id = e.finding_id where f.session_id = 's-1'"))
                .containsExactly(Map.of("seq", 8, "verb_class", "MUTATING", "path_hint", "App.java"));
    }

    @Test
    void theSessionRowsCarryTheVersionStringTheRunWasGiven() {
        service.run(corpus, "2.7.0-cohort");

        assertThat(jdbc.queryForList(
                "select harness_version, version_inferred from session order by id"))
                .containsExactly(
                        Map.of("harness_version", "2.7.0-cohort", "version_inferred", 1),
                        Map.of("harness_version", "2.7.0-cohort", "version_inferred", 1));
    }

    @Test
    void aCorpusWithOnlyALockFileYieldsZeroStreamsAndAWarning() throws IOException {
        final Path lockOnly = temp.resolve("lock-only");
        Files.createDirectories(lockOnly.resolve("proj-x/s-9"));
        Files.writeString(lockOnly.resolve("proj-x/s-9/session.lock"), "locked");

        final IndexSummary summary = service.run(lockOnly, "test-version");

        assertThat(summary.streams()).isZero();
        assertThat(summary.sessions()).isZero();
        assertThat(count("session")).isZero();
        assertThat(logMessages(Level.WARN))
                .anyMatch(msg -> msg.contains("no session streams"));
    }

    @Nested
    final class StartupRunner {

        @Test
        void anEmptyDatabaseWithACorpusIsIndexedOnStartup() {
            new StartupIndexRunner(service, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments());

            assertThat(writer.countSessions()).isEqualTo(2);
            assertThat(jdbc.queryForList("select key, value from meta"))
                    .containsExactly(Map.of("key", "schema_version", "value",
                            IndexService.SCHEMA_VERSION));
        }

        @Test
        void aNoIndexFlagLeavesTheDatabaseEmpty() {
            new StartupIndexRunner(service, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments("--no-index"));

            assertThat(writer.countSessions()).isZero();
            assertThat(logMessages(Level.INFO))
                    .noneMatch(msg -> msg.startsWith("indexed "));
        }

        @Test
        void aPopulatedDatabaseIsNeverRescannedEvenWhenTheCorpusIsMissing() {
            service.run();
            logAppender.list.clear();

            new StartupIndexRunner(service, propertiesOf(temp.resolve("nowhere")), writer)
                    .run(new DefaultApplicationArguments());

            // the database gate fires before the corpus check: no warning at all
            assertThat(logMessages(Level.WARN)).isEmpty();
            assertThat(writer.countSessions()).isEqualTo(2);
        }

        @Test
        void anEmptyDatabaseWithAMissingCorpusWarnsAndStaysEmpty() {
            new StartupIndexRunner(service, propertiesOf(temp.resolve("nowhere")), writer)
                    .run(new DefaultApplicationArguments());

            assertThat(writer.countSessions()).isZero();
            assertThat(logMessages(Level.WARN))
                    .anyMatch(msg -> msg.contains("corpus directory does not exist"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private InspectorProperties propertiesOf(final Path path) {
        return new InspectorProperties(path.toString(), "test-version",
                new InspectorProperties.Evidence(true));
    }

    private Map<String, Integer> counts() {
        return Map.of(
                "session", count("session"),
                "step", count("step"),
                "tool_call", count("tool_call"),
                "finding", count("finding"),
                "shell_evidence", count("shell_evidence"));
    }

    private int count(final String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private List<String> logMessages(final Level level) {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void writeS1(final Path root) throws IOException {
        // Note: no fixture path may contain "/dev/" — the analyzer's device filter treats
        // it as a substring, which would swallow the referenced path in a shell command.
        final Path file = root.resolve("proj-a/s-1/" + Convention.FILE_V0);
        Files.createDirectories(file.getParent());
        writeZstd(file,
                sessionLine("s-1", "/home/devuser/demo", T0),
                ev("request/context", T0,
                        "{\"provider\":\"p1\",\"model\":\"model-a\",\"contextWindow\":131072}"),
                ev("step/start", T0, "{\"turn\":0,\"step\":0}"),
                v0Chunk(T0 + 500),
                v0Chunk(T0 + 2500),
                ev("assistant/message", T0 + 2600,
                        "{\"turn\":0,\"step\":0,\"usage\":{\"inputTokens\":100,\"outputTokens\":500}}"),
                toolCall("c-read", "read", T0 + 100,
                        "{\"file_path\":\"/home/devuser/demo/App.java\"}"),
                toolResult("c-read", T0 + 200, null),
                toolCall("c-bash", "bash", T0 + 400,
                        "{\"command\":\"sed -i 's/a/b/' /home/devuser/demo/App.java\"}"),
                toolResult("c-bash", T0 + 500, null),
                toolCall("c-edit", "edit", T0 + 600,
                        "{\"file_path\":\"/home/devuser/demo/App.java\"}"),
                toolResult("c-edit", T0 + 700, "FS_STALE_VERSION"),
                toolCall("c-write", "write", T0 + 800,
                        "{\"file_path\":\"/home/otheruser/missing.txt\"}"),
                toolResult("c-write", T0 + 900, "FS_NOT_FOUND"));
    }

    private void writeS2(final Path root) throws IOException {
        // each stream numbers its own lines from zero — the ingestor reads the per-file seq
        seq = 0;
        final Path file = root.resolve("proj-b/s-2/" + Convention.FILE_V0);
        Files.createDirectories(file.getParent());
        final long t1 = T0 + 1_000_000;
        writeZstd(file,
                sessionLine("s-2", "/home/devuser/demo-b", t1),
                ev("step/start", t1, "{\"turn\":0,\"step\":0}"),
                ev("llm/retry", t1 + 100, "{\"turn\":0,\"step\":0,\"failure\":{\"code\":\"TIMEOUT\"}}"),
                ev("llm/retry", t1 + 200, "{\"turn\":0,\"step\":0,\"failure\":{\"code\":\"SERVER\"}}"),
                ev("llm/retry", t1 + 300, "{\"turn\":0,\"step\":0,\"failure\":{\"code\":\"TIMEOUT\"}}"));
    }

    private void writeZstd(final Path file, final String... lines) throws IOException {
        try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(file));
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (final String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    private String ev(final String type, final long time, final String data) {
        return "{\"type\":\"" + type + "\",\"seq\":" + seq++ + ",\"time\":" + time + ",\"data\":" + data + "}";
    }

    private String sessionLine(final String id, final String cwd, final long time) {
        return "{\"type\":\"session\",\"seq\":" + seq++ + ",\"time\":" + time + ",\"id\":\"" + id + "\""
                + ",\"createdAt\":" + time + ",\"cwd\":\"" + cwd + "\",\"version\":3"
                + ",\"agentPreset\":\"worker\",\"delegationDepth\":1,\"data\":{}}";
    }

    private String v0Chunk(final long time) {
        return ev("assistant/chunk", time,
                "{\"turn\":0,\"step\":0,\"chunk\":{\"type\":\"text\",\"index\":0}}");
    }

    private String toolCall(final String callId, final String name, final long time,
                            final String argumentsJson) {
        return ev("tool/call", time,
                "{\"turn\":0,\"step\":0,\"callId\":\"" + callId + "\",\"name\":\"" + name
                        + "\",\"arguments\":" + jsonString(argumentsJson) + "}");
    }

    private String toolResult(final String callId, final long time, final String code) {
        final String error = code == null ? ""
                : ",\"error\":{\"name\":\"FsError\",\"code\":\"" + code + "\"}";
        return ev("tool/result", time,
                "{\"turn\":0,\"step\":0,\"message\":{\"source\":{\"callId\":\"" + callId + "\"}}"
                        + error + "}");
    }

    private String jsonString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
