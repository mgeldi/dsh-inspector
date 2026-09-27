package inspector.index;

import inspector.config.InspectorProperties;
import inspector.store.IndexWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Indexes on startup when the database holds nothing and the configured corpus has files.
 * Conditional rather than unconditional: a second boot must not pay for a rescan the
 * operator did not ask for, and an empty corpus must not look like a crash. A stale schema
 * version has already been dealt with by the time this runs: {@code SchemaGate} rebuilt it while
 * the context was starting, before anything could read a table.
 */
@Component
@Order(0)
public final class StartupIndexRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupIndexRunner.class);

    private final IndexService indexService;
    private final InspectorProperties properties;
    private final IndexWriter writer;

    public StartupIndexRunner(final IndexService indexService, final InspectorProperties properties,
                              final IndexWriter writer) {
        this.indexService = indexService;
        this.properties = properties;
        this.writer = writer;
    }

    @Override
    public void run(final ApplicationArguments args) {
        try {
            // The whole decide-and-rebuild sequence holds the run guard: a corpus reset is as
            // destructive as a run, and the embedded server is already accepting requests while
            // runners execute. Without this, a request that beats the runner to the lock would
            // have its rows reset out from under it a moment later.
            indexService.underRunLock(() -> indexIfWanted(args));
        } catch (final IndexAlreadyRunningException ex) {
            // The other direction: something reached POST /api/index/run before this runner did.
            // Aborting here would destroy the boot that request was aiming at, and the run that
            // won is indexing the same configured corpus this boot would have — so say what
            // happened and come up.
            LOG.warn("an index run was already in progress while startup was deciding whether to"
                    + " index; startup started no second run and the index it is building is left"
                    + " alone");
        }
    }

    private void indexIfWanted(final ApplicationArguments args) {
        if (args.containsOption("no-index")) {
            return;
        }
        final Path corpus = Path.of(properties.corpus());
        if (!Files.isDirectory(corpus)) {
            // No path in the message: a configured corpus path such as ~/.dsh/sessions
            // carries the username.
            LOG.warn("the configured corpus directory does not exist; the dashboard will be"
                    + " empty until --inspector.corpus points at session logs");
            return;
        }
        // Ahead of the populated gate. A database full of findings from a *different* corpus
        // is not "already indexed": the gate used to read it as a finished job and serve
        // 389 live-corpus findings to a run configured for the fixtures, silently.
        writer.resetIfCorpusChanged(corpus);
        if (writer.countSessions() > 0) {
            // A populated index of the corpus now configured is not rescanned on a plain reboot —
            // with two exceptions, both about the index no longer answering what it is asked.
            // --reindex is the caller saying the corpus has moved on (the headless report always
            // passes it: a snapshot of an older index is a snapshot of the wrong thing). And a
            // changed harness timeline means every session's version was attributed from a
            // timeline that no longer exists; the versions are written at index time, so only a
            // run can correct them.
            if (args.containsOption("reindex")) {
                LOG.info("--reindex given; re-indexing the populated index");
            } else if (!IndexService.ANALYSIS_VERSION.equals(writer.storedAnalysisVersion())) {
                LOG.info("this index was built by other analysis rules than this build's ({}); re-indexing",
                        IndexService.ANALYSIS_VERSION);
            } else if (!properties.timelineFingerprint(properties.harnessVersion())
                    .equals(writer.storedHarnessTimeline())) {
                LOG.info("the harness timeline differs from the one this index was built with;"
                        + " re-indexing so every session carries the version it ran under");
            } else {
                return;
            }
        }
        // The corpus this method just validated is the one handed over, rather than a
        // no-arg run() re-reading the property: the check and the work cannot then drift
        // apart over which directory is authoritative.
        indexService.run(corpus, properties.harnessVersion());
    }
}
