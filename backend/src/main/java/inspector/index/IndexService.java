package inspector.index;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.ErrorPlanes;
import inspector.detect.Finding;
import inspector.detect.Plane;
import inspector.ingest.CorpusScanner;
import inspector.ingest.ErrorEvent;
import inspector.ingest.SessionIngestor;
import inspector.ingest.SessionSource;
import inspector.ingest.StreamFacts;
import inspector.store.IndexWriter;
import inspector.store.IndexWriter.StreamKey;
import inspector.store.IndexWriter.Written;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scan → ingest → detect → write, one corpus at a time. The corpus and the fallback harness
 * version are parameters, not configuration reads, so two runs over two corpora — two version
 * strings, two cohorts — need no test-only seam in production code. Where the configured harness
 * timeline covers a session's start, the timeline's version wins over the fallback.
 *
 * <p>Ingest and detection are pure per stream — decompress, parse, derive, never touch the
 * database — so they run on a small pool, while writes stay on the calling thread in scan order:
 * SQLite has one writer, and the index a parallel run produces is the same, id for id, as a
 * serial one. At most {@link #IN_FLIGHT} streams are held in memory at a time.
 *
 * <p>A run makes the index <em>equal</em> to the corpus rather than merging into it: after the
 * last stream is written, {@link IndexWriter#pruneToWritten} discards the streams the scan did
 * not reach. The database is a derived cache of the corpus, so a session that is no longer in
 * the corpus must not stay on screen describing itself.
 */
@Service
public final class IndexService {

    private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);
    /**
     * The version of what an index run derives — ingest's parsing and every detector's rules. It is
     * not the schema version: a detector that classifies differently changes no table, so a schema
     * bump would throw away a good file for nothing, while leaving an index alone after such a
     * change serves findings the running build would not produce. Bump it with any change to what
     * a run writes into the same columns; a boot on an index built at another value re-indexes.
     * 5.1: shell-edit's per-form write targets and the partial-read category. (The judge's
     * dispersion is computed when a request is answered, not written, so it needs no bump.)
     */
    public static final String ANALYSIS_VERSION = "5.1";

    /** Ingest threads. Decompression and JSON parsing are CPU-bound; more threads than cores buys nothing. */
    private static final int WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    /** Streams analysed ahead of the writer. Bounds memory to a few streams' facts, not the corpus's. */
    private static final int IN_FLIGHT = WORKERS * 2;

    private final CorpusScanner scanner;
    private final SessionIngestor ingestor;
    private final List<Detector> detectors;
    private final IndexWriter writer;
    private final InspectorProperties properties;
    /**
     * Held for the whole of one run, not one stream. One lock instance in one service instance
     * is the whole guard because the application is one process over one SQLite file — which is
     * what DESIGN.md §9 commits to; two processes would need the lock to live in the database.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    public IndexService(final CorpusScanner scanner, final SessionIngestor ingestor,
                        final List<Detector> detectors, final IndexWriter writer,
                        final InspectorProperties properties) {
        this.scanner = scanner;
        this.ingestor = ingestor;
        this.detectors = detectors;
        this.writer = writer;
        this.properties = properties;
    }

    public IndexSummary run() {
        return run(Path.of(properties.corpus()), properties.harnessVersion());
    }

    /**
     * One run at a time. The lock is taken with {@code tryLock} and never waited on: a caller
     * that arrives while a run is going on is refused, because a queued second run would rebuild
     * the same corpus again for no reason — and between the refusal and the queue it would have
     * sat behind, the first run's prune has already made the index equal to the corpus.
     *
     * <p>{@link StartupIndexRunner} indexes through this method, so a manual
     * request that arrives while startup is still indexing is refused the same way rather than
     * allowed to interleave with a run the operator never asked for. (The embedded server is
     * already listening while {@code ApplicationRunner}s run — that is what makes the window
     * real rather than theoretical.)
     *
     * @throws IndexAlreadyRunningException if another run holds the lock
     */
    public IndexSummary run(final Path corpus, final String fallbackHarnessVersion) {
        if (!runLock.tryLock()) {
            throw new IndexAlreadyRunningException();
        }
        try {
            return index(corpus, fallbackHarnessVersion);
        } finally {
            runLock.unlock();
        }
    }

    /**
     * Hold the same single-flight guard across a sequence of steps, so they cannot interleave
     * with a run either. Only startup needs it: a reset is as destructive as a rebuild, and a
     * guard that covers the rebuild but not the reset leaves a window in which a boot wipes the
     * tables a run is filling.
     *
     * <p>Re-entrance is the point of the {@link ReentrantLock}: startup calls
     * {@link #run(Path, String)} from inside {@code body}, and the same thread's second
     * {@code tryLock} counts a nested hold rather than refusing it. A different thread is still
     * refused, which is the whole contract.
     *
     * @throws IndexAlreadyRunningException if a run holds the guard on another thread
     */
    public void underRunLock(final Runnable body) {
        if (!runLock.tryLock()) {
            throw new IndexAlreadyRunningException();
        }
        try {
            body.run();
        } finally {
            runLock.unlock();
        }
    }

    private IndexSummary index(final Path corpus, final String fallbackHarnessVersion) {
        final long started = System.currentTimeMillis();
        final List<SessionSource> sources = scanner.scan(corpus);
        if (sources.isEmpty()) {
            // A corpus directory that exists but holds no session files (a lock file is not
            // one) would otherwise look like a crash, and an empty dashboard without this
            // line has no explanation to point at. No path in the message: a configured
            // corpus path carries the username.
            LOG.warn("corpus scan returned no session streams; the dashboard will be empty"
                    + " until it contains session logs");
        }
        int steps = 0;
        int calls = 0;
        int findings = 0;
        int evidenceRows = 0;
        long parseFailures = 0;
        final long indexedAt = System.currentTimeMillis();
        final List<StreamKey> written = new ArrayList<>();
        final Set<String> unmappedCodes = new HashSet<>();

        final ExecutorService pool = Executors.newFixedThreadPool(WORKERS, runnable -> {
            final Thread thread = new Thread(runnable, "index-ingest");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final Deque<Future<Analysed>> inFlight = new ArrayDeque<>();
            int next = 0;
            while (next < sources.size() || !inFlight.isEmpty()) {
                while (next < sources.size() && inFlight.size() < IN_FLIGHT) {
                    final SessionSource source = sources.get(next++);
                    inFlight.add(pool.submit(() -> analyse(source)));
                }
                final Analysed analysed = await(inFlight.removeFirst());
                final StreamFacts facts = analysed.facts();
                final Written writtenRows = writer.writeStream(analysed.source(), facts, analysed.findings(),
                        properties.harnessVersionAt(facts.session().startedAt(), fallbackHarnessVersion),
                        properties.evidence().store(), indexedAt);
                steps += writtenRows.steps();
                calls += writtenRows.toolCalls();
                findings += writtenRows.findings();
                evidenceRows += writtenRows.evidenceRows();
                parseFailures += facts.parseFailures();
                written.add(new StreamKey(facts.session().id(), facts.session().sourceFile()));
                // The only check that has real data in front of it. ErrorPlanes maps a code to a
                // plane and falls back to INFRASTRUCTURE for anything it does not know, which is
                // the right default — a code nobody classified is the operator's to look at — but
                // it is silent, and a silent default put a model-misuse code on the operator's
                // plane in the headline chart for a whole corpus. This sees what the harness
                // actually emitted.
                for (final ErrorEvent error : facts.errors()) {
                    if (error.code() != null && ErrorPlanes.lookup(error.code()).isEmpty()) {
                        unmappedCodes.add(error.code());
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (!unmappedCodes.isEmpty()) {
            // Codes only — they are harness constants, not content, and naming them is the
            // whole point: a count alone would say something is wrong without saying what.
            LOG.warn("{} error code(s) are not in the plane map and defaulted to {}: {}."
                            + " Classify them in ErrorPlanes or the plane mix understates"
                            + " whichever plane they belong to",
                    unmappedCodes.size(), Plane.INFRASTRUCTURE, new TreeSet<>(unmappedCodes));
        }
        // Then, once for the whole run: whatever the scan did not reach is no longer in the
        // corpus and has to leave the index with it. Per stream this cannot be seen at all —
        // writeStream only ever deletes the stream it is about to write.
        final int pruned = writer.pruneToWritten(corpus, written);
        writer.seedMeta(IndexWriter.SCHEMA_VERSION);
        // What this index came from, so a later boot pointing at a different corpus can tell.
        writer.seedCorpus(corpus.toAbsolutePath().normalize().toString());
        writer.seedHarnessTimeline(properties.timelineFingerprint(fallbackHarnessVersion));
        writer.seedAnalysisVersion(ANALYSIS_VERSION);

        final IndexSummary summary = new IndexSummary(sources.size(), writer.countSessions(),
                steps, calls, findings, evidenceRows, pruned, parseFailures,
                System.currentTimeMillis() - started);
        LOG.info("indexed {} streams, {} findings ({} evidence rows) in {} ms"
                        + " ({} parse failures, {} pruned)",
                summary.streams(), summary.findings(), summary.evidenceRows(),
                summary.durationMs(), summary.parseFailures(), summary.pruned());
        return summary;
    }

    private record Analysed(SessionSource source, StreamFacts facts, List<Finding> findings) {
    }

    /** One stream's pure half: ingest and every detector. Safe on any thread — it touches no shared state. */
    private Analysed analyse(final SessionSource source) {
        final StreamFacts facts = ingestor.ingest(source);
        final List<Finding> produced = new ArrayList<>();
        for (final Detector detector : detectors) {
            produced.addAll(detector.detect(facts));
        }
        return new Analysed(source, facts, produced);
    }

    private static Analysed await(final Future<Analysed> future) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("index run interrupted", e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("ingest failed", e.getCause());
        }
    }
}
