package inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import inspector.config.InspectorProperties.HarnessRelease;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which harness version a session ran under, from the operator's timeline. The session log does
 * not record it, so this is the only way a cohort by version can mean "before and after".
 */
final class HarnessTimelineTest {

    private static final Instant A = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant B = Instant.parse("2026-09-14T12:00:00Z");

    private static InspectorProperties withTimeline(final List<HarnessRelease> timeline) {
        return new InspectorProperties("fixtures/sessions", "unknown",
                new InspectorProperties.Evidence(true), timeline);
    }

    @Test
    void aSessionGetsTheLastEntryNotAfterItsStart() {
        final InspectorProperties properties = withTimeline(List.of(
                new HarnessRelease(A, "agents-v1"), new HarnessRelease(B, "agents-v2")));

        assertThat(properties.harnessVersionAt(A.toEpochMilli() - 1, "unknown")).isEqualTo("unknown");
        assertThat(properties.harnessVersionAt(A.toEpochMilli(), "unknown"))
                .as("since is inclusive").isEqualTo("agents-v1");
        assertThat(properties.harnessVersionAt(B.toEpochMilli() - 1, "unknown")).isEqualTo("agents-v1");
        assertThat(properties.harnessVersionAt(B.toEpochMilli() + 1, "unknown")).isEqualTo("agents-v2");
    }

    @Test
    void theEntriesNeedNotBeWrittenInOrder() {
        final InspectorProperties properties = withTimeline(List.of(
                new HarnessRelease(B, "agents-v2"), new HarnessRelease(A, "agents-v1")));

        assertThat(properties.harnessTimeline()).extracting(HarnessRelease::version)
                .containsExactly("agents-v1", "agents-v2");
        assertThat(properties.harnessVersionAt(B.toEpochMilli() - 1, "unknown")).isEqualTo("agents-v1");
    }

    @Test
    void withoutATimelineEverySessionGetsTheFallback() {
        final InspectorProperties properties = withTimeline(null);

        assertThat(properties.harnessTimeline()).isEmpty();
        assertThat(properties.harnessVersionAt(B.toEpochMilli(), "run-given")).isEqualTo("run-given");
    }

    @Test
    void anEntryWithoutAnInstantOrAVersionIsRefused() {
        assertThatThrownBy(() -> new HarnessRelease(null, "v"))
                .hasMessageContaining("'since'");
        assertThatThrownBy(() -> new HarnessRelease(A, " "))
                .hasMessageContaining("non-blank 'version'");
    }
}
