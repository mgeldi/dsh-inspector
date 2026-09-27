package inspector.api;

import inspector.TestPipeline;
import inspector.TestStore;
import java.nio.file.Path;

/**
 * Shared fixture indexing for the API tests. The pipeline is hand-constructed
 * ({@link TestPipeline}, {@link TestStore}): a temp-dir SQLite file, the committed
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
        try (TestStore store = TestStore.open(dbFile)) {
            TestPipeline.indexService(store, TestPipeline.properties(corpus.toString(), harnessVersion, storeEvidence))
                    .run(corpus, harnessVersion);
        }
    }
}
