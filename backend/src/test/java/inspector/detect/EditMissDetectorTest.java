package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.ingest.ErrorEvent;
import inspector.ingest.SessionRecord;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A failed edit is explained by what the model last did to the same file in the same stream.
 * Every path here is invented.
 */
final class EditMissDetectorTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String FILE = "/home/dev/demo/App.java";
    private static final String MISS = "FS_EDIT_NOT_FOUND";

    private final EditMissDetector detector = new EditMissDetector();

    @Test
    void theDetectorOwnsTheCodeSoTheGenericRowIsNotAlsoEmitted() {
        assertThat(detector.ownsToolCodes()).containsExactly(MISS);
        final ErrorPlaneDetector generic = new ErrorPlaneDetector(List.of(detector));
        final Stream s = new Stream().call(1, "edit", FILE, MISS);

        assertThat(generic.detect(s.facts())).isEmpty();
        assertThat(detector.detect(s.facts())).hasSize(1);
    }

    @Test
    void eachMissIsCategorisedByThePreviousOperationOnThatPath() {
        final Stream s = new Stream()
                .call(1, "edit", FILE, MISS)                     // nothing before it
                .call(2, "read", FILE, null)
                .call(3, "edit", FILE, MISS)                     // after a read
                .call(4, "edit", FILE, MISS)                     // after the miss just before
                .call(5, "edit", FILE, null)
                .call(6, "edit", FILE, MISS);                    // after its own successful edit

        assertThat(detector.detect(s.facts()))
                .extracting(Finding::seq, Finding::category, Finding::causeSeq)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, Category.MISS_UNREAD, null),
                        org.assertj.core.groups.Tuple.tuple(3, Category.MISS_AFTER_READ, 2),
                        org.assertj.core.groups.Tuple.tuple(4, Category.REPEATED_MISS, 3),
                        org.assertj.core.groups.Tuple.tuple(6, Category.MISS_AFTER_EDIT, 5));
    }

    /**
     * A read of a range is not a read of the file: 43 of the 46 misses after a read on the measured
     * corpus followed one, the model quoting text it had not been shown.
     */
    @Test
    void aMissAfterARangedReadIsToldApartFromAMissAfterAFullRead() {
        final Stream s = new Stream()
                .read(1, FILE, true).call(2, "edit", FILE, MISS)
                .read(3, FILE, false).call(4, "edit", FILE, MISS);

        assertThat(detector.detect(s.facts())).extracting(Finding::category)
                .containsExactly(Category.MISS_AFTER_PARTIAL_READ, Category.MISS_AFTER_READ);
    }

    /**
     * An operation that failed for another reason neither changed the file nor showed the model
     * its content, so it is not what the miss followed: the read before it is.
     */
    @Test
    void anOperationThatFailedForAnotherReasonIsLookedPast() {
        final Stream s = new Stream()
                .call(1, "read", FILE, null)
                .call(2, "write", FILE, "FS_STALE_VERSION")
                .call(3, "edit", FILE, MISS);

        assertThat(detector.detect(s.facts())).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.MISS_AFTER_READ);
            assertThat(f.causeSeq()).isEqualTo(1);
        });
    }

    @Test
    void anotherFilesHistoryDoesNotExplainThisMiss() {
        final Stream s = new Stream()
                .call(1, "read", "/home/dev/demo/Other.java", null)
                .call(2, "edit", FILE, MISS);

        assertThat(detector.detect(s.facts())).singleElement()
                .satisfies(f -> assertThat(f.category()).isEqualTo(Category.MISS_UNREAD));
    }

    /**
     * A result whose call never appeared has no tool name. It used to reach a
     * {@code Set.of(...).contains(null)} and throw, and the first real corpus it met stopped the
     * index run there.
     */
    @Test
    void aResultWithNoCallAndNoToolNameIsSkippedRatherThanFatal() {
        final Stream s = new Stream().call(1, null, FILE, null).call(2, "edit", FILE, MISS);

        assertThat(detector.detect(s.facts())).singleElement()
                .satisfies(f -> assertThat(f.category()).isEqualTo(Category.MISS_UNREAD));
    }

    @Test
    void theFindingIsModelMisuseWithHighConfidenceAndItsEventTime() {
        final Stream s = new Stream().call(1, "read", FILE, null).call(2, "edit", FILE, MISS);

        assertThat(detector.detect(s.facts())).singleElement().satisfies(f -> {
            assertThat(f.detector()).isEqualTo(EditMissDetector.ID);
            assertThat(f.plane()).isEqualTo(Plane.MODEL_MISUSE);
            assertThat(f.code()).isEqualTo(MISS);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(f.occurredAt()).isEqualTo(T0 + 2_100);
            assertThat(f.summary()).isEqualTo("App.java: edit found no match although the file was read at seq 1");
        });
    }

    /** A stream built call by call: one tool call per seq, its error (if any) at the same seq. */
    private static final class Stream {
        private final List<ToolCallRecord> calls = new ArrayList<>();
        private final List<ErrorEvent> errors = new ArrayList<>();
        private final List<inspector.ingest.FileTouch> touches = new ArrayList<>();

        Stream read(final int seq, final String path, final boolean partial) {
            touches.add(new inspector.ingest.FileTouch(seq, path, inspector.ingest.FileTouch.READ, partial));
            return call(seq, "read", path, null);
        }

        Stream call(final int seq, final String tool, final String path, final String code) {
            calls.add(new ToolCallRecord(1, 0, seq, tool, T0 + seq * 1_000L, T0 + seq * 1_000L + 100,
                    100L, code, path, tool == null));
            if (code != null) {
                errors.add(new ErrorEvent(seq, 1, 0, tool, code, path, T0 + seq * 1_000L + 100));
            }
            return this;
        }

        StreamFacts facts() {
            return new StreamFacts(new SessionRecord("s-edit", "session.jsonl.zstd", "demo", "V0", T0,
                    null, null, 0, null, null, null, 0, "/home/dev/demo"),
                    List.of(), calls, touches, List.of(), errors, List.of(), List.of(), 0L);
        }
    }
}
