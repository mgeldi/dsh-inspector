package inspector.api;

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

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture indexing for the API tests. The pipeline is hand-constructed
 * exactly like {@code FixtureCorpusTest}: a temp-dir SQLite file, the committed
 * fixture corpora, no Spring context doing the indexing. Every test class owns
 * its own {@code @TempDir} database; nothing ever writes inside the repo.
 *
 * <p>The two corpora stand in for two harness versions so the cohorts route
 * has two rows to compare.
 */
final class IndexedCorpus {

    static final String MAIN_VERSION = "0.1.5-rc.2";
    static final String SECOND_VERSION = "0.1.4";

    private IndexedCorpus() {
    }

    /** Both committed corpora: sessions/ at 0.1.5-rc.2, sessions-b/ at 0.1.4. */
    static void indexBoth(final Path dbFile) {
        index(dbFile, Path.of("fixtures/sessions"), MAIN_VERSION);
        index(dbFile, Path.of("fixtures/sessions-b"), SECOND_VERSION);
    }

    static void index(final Path dbFile, final Path corpus, final String harnessVersion) {
        index(dbFile, corpus, harnessVersion, true);
    }

    /**
     * Index the given corpus directory into the given SQLite file.
     *
     * @param storeEvidence whether {@code shell_evidence} rows are written
     */
    static void index(
            final Path dbFile, final Path corpus, final String harnessVersion, final boolean storeEvidence) {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            final String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final String statement : ddl.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        final ObjectMapper mapper = new ObjectMapper();
        final SessionIngestor ingestor = new SessionIngestor(mapper, new ShellAnalyzer(mapper));
        final StampGuardDetector stamp = new StampGuardDetector();
        final List<Detector> detectors = List.of(stamp, new ErrorPlaneDetector(stamp),
                new FatalTurnDetector(), new RetryStormDetector());
        final IndexWriter writer = new IndexWriter(jdbc, new DataSourceTransactionManager(dataSource));
        final IndexService service = new IndexService(new CorpusScanner(), ingestor, detectors, writer,
                new InspectorProperties(corpus.toString(), harnessVersion,
                        new InspectorProperties.Evidence(storeEvidence)));
        service.run(corpus, harnessVersion);
    }
}
