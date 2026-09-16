package inspector.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import inspector.detect.Finding;
import inspector.detect.RetryStormDetector;
import inspector.detect.StampGuardDetector;
import inspector.ingest.Convention;
import inspector.ingest.CorpusScanner;
import inspector.ingest.ShellAnalyzer;
import inspector.ingest.SessionIngestor;
import inspector.ingest.StreamFacts;
import inspector.store.IndexWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        final StampGuardDetector stamp = new StampGuardDetector();
        final FatalTurnDetector fatal = new FatalTurnDetector();
        final RetryStormDetector retry = new RetryStormDetector();
        // One instance of each, and the error detector is told what the others own. It used to
        // be built with a second, throwaway StampGuardDetector while a third one sat in the
        // pipeline — harmless while the owned set is a constant, and exactly the kind of wiring
        // that stops matching the context the day an owned code becomes state.
        final List<Detector> detectors = List.of(
                new ErrorPlaneDetector(List.of(stamp, fatal, retry)), stamp, fatal, retry);
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
        // The evidence count is the number of rows the run wrote, checked against the table
        // rather than against another number the same code produced.
        assertThat(summary.evidenceRows()).isEqualTo(count("shell_evidence"));
        assertThat(summary.parseFailures()).isZero();
        assertThat(summary.durationMs()).isGreaterThanOrEqualTo(0);
        // the summary line is counts only — it is the index's only self-report
        assertThat(logMessages(Level.INFO))
                .anyMatch(msg -> msg.matches("indexed 2 streams, 3 findings \\(\\d+ evidence rows\\)"
                        + " in \\d+ ms \\(0 parse failures, 0 pruned\\)"));
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
        assertThat(second.pruned()).isZero();
    }

    @Test
    void aStreamThatVanishedFromTheCorpusIsGoneFromTheIndexOnTheNextRun() throws IOException {
        // The defect as a user met it: the run's summary line said 8 findings, the tiles next
        // to it said 9, and the extra one belonged to a session that was no longer in the
        // corpus. writeStream deletes the stream it is about to write and nothing else, so
        // nothing ever removed the rows of a stream that stopped being scanned.
        service.run();
        logAppender.list.clear();
        Files.delete(corpus.resolve("proj-b/s-2/" + Convention.FILE_V0));

        final IndexSummary second = service.run(corpus, "test-version");

        assertThat(second.streams()).isEqualTo(1);
        assertThat(second.sessions()).isEqualTo(1);
        assertThat(second.pruned()).isEqualTo(1);
        // gone from every table that carried it, evidence rows included
        assertThat(counts()).isEqualTo(Map.of(
                "session", 1, "step", 1, "tool_call", 4, "finding", 2, "shell_evidence", 1));
        // the assertion that matters: what the run reports and what the index can serve are
        // the same numbers, because a dashboard showing either is showing one screen
        assertThat(count("session")).isEqualTo(second.streams());
        assertThat(count("step")).isEqualTo(second.steps());
        assertThat(count("finding")).isEqualTo(second.findings());
        // and pruning a stream leaves no orphan behind — child rows go before their parent
        assertThat(jdbc.queryForObject("select count(*) from shell_evidence where finding_id"
                + " not in (select id from finding)", Integer.class)).isZero();
        // counts only: a source_file is a path inside the corpus
        assertThat(logMessages(Level.INFO)).anyMatch(msg -> msg.contains("pruned them"));
        assertThat(logMessages(Level.INFO)).noneMatch(msg -> msg.contains(corpus.toString()));
    }

    @Test
    void aRunOverADifferentCorpusPrunesNothingItNeverRead() throws IOException {
        // The guard that keeps the prune from becoming a wipe: rows the run did not scan are
        // only prunable when the index says it came from the corpus being indexed. Here it
        // came from another one, so switching corpora stays resetIfCorpusChanged's business.
        service.run();
        final Path other = temp.resolve("other-corpus");
        writeS1(other);

        final IndexSummary second = service.run(other, "other-version");

        assertThat(second.streams()).isEqualTo(1);
        assertThat(second.pruned()).isZero();
        // s-2 belongs to the other corpus: still there, still to be cleared by the reset
        assertThat(count("session")).isEqualTo(2);
        assertThat(count("finding")).isEqualTo(3);
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

    /**
     * A run is single-flight (DESIGN.md §9). Every pause here is a detector waiting on a latch
     * that the test itself holds, never a sleep: "while a run is in flight" is a state these
     * tests establish and can point at, not a timing window they hope to fall into.
     *
     * <p>Both callers use the <em>same</em> service instance on purpose — the lock is a field, and
     * in the application there is exactly one instance, the one {@code IndexController} and
     * {@code StartupIndexRunner} were both handed. Two instances would have tested a lock nobody
     * contends with.
     */
    @Nested
    final class SingleFlight {

        /** Stands in the pipeline and holds the run open until the test lets it finish. */
        private final class Gate implements Detector {

            private final CountDownLatch entered;
            private final CountDownLatch release;
            private final AtomicInteger calls = new AtomicInteger();
            private volatile boolean throwsOnce;

            private Gate(final CountDownLatch entered, final CountDownLatch release) {
                this.entered = entered;
                this.release = release;
            }

            @Override
            public String id() {
                return "gate";
            }

            @Override
            public List<Finding> detect(final StreamFacts facts) {
                if (throwsOnce && calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("the detector failed mid-run");
                }
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the gate");
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return List.of();
            }
        }

        /** The real pipeline over the real temp database, with only the detectors swapped. */
        private IndexService gated(final Gate gate) {
            final ObjectMapper mapper = new ObjectMapper();
            return new IndexService(new CorpusScanner(),
                    new SessionIngestor(mapper, new ShellAnalyzer(mapper)),
                    List.of(gate), writer, propertiesOf(corpus));
        }

        @Test
        void aSecondRunWhileOneIsInFlightIsRefusedRatherThanInterleaved() throws Exception {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final IndexService single = gated(new Gate(entered, release));
            final AtomicReference<IndexSummary> finished = new AtomicReference<>();
            final AtomicReference<Throwable> failed = new AtomicReference<>();
            final Thread running = new Thread(() -> {
                try {
                    finished.set(single.run(corpus, "test-version"));
                } catch (final RuntimeException ex) {
                    failed.set(ex);
                }
            }, "index-in-flight");

            running.start();
            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("the run is inside the pipeline, so it holds the lock")
                    .isTrue();

            assertThatThrownBy(() -> single.run(corpus, "test-version"))
                    .isInstanceOf(IndexAlreadyRunningException.class);

            release.countDown();
            running.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(running.isAlive()).as("the gate let the run finish").isFalse();
            assertThat(failed.get()).isNull();
            assertThat(finished.get().streams()).isEqualTo(2);

            // The lock came back with the run. A guard that leaks after one success bricks the
            // only mutating endpoint this application has.
            assertThat(single.run(corpus, "test-version").streams()).isEqualTo(2);
        }

        @Test
        void twoRunsAskedForInTheSameMomentProduceOneRunAndOneRefusal() throws Exception {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch refusedOnce = new CountDownLatch(1);
            final IndexService single = gated(new Gate(entered, release));
            final CyclicBarrier bothAsk = new CyclicBarrier(2);
            final AtomicInteger completed = new AtomicInteger();
            final AtomicInteger refused = new AtomicInteger();
            final CountDownLatch bothSettled = new CountDownLatch(2);
            final Runnable ask = () -> {
                awaitQuietly(bothAsk);
                try {
                    single.run(corpus, "test-version");
                    completed.incrementAndGet();
                } catch (final IndexAlreadyRunningException ex) {
                    refused.incrementAndGet();
                    refusedOnce.countDown();
                } finally {
                    bothSettled.countDown();
                }
            };
            final Thread a = new Thread(ask, "ask-a");
            final Thread b = new Thread(ask, "ask-b");
            a.start();
            b.start();

            // The order that matters is established, not assumed: one run is inside the pipeline
            // and the other has already been turned away, while the first is still holding on.
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(refusedOnce.await(10, TimeUnit.SECONDS))
                    .as("the loser was refused while the winner still holds the lock")
                    .isTrue();

            release.countDown();
            assertThat(bothSettled.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.get()).as("exactly one run happened").isEqualTo(1);
            assertThat(refused.get()).as("exactly one was refused").isEqualTo(1);
        }

        /**
         * The window the frontend cannot protect: the embedded server is already accepting
         * requests while {@code ApplicationRunner} is still indexing, so a manual run can arrive
         * mid-startup. The database is still empty at that moment — the gate is holding the run
         * on the first stream, before its rows are written — so the startup path really does
         * reach {@code run} rather than short-circuiting on a populated index.
         */
        @Test
        void aManualRunAskedForWhileStartupIsStillIndexingIsRefused() throws Exception {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final IndexService single = gated(new Gate(entered, release));
            final AtomicReference<Throwable> startupFailed = new AtomicReference<>();
            final Thread startup = new Thread(() -> {
                try {
                    new StartupIndexRunner(single, propertiesOf(corpus), writer)
                            .run(new DefaultApplicationArguments());
                } catch (final RuntimeException ex) {
                    startupFailed.set(ex);
                }
            }, "startup-index-in-flight");

            startup.start();
            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("startup indexing is inside the pipeline")
                    .isTrue();

            assertThatThrownBy(() -> single.run(corpus, "test-version"))
                    .isInstanceOf(IndexAlreadyRunningException.class);
            assertThat(writer.countSessions())
                    .as("the refused request wrote nothing on its way out")
                    .isZero();

            release.countDown();
            startup.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(startupFailed.get()).isNull();
            assertThat(writer.countSessions()).isEqualTo(2);
        }

        /**
         * The other direction, which is the one that could have made this guard worse than the
         * bug it fixes: the embedded server is listening before {@code ApplicationRunner} runs,
         * so a request can reach the lock first. The runner must then step aside — throwing there
         * would abort the very boot the request was waiting for, and the run that won is indexing
         * the same configured corpus.
         */
        @Test
        void startupStepsAsideRatherThanFailingWhenARunGotThereFirst() throws Exception {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final IndexService single = gated(new Gate(entered, release));
            final Thread manual = new Thread(() -> single.run(corpus, "test-version"), "manual-run-first");
            manual.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            // No exception, and no second run: the runner warns and returns.
            new StartupIndexRunner(single, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments());
            assertThat(logMessages(Level.WARN))
                    .anyMatch(msg -> msg.contains("already in progress while startup"));
            assertThat(writer.countSessions())
                    .as("startup started no competing rebuild")
                    .isZero();

            release.countDown();
            manual.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(writer.countSessions()).isEqualTo(2);
        }

        /** A run that dies partway must not leave the lock held by a thread that no longer exists. */
        @Test
        void aRunThatThrowsHandsTheLockBack() {
            final CountDownLatch entered = new CountDownLatch(1);
            // An open gate: this case is about the second run getting in at all, not about
            // holding it open. Getting to the gate is already proof the lock was free.
            final CountDownLatch release = new CountDownLatch(0);
            final Gate gate = new Gate(entered, release);
            gate.throwsOnce = true;
            final IndexService single = gated(gate);

            assertThatThrownBy(() -> single.run(corpus, "test-version"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the detector failed mid-run");

            gate.throwsOnce = false;
            assertThat(single.run(corpus, "test-version").streams())
                    .as("the next run is answered, not refused")
                    .isEqualTo(2);
        }

        private static void awaitQuietly(final CyclicBarrier barrier) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (final Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Nested
    final class StartupRunner {

        @Test
        void anEmptyDatabaseWithACorpusIsIndexedOnStartup() {
            new StartupIndexRunner(service, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments());

            assertThat(writer.countSessions()).isEqualTo(2);
            // two rows: the schema version, and which corpus the rows came from
            assertThat(jdbc.queryForList("select key, value from meta"))
                    .containsExactlyInAnyOrder(
                            Map.of("key", "schema_version", "value", IndexService.SCHEMA_VERSION),
                            Map.of("key", "corpus", "value", corpus.toAbsolutePath()
                                    .normalize().toString()));
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
        void aStaleDatabaseIsResetAndReindexedOnStartup() {
            // The first boot on a database an older build left behind: the reset must leave
            // the database usable, not merely empty — the startup rule then indexes the
            // corpus and the row counts come back.
            service.run();
            writer.seedMeta("0");
            logAppender.list.clear();

            new StartupIndexRunner(service, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments());

            assertThat(writer.countSessions()).isEqualTo(2);
            assertThat(count("step")).isEqualTo(2);
            assertThat(count("tool_call")).isEqualTo(4);
            assertThat(count("finding")).isEqualTo(3);
            assertThat(jdbc.queryForList("select key, value from meta"))
                    .containsExactlyInAnyOrder(
                            Map.of("key", "schema_version", "value", IndexService.SCHEMA_VERSION),
                            Map.of("key", "corpus", "value", corpus.toAbsolutePath()
                                    .normalize().toString()));
            // the reset is logged with counts only: how many streams were discarded, never
            // a corpus path or content
            final List<String> info = logMessages(Level.INFO);
            assertThat(info).anyMatch(msg -> msg.contains("discarding 2 streams"));
            assertThat(info).noneMatch(msg -> msg.contains(corpus.toString()));
        }

        @Test
        void aPopulatedDatabaseIsNeverRescannedEvenWhenTheCorpusIsMissing() {
            service.run();
            logAppender.list.clear();

            new StartupIndexRunner(service, propertiesOf(temp.resolve("nowhere")), writer)
                    .run(new DefaultApplicationArguments());

            // Never rescanned: the counts are exactly what the first run wrote. The warning is
            // now expected and is the point — a corpus path that does not resolve means the
            // screen is showing an index this run cannot refresh, and silence used to hide that.
            assertThat(logMessages(Level.WARN))
                    .anyMatch(msg -> msg.contains("corpus directory does not exist"));
            assertThat(logMessages(Level.INFO)).noneMatch(msg -> msg.startsWith("indexed "));
            assertThat(writer.countSessions()).isEqualTo(2);
            // and the index survives: an unmounted directory invalidates nothing
            assertThat(count("finding")).isEqualTo(3);
        }

        @Test
        void aDatabaseBuiltFromAnotherCorpusIsReindexedRatherThanServed() throws IOException {
            // The defect as a user met it: run.sh demo, then run.sh live, sharing one database
            // file. "Sessions present" read as "already indexed", so the second boot served the
            // first corpus's findings and the dashboard quietly described data it had not read.
            new StartupIndexRunner(service, propertiesOf(corpus), writer)
                    .run(new DefaultApplicationArguments());
            assertThat(writer.countSessions()).isEqualTo(2);

            final Path other = temp.resolve("other-corpus");
            writeS1(other);
            logAppender.list.clear();

            new StartupIndexRunner(service, propertiesOf(other), writer)
                    .run(new DefaultApplicationArguments());

            assertThat(writer.countSessions())
                    .as("the configured corpus's own streams, not the previous corpus's")
                    .isEqualTo(1);
            assertThat(count("finding")).as("the new corpus's findings, not the previous corpus's 3")
                    .isEqualTo(2);
            final List<String> info = logMessages(Level.INFO);
            assertThat(info).anyMatch(msg -> msg.contains("different corpus"));
            assertThat(info).anyMatch(msg -> msg.startsWith("indexed 1 stream"));
            // counts only: a corpus path carries the username
            assertThat(info).noneMatch(msg -> msg.contains(other.toString()));
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
