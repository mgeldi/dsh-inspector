package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import inspector.ingest.ErrorEvent;
import inspector.ingest.SessionRecord;
import inspector.ingest.StreamFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ErrorPlaneDetectorTest {

    private final ErrorPlaneDetector detector = new ErrorPlaneDetector();

    @Test
    void everyErrorBecomesOneFindingWithItsPlaneAndNullConfidence() {
        final List<Finding> findings = detector.detect(facts(
                error(101, "edit", "FS_NOT_FOUND", "/home/dev/demo/missing.java"),
                error(102, "bash", "INVALID_ARGS", null),
                error(103, "web", "WEB_PROVIDER_CREDENTIAL_MISSING", null)));

        assertThat(findings).hasSize(3);
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.detector()).isEqualTo(ErrorPlaneDetector.ID);
            assertThat(f.category()).isNull();
            assertThat(f.confidence()).isNull();
            assertThat(f.staleSeq()).isNull();
            assertThat(f.causeSeq()).isNull();
            assertThat(f.evidence()).isEmpty();
        });
        assertThat(findings)
                .extracting(Finding::code, Finding::plane, Finding::seq, Finding::absolutePath)
                .containsExactly(
                        tuple("FS_NOT_FOUND", Plane.MODEL_MISUSE, 101, "/home/dev/demo/missing.java"),
                        tuple("INVALID_ARGS", Plane.MODEL_MISUSE, 102, null),
                        tuple("WEB_PROVIDER_CREDENTIAL_MISSING", Plane.INFRASTRUCTURE, 103, null));
    }

    @Test
    void theSummaryIsBuiltFromToolAndCodeNeverFromUserText() {
        final List<Finding> findings = detector.detect(facts(
                error(101, "edit", "FS_NOT_FOUND", "/home/dev/demo/missing.java"),
                error(102, null, "INVALID_ARGS", null)));

        assertThat(findings.get(0).summary()).isEqualTo("edit returned FS_NOT_FOUND");
        assertThat(findings.get(1).summary()).isEqualTo("tool returned INVALID_ARGS");
    }

    private StreamFacts facts(final ErrorEvent... errors) {
        return new StreamFacts(session(), List.of(), List.of(), List.of(), List.of(),
                List.of(errors), List.of(), List.of(), 0L);
    }

    private ErrorEvent error(final int seq, final String tool, final String code,
                             final String path) {
        return new ErrorEvent(seq, 0, 0, tool, code, path);
    }

    private SessionRecord session() {
        return new SessionRecord("s-demo", "session.jsonl.zstd", "demo-project", "V0",
                1L, null, null, null, null, null, 0, "/home/dev/demo");
    }
}
