package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.ingest.RetryEvent;
import inspector.ingest.SessionRecord;
import inspector.ingest.StreamFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RetryStormDetectorTest {

    private static final long T0 = 1_760_000_000_000L;

    private final RetryStormDetector detector = new RetryStormDetector();

    @Test
    void aStepThatExhaustedItsBudgetIsOneFindingNotOnePerRetry() {
        final List<Finding> findings = detector.detect(facts(
                new RetryEvent(10, 0, 0, "TIMEOUT", T0 + 10),
                new RetryEvent(11, 0, 0, "TIMEOUT", T0 + 11),
                new RetryEvent(12, 0, 0, "SERVER", T0 + 12)));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.detector()).isEqualTo(RetryStormDetector.ID);
            assertThat(f.plane()).isEqualTo(Plane.INFRASTRUCTURE);
            assertThat(f.category()).isNull();
            assertThat(f.code()).isEqualTo("SERVER");          // the last retry's code
            assertThat(f.seq()).isEqualTo(12);
            assertThat(f.confidence()).isNull();
            assertThat(f.summary()).isEqualTo("step exhausted its retry budget (3 retries, last SERVER)");
        });
    }

    @Test
    void threeRetriesSpreadAcrossThreeStepsAreNotAStorm() {
        final List<Finding> findings = detector.detect(facts(
                new RetryEvent(10, 0, 0, "TIMEOUT", T0 + 10),
                new RetryEvent(11, 0, 1, "TIMEOUT", T0 + 11),
                new RetryEvent(12, 0, 2, "SERVER", T0 + 12)));

        assertThat(findings).isEmpty();
    }

    @Test
    void twoRetriesIsTheThreshold() {
        final List<Finding> findings = detector.detect(facts(
                new RetryEvent(10, 1, 2, "TRANSPORT", T0 + 10),
                new RetryEvent(11, 1, 2, "SERVER", T0 + 11)));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("SERVER");
            assertThat(f.summary()).isEqualTo("step exhausted its retry budget (2 retries, last SERVER)");
        });
    }

    @Test
    void theFindingCarriesTheLastRetryEventTime() {
        final List<Finding> findings = detector.detect(facts(
                new RetryEvent(10, 0, 0, "TIMEOUT", T0 + 10),
                new RetryEvent(11, 0, 0, "SERVER", T0 + 999)));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.occurredAt()).isEqualTo(T0 + 999));
    }

    private StreamFacts facts(final RetryEvent... retries) {
        return new StreamFacts(session(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(retries), 0L);
    }

    private SessionRecord session() {
        return new SessionRecord("s-demo", "session.jsonl.zstd", "demo-project", "V0",
                1L, null, null, null, null, null, null, 0, "/home/dev/demo");
    }
}
