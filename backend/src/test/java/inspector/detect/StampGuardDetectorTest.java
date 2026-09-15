package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

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

final class StampGuardDetectorTest {

    private static final int TOUCH_SEQ = 145;
    private static final int CAUSE_SEQ = 150;
    private static final int FAIL_SEQ = 300;

    private final StampGuardDetector detector = new StampGuardDetector();

    @Test
    void directMutationIsHighConfidenceWhenThePathIsAbsolute() {
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.DIRECT_MUTATION);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(f.staleSeq()).isEqualTo(TOUCH_SEQ);
            assertThat(f.causeSeq()).isEqualTo(CAUSE_SEQ);
            assertThat(f.evidence()).singleElement()
                    .satisfies(e -> assertThat(e.seq()).isEqualTo(CAUSE_SEQ));
        });
    }

    @Test
    void aCauseBeforeTheStaleTouchMustNotBeAttributed() {
        // window is the open interval (staleSeq, failedSeq): earlier mutations moved a different stamp
        final List<Finding> findings = detect(
                shell(CAUSE_SEQ - 10, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.EXTERNAL);
            assertThat(f.confidence()).isNull();
            assertThat(f.evidence()).isEmpty();
        });
    }

    @Test
    void onlyTheFirstMutationAfterTheStaleTouchIsTheCause() {
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ + 50, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.DIRECT_MUTATION);
            assertThat(f.causeSeq()).isEqualTo(CAUSE_SEQ);
        });
    }

    @Test
    void basenameOnlyMatchDropsConfidenceToMedium() {
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/src/App.java"),
                shell(CAUSE_SEQ, VerbClass.MUTATING, "src/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/src/App.java"));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    @Test
    void gitRestoreIsAttributedButExplicitlyNotAViolation() {
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/README.md"),
                shell(CAUSE_SEQ, VerbClass.VCS_RESTORE, "README.md"),
                stale(FAIL_SEQ, "/home/dev/demo/README.md"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.VCS_RESTORE);
            assertThat(f.summary()).contains("legitimate");
        });
    }

    @Test
    void mentionWithoutMutationIsExternalAndStoresNoEvidence() {
        // the measured false-positive class: executing a script does not touch its mtime
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/scripts/audit-report.mjs"),
                shell(CAUSE_SEQ, VerbClass.OTHER, "scripts/audit-report.mjs"),
                stale(FAIL_SEQ, "/home/dev/demo/scripts/audit-report.mjs"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.EXTERNAL);
            assertThat(f.confidence()).isNull();
            assertThat(f.evidence()).isEmpty();       // §5.3: the mention is not stored
        });
    }

    @Test
    void withNoPriorFileToolTouchThereIsNoWindowAndNoGuess() {
        final List<Finding> findings = detect(stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.EXTERNAL);
            assertThat(f.confidence()).isNull();
            assertThat(f.staleSeq()).isNull();
            assertThat(f.evidence()).isEmpty();
        });
    }

    @Test
    void readOnlyWindowMentionsAreNeverCauses() {
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.READ_ONLY, "App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.category()).isEqualTo(Category.EXTERNAL));
    }

    @Test
    void aShellAtExactlyTheStaleTouchIsNotTheCause() {
        // the stale touch itself is not evidence of a mutation: the window is open on the left
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(TOUCH_SEQ, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.EXTERNAL);
            assertThat(f.confidence()).isNull();
            assertThat(f.staleSeq()).isEqualTo(TOUCH_SEQ);
            assertThat(f.evidence()).isEmpty();
        });
    }

    @Test
    void aShellAtExactlyTheFailingWriteIsNotItsOwnCause() {
        // the failing write is not its own cause: the window is open on the right
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                edit(FAIL_SEQ, "/home/dev/demo/App.java"),
                shell(FAIL_SEQ, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.EXTERNAL);
            assertThat(f.confidence()).isNull();
            assertThat(f.staleSeq()).isEqualTo(TOUCH_SEQ);
            assertThat(f.evidence()).isEmpty();
        });
    }

    @Test
    void aReadOnlyMentionBeforeTheRealMutationDoesNotHideIt() {
        // §5.3 step 3: the cause is the first *mutating* command; an earlier read-only mention
        // is not a cause and must not hide the mutation that actually moved the stamp.
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(TOUCH_SEQ + 3, VerbClass.READ_ONLY, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.DIRECT_MUTATION);
            assertThat(f.causeSeq()).isEqualTo(CAUSE_SEQ);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void anEarlierRestoreWinsOverALaterMutationBecauseBothMoveTheStamp() {
        // A `git checkout` rewrites the file just as `sed -i` does, so the earlier of the two is
        // what broke the stamp. Reporting the mutation would be wrong twice: the attribution is
        // late, and the seq shown on screen would disagree with the story the summary tells.
        // Chronology decides between causal verbs; the verb class only decides the category.
        final List<Finding> findings = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ + 20, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.VCS_RESTORE, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.VCS_RESTORE);
            assertThat(f.causeSeq()).isEqualTo(CAUSE_SEQ);
            assertThat(f.summary()).contains("legitimate");
        });
    }

    @Test
    void evidenceOrderInTheStreamDoesNotChangeTheVerdict() {
        // The detector sorts by seq before scanning. Handing the same events over in the other
        // order must not produce a different attribution, or the window scan secretly depends
        // on input order — which is exactly how the two-pass version got this wrong.
        final List<Finding> forwards = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.VCS_RESTORE, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ + 20, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));
        final List<Finding> backwards = detect(
                edit(TOUCH_SEQ, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ + 20, VerbClass.MUTATING, "/home/dev/demo/App.java"),
                shell(CAUSE_SEQ, VerbClass.VCS_RESTORE, "/home/dev/demo/App.java"),
                stale(FAIL_SEQ, "/home/dev/demo/App.java"));

        assertThat(backwards).usingRecursiveComparison().isEqualTo(forwards);
    }

    private List<Finding> detect(final Object... parts) {
        final List<FileTouch> touches = new ArrayList<>();
        final List<ShellEvidence> shell = new ArrayList<>();
        final List<ErrorEvent> errors = new ArrayList<>();
        for (final Object part : parts) {
            if (part instanceof FileTouch t) { touches.add(t); }
            else if (part instanceof ShellEvidence s) { shell.add(s); }
            else if (part instanceof ErrorEvent e) { errors.add(e); }
        }
        return new StampGuardDetector().detect(new StreamFacts(session(), List.of(), List.of(),
                List.copyOf(touches), List.copyOf(shell), List.copyOf(errors),
                List.of(), List.of(), 0L));
    }

    private FileTouch edit(final int seq, final String path) {
        return new FileTouch(seq, path, FileTouch.WRITE);
    }

    private ShellEvidence shell(final int seq, final VerbClass verb, final String... paths) {
        return new ShellEvidence(seq, Set.of(paths), verb, new RedactedExcerpt("test command"));
    }

    private ErrorEvent stale(final int seq, final String path) {
        return new ErrorEvent(seq, 0, 0, "edit", "FS_STALE_VERSION", path);
    }

    private SessionRecord session() {
        return new SessionRecord("s-demo", "session.jsonl.zstd", "demo-project", "V0",
                1L, null, null, null, null, null, 0, "/home/dev/demo");
    }
}
