package inspector.index;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.Finding;
import inspector.ingest.CorpusScanner;
import inspector.ingest.SessionIngestor;
import inspector.ingest.SessionSource;
import inspector.ingest.StreamFacts;
import inspector.store.IndexWriter;
import inspector.store.IndexWriter.StreamKey;
import inspector.store.IndexWriter.Written;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scan → ingest → detect → write, one corpus at a time. The corpus and the harness version
 * are parameters, not configuration reads, so two runs over two corpora — two version
 * strings, two cohorts — need no test-only seam in production code.
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
     * The index schema the running build writes; the startup reset compares it against the
     * stored row. Bumped to 2 by the join-key indexes: the DDL gained a DROP, and a reset only
     * empties tables, so an index retired since the last boot needs a wipe to actually go.
     */
    public static final String SCHEMA_VERSION = "2";

    private final CorpusScanner scanner;
    private final SessionIngestor ingestor;
    private final List<Detector> detectors;
    private final IndexWriter writer;
    private final InspectorProperties properties;

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

    public IndexSummary run(final Path corpus, final String harnessVersion) {
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
        long parseFailures = 0;
        final long indexedAt = System.currentTimeMillis();
        final List<StreamKey> written = new ArrayList<>();

        for (final SessionSource source : sources) {
            final StreamFacts facts = ingestor.ingest(source);
            final List<Finding> produced = new ArrayList<>();
            for (final Detector detector : detectors) {
                produced.addAll(detector.detect(facts));
            }
            final Written writtenRows = writer.writeStream(source, facts, produced, harnessVersion,
                    properties.evidence().store(), indexedAt);
            steps += writtenRows.steps();
            calls += writtenRows.toolCalls();
            findings += writtenRows.findings();
            parseFailures += facts.parseFailures();
            written.add(new StreamKey(facts.session().id(), facts.session().sourceFile()));
        }
        // Then, once for the whole run: whatever the scan did not reach is no longer in the
        // corpus and has to leave the index with it. Per stream this cannot be seen at all —
        // writeStream only ever deletes the stream it is about to write.
        final int pruned = writer.pruneToWritten(corpus, written);
        writer.seedMeta(SCHEMA_VERSION);
        // What this index came from, so a later boot pointing at a different corpus can tell.
        writer.seedCorpus(corpus.toAbsolutePath().normalize().toString());

        final IndexSummary summary = new IndexSummary(sources.size(), writer.countSessions(),
                steps, calls, findings, pruned, parseFailures, System.currentTimeMillis() - started);
        LOG.info("indexed {} streams, {} findings in {} ms ({} parse failures, {} pruned)",
                summary.streams(), summary.findings(), summary.durationMs(), summary.parseFailures(),
                summary.pruned());
        return summary;
    }
}
