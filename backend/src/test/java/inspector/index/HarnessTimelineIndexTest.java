package inspector.index;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.TestPipeline;
import inspector.TestStore;
import inspector.config.InspectorProperties;
import inspector.config.InspectorProperties.HarnessRelease;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The timeline reaching the index: every session gets the version that was live when it started,
 * which is what turns a cohort by harness version into "before and after that change".
 */
final class HarnessTimelineIndexTest {

    /** Midway through the fixture corpus, which spans 2026-09-01 to 2026-09-13. */
    private static final Instant CHANGE = Instant.parse("2026-09-06T00:00:00Z");

    @TempDir
    Path temp;

    @Test
    void eachSessionIsAttributedTheVersionLiveAtItsStart() {
        try (TestStore store = TestStore.open(temp.resolve("timeline.sqlite"))) {
            final InspectorProperties properties = new InspectorProperties("fixtures/sessions", "unknown",
                    new InspectorProperties.Evidence(true), List.of(new HarnessRelease(CHANGE, "agents-v2")));
            TestPipeline.indexService(store, properties).run(Path.of("fixtures/sessions"), "agents-v1");

            final long before = store.jdbc().queryForObject(
                    "select count(*) from session where started_at < ?", Long.class, CHANGE.toEpochMilli());
            final long after = store.jdbc().queryForObject(
                    "select count(*) from session where started_at >= ?", Long.class, CHANGE.toEpochMilli());
            assertThat(before).as("the fixture has sessions on both sides").isPositive();
            assertThat(after).isPositive();

            // before the first entry: the version the run was handed; from it on: the entry's
            assertThat(store.jdbc().queryForObject("select count(*) from session where started_at < ?"
                    + " and harness_version = 'agents-v1'", Long.class, CHANGE.toEpochMilli())).isEqualTo(before);
            assertThat(store.jdbc().queryForObject("select count(*) from session where started_at >= ?"
                    + " and harness_version = 'agents-v2'", Long.class, CHANGE.toEpochMilli())).isEqualTo(after);
            // still an attribution, not a declaration
            assertThat(store.jdbc().queryForObject("select min(version_inferred) from session", Integer.class))
                    .isEqualTo(1);
        }
    }
}
