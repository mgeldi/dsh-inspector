package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.ingest.FileTouch;
import inspector.ingest.RedactedExcerpt;
import inspector.ingest.SessionRecord;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import inspector.ingest.VerbClass;
import inspector.ingest.WriteKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A shell command that rewrote a file the file tools were tracking. Every path is invented.
 */
final class ShellEditDetectorTest {

    private static final long T0 = 1_760_000_000_000L;
    private static final String FILE = "/home/dev/demo/src/App.java";

    private final ShellEditDetector detector = new ShellEditDetector();

    @Test
    void aRewriteOfATrackedFileIsOneFindingWithTheStampItInvalidated() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.IN_PLACE, Set.of(FILE), Set.of(FILE));

        assertThat(detector.detect(s.facts())).singleElement().satisfies(f -> {
            assertThat(f.detector()).isEqualTo(ShellEditDetector.ID);
            assertThat(f.plane()).isEqualTo(Plane.MODEL_MISUSE);
            assertThat(f.category()).isEqualTo(Category.DIRECT_MUTATION);
            assertThat(f.code()).as("not an error: nothing refused anything").isNull();
            assertThat(f.detail()).isEqualTo("IN_PLACE");
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(f.seq()).isEqualTo(5);
            assertThat(f.staleSeq()).isEqualTo(3);
            assertThat(f.occurredAt()).as("the shell call's own time").isEqualTo(T0 + 5_000);
            assertThat(f.evidence()).hasSize(1);
        });
    }

    @Test
    void aFileOperationIsNotAnEditEvenOnATrackedFile() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.FILE_OP, Set.of(FILE, FILE + ".bak"), Set.of());

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    @Test
    void aFileTheToolsOnlyTouchAfterTheCommandWasNotTrackedWhenItRan() {
        final Stream s = new Stream()
                .shell(5, WriteKind.REDIRECT, Set.of(FILE), Set.of(FILE))
                .touch(7, FILE);

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    /**
     * {@code cat App.java > copy.txt} names the tracked file, and writes a different one. When the
     * command says what it writes, only that is matched.
     */
    @Test
    void aRedirectIsMatchedOnItsTargetNotOnEveryPathItMentions() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.REDIRECT, Set.of(FILE, "/home/dev/demo/copy.txt"),
                        Set.of("/home/dev/demo/copy.txt"));

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    @Test
    void aMultiSegmentRelativePathIsAFullMatchAndABareBasenameIsNot() {
        final Stream suffix = new Stream().touch(3, FILE)
                .shell(5, WriteKind.SCRIPT, Set.of("src/App.java"), Set.of("src/App.java"));
        final Stream basename = new Stream().touch(3, FILE)
                .shell(5, WriteKind.SCRIPT, Set.of("App.java"), Set.of("App.java"));

        assertThat(detector.detect(suffix.facts())).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(Confidence.HIGH));
        assertThat(detector.detect(basename.facts())).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    /**
     * A script that writes through a variable names no file; the tracked path is only among the
     * paths it mentions — perhaps the one it read. That is never better than MEDIUM.
     */
    @Test
    void aScriptThatNamesNoWriteTargetIsOnlyEverAMediumMatch() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.SCRIPT, Set.of(FILE), Set.of());

        assertThat(detector.detect(s.facts())).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    /**
     * {@code ./App.java} is a basename with a prefix, not a path with a directory in it: it names
     * whatever App.java the shell was standing next to, which is a basename-strength match.
     */
    @Test
    void aDotSlashNameIsABasenameMatchNotAFullOne() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.SCRIPT, Set.of("./App.java"), Set.of("./App.java"));

        assertThat(detector.detect(s.facts())).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    /** {@code node x.js | tee log.txt} writes log.txt; the tracked x.js was only executed. */
    @Test
    void aTrackedFileTheCommandOnlyRunsIsNotEdited() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.TEE, Set.of(FILE, "/home/dev/demo/build.log"), Set.of("/home/dev/demo/build.log"));

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    /** An absolute path that is not the tracked one names another file, however alike their names. */
    @Test
    void aForeignAbsoluteTargetOfTheSameNameIsAnotherFile() {
        final Stream s = new Stream().touch(3, FILE)
                .shell(5, WriteKind.REDIRECT, Set.of("/tmp/elsewhere/App.java"), Set.of("/tmp/elsewhere/App.java"));

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    /** A file-tool call that was refused made no stamp, so it did not start tracking anything. */
    @Test
    void aRefusedFileToolCallDoesNotMakeTheFileTracked() {
        final Stream s = new Stream();
        s.touches.add(new FileTouch(3, FILE, FileTouch.WRITE));
        s.calls.add(new ToolCallRecord(1, 0, 3, "edit", T0 + 3_000, null, null, "FS_NOT_OBSERVED", FILE, false));
        s.shell(5, WriteKind.IN_PLACE, Set.of(FILE), Set.of());

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    /** The detail is how this file was written, not the command's first write form. */
    @Test
    void theDetailNamesTheFormThatWroteTheTrackedFile() {
        final Stream s = new Stream().touch(3, FILE);
        s.shell.add(new ShellEvidence(5, Set.of(FILE, "/tmp/run.log"), VerbClass.MUTATING,
                new RedactedExcerpt("an invented command"), WriteKind.REDIRECT,
                java.util.Map.of("/tmp/run.log", WriteKind.REDIRECT, FILE, WriteKind.SCRIPT)));
        s.call(5);

        assertThat(detector.detect(s.facts())).singleElement().satisfies(f -> {
            assertThat(f.detail()).isEqualTo("SCRIPT");
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(f.summary()).contains("(script)");
        });
    }

    @Test
    void aReadOnlyCommandIsNeverAnEdit() {
        final Stream s = new Stream().touch(3, FILE);
        s.shell.add(new ShellEvidence(5, Set.of(FILE), VerbClass.READ_ONLY, new RedactedExcerpt("cat App.java")));
        s.call(5);

        assertThat(detector.detect(s.facts())).isEmpty();
    }

    private static final class Stream {
        private final List<FileTouch> touches = new ArrayList<>();
        private final List<ShellEvidence> shell = new ArrayList<>();
        private final List<ToolCallRecord> calls = new ArrayList<>();

        Stream touch(final int seq, final String path) {
            touches.add(new FileTouch(seq, path, FileTouch.READ));
            return call(seq);
        }

        Stream shell(final int seq, final WriteKind kind, final Set<String> referenced, final Set<String> targets) {
            shell.add(new ShellEvidence(seq, referenced, VerbClass.MUTATING,
                    new RedactedExcerpt("an invented command"), kind, targets));
            return call(seq);
        }

        Stream call(final int seq) {
            calls.add(new ToolCallRecord(1, 0, seq, "tool", T0 + seq * 1_000L, null, null, null, null, false));
            return this;
        }

        StreamFacts facts() {
            return new StreamFacts(new SessionRecord("s-shell", "session.jsonl.zstd", "demo", "V0", T0,
                    null, null, 0, null, null, null, 0, "/home/dev/demo"),
                    List.of(), calls, touches, shell, List.of(), List.of(), List.of(), 0L);
        }
    }
}
