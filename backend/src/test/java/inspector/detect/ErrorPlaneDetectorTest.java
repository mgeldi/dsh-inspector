package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import inspector.ingest.ErrorEvent;
import inspector.ingest.FileTouch;
import inspector.ingest.RedactedExcerpt;
import inspector.ingest.SessionRecord;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.VerbClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ErrorPlaneDetectorTest {

    private static final long T0 = 1_760_000_000_000L;

    private final StampGuardDetector stampGuard = new StampGuardDetector();
    private final ErrorPlaneDetector detector = new ErrorPlaneDetector(List.of(stampGuard));

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
    void noSourceErrorProducesTwoFindingsAcrossDetectors() {
        // One owner per source event: StampGuardDetector claims FS_STALE_VERSION, and
        // ErrorPlaneDetector skips exactly the codes another detector claims.
        final StreamFacts facts = new StreamFacts(session(), List.of(), List.of(),
                List.of(new FileTouch(145, "/home/dev/demo/App.java", FileTouch.WRITE)),
                List.of(new ShellEvidence(150, Set.of("/home/dev/demo/App.java"),
                        VerbClass.MUTATING, new RedactedExcerpt("sed -i App.java"))),
                List.of(
                        new ErrorEvent(300, 0, 0, "edit", "FS_STALE_VERSION",
                                "/home/dev/demo/App.java", T0 + 300),
                        new ErrorEvent(301, 0, 0, "edit", "FS_NOT_FOUND",
                                "/home/dev/demo/missing.java", T0 + 301)),
                List.of(), List.of(), 0L);

        final List<Finding> all = new ArrayList<>();
        all.addAll(stampGuard.detect(facts));
        all.addAll(detector.detect(facts));

        assertThat(detector.detect(facts))
                .extracting(Finding::code)
                .doesNotContain("FS_STALE_VERSION");
        assertThat(all).extracting(Finding::code)
                .containsExactlyInAnyOrder("FS_STALE_VERSION", "FS_NOT_FOUND");
        assertThat(all).extracting(Finding::seq).doesNotHaveDuplicates();
    }

    /**
     * {@code ownsToolCodes} is a contract on {@link Detector}, not a quirk of
     * {@code StampGuardDetector}, so the skip set is the union over every other detector. Before
     * this was {@code List<Detector>}, a second owning detector would have been consulted by
     * nobody: its errors would have produced a finding from itself and one from here, and the
     * corpus-level duplicate check would have caught it only if the fixture corpus happened to
     * contain that code.
     */
    @Test
    void everyDetectorThatClaimsACodeIsHonoured() {
        final ErrorPlaneDetector withTwoMoreOwners = new ErrorPlaneDetector(List.of(
                stampGuard, new OwningStub("alpha", Set.of("ALPHA_CODE")),
                new OwningStub("beta", Set.of("BETA_CODE"))));

        final List<Finding> findings = withTwoMoreOwners.detect(facts(
                error(201, "edit", "ALPHA_CODE", null),
                error(202, "edit", "BETA_CODE", null),
                error(203, "edit", "FS_STALE_VERSION", null),
                error(204, "edit", "FS_NOT_FOUND", null)));

        // three codes claimed elsewhere, one left: this detector keeps what nobody owns
        assertThat(findings).extracting(Finding::code).containsExactly("FS_NOT_FOUND");
    }

    /** Two owners claiming the same code is a union, not a conflict. */
    @Test
    void twoDetectorsClaimingTheSameCodeIsStillOneSkip() {
        final ErrorPlaneDetector detector = new ErrorPlaneDetector(List.of(
                new OwningStub("alpha", Set.of("SHARED_CODE")),
                new OwningStub("beta", Set.of("SHARED_CODE"))));

        assertThat(detector.detect(facts(error(205, "bash", "SHARED_CODE", null)))).isEmpty();
    }

    /** A detector that claims nothing contributes nothing to the union. */
    @Test
    void aDetectorOwningNothingChangesNothing() {
        final ErrorPlaneDetector detector = new ErrorPlaneDetector(List.of(
                stampGuard, new OwningStub("silent", Set.of())));

        assertThat(detector.detect(facts(error(206, "bash", "INVALID_ARGS", null))))
                .extracting(Finding::code).containsExactly("INVALID_ARGS");
    }

    private record OwningStub(String id, Set<String> codes) implements Detector {

        @Override
        public Set<String> ownsToolCodes() {
            return codes;
        }

        @Override
        public List<Finding> detect(final StreamFacts facts) {
            return List.of();
        }
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
        return new ErrorEvent(seq, 0, 0, tool, code, path, T0 + seq);
    }

    @Test
    void theFindingCarriesTheEventTimeOfTheRefusal() {
        final List<Finding> findings = detector.detect(facts(
                new ErrorEvent(101, 0, 0, "edit", "FS_NOT_FOUND", "/home/dev/demo/missing.java",
                        T0 + 101),
                new ErrorEvent(102, 0, 0, "bash", "INVALID_ARGS", null, T0 + 102)));

        assertThat(findings)
                .extracting(Finding::seq, Finding::occurredAt)
                .containsExactly(tuple(101, T0 + 101), tuple(102, T0 + 102));
    }

    private SessionRecord session() {
        return new SessionRecord("s-demo", "session.jsonl.zstd", "demo-project", "V0",
                1L, null, null, null, null, null, 0, "/home/dev/demo");
    }
}
