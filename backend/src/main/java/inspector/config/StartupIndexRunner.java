package inspector.config;

import inspector.index.IndexService;
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
 * operator did not ask for, and an empty corpus must not look like a crash.
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
        // The database gate comes first: a populated index is never rescanned, whatever the
        // corpus directory looks like.
        if (args.containsOption("no-index") || writer.countSessions() > 0) {
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
        indexService.run();
    }
}
