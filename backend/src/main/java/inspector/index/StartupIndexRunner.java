package inspector.index;

import inspector.config.InspectorProperties;
import inspector.store.IndexWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Indexes on startup when the database holds nothing and the configured corpus has files.
 * Conditional rather than unconditional: a second boot must not pay for a rescan the
 * operator did not ask for, and an empty corpus must not look like a crash. Before the rule
 * runs, a stale schema version resets the index — the database is a derived cache of the
 * corpus, not a system of record — so a schema bump never meets a half-obsolete table.
 */
@Component
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
            // The whole decide-and-rebuild sequence holds the run guard: the resets are as
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
        // A schema bump is an invalidation, not a migration: the reset runs whether or not
        // this boot goes on to index, because a stale index is broken either way.
        writer.resetIfStale(IndexService.SCHEMA_VERSION);
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
            // A populated index of the corpus now configured is never rescanned.
            return;
        }
        // The corpus this method just validated is the one handed over, rather than a
        // no-arg run() re-reading the property: the check and the work cannot then drift
        // apart over which directory is authoritative.
        indexService.run(corpus, properties.harnessVersion());
    }
}
