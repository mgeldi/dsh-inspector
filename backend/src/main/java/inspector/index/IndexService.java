package inspector.index;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.Finding;
import inspector.ingest.CorpusScanner;
import inspector.ingest.SessionIngestor;
import inspector.ingest.SessionSource;
import inspector.ingest.StreamFacts;
import inspector.store.IndexWriter;
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
 */
@Service
public final class IndexService {

    private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);
    /** The index schema the running build writes; the startup reset compares it against the stored row. */
    public static final String SCHEMA_VERSION = "1";

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

        for (final SessionSource source : sources) {
            final StreamFacts facts = ingestor.ingest(source);
            final List<Finding> produced = new ArrayList<>();
            for (final Detector detector : detectors) {
                produced.addAll(detector.detect(facts));
            }
            final Written written = writer.writeStream(source, facts, produced, harnessVersion,
                    properties.evidence().store(), indexedAt);
            steps += written.steps();
            calls += written.toolCalls();
            findings += written.findings();
            parseFailures += facts.parseFailures();
        }
        writer.seedMeta(SCHEMA_VERSION);

        final IndexSummary summary = new IndexSummary(sources.size(), writer.countSessions(),
                steps, calls, findings, parseFailures, System.currentTimeMillis() - started);
        LOG.info("indexed {} streams, {} findings in {} ms ({} parse failures)",
                summary.streams(), summary.findings(), summary.durationMs(), summary.parseFailures());
        return summary;
    }
}
