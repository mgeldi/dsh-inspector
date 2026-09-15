package inspector.detect;

import inspector.ingest.ErrorEvent;
import inspector.ingest.FileTouch;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.VerbClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * DESIGN.md §5.3. The log gives the failed write and its path; the stale touch and the cause
 * are derived, and the finding says which is which. Attribution is abductive: the summary says
 * "consistent with", because the log never records what modified the file.
 */
@Component
public final class StampGuardDetector implements Detector {

    public static final String ID = "stamp-guard";
    private static final String CODE = "FS_STALE_VERSION";

    /**
     * The verb classes that can have moved an mtime. A version-control restore rewrites the file
     * just as a direct mutation does, so both are causal and chronology between them decides.
     * READ_ONLY and OTHER never are: a mention is not a write (§5.3).
     */
    private static final Set<VerbClass> CAUSAL_VERBS = Set.of(VerbClass.MUTATING, VerbClass.VCS_RESTORE);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> ownsToolCodes() {
        return Set.of(CODE);
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        final Map<String, List<FileTouch>> touchesByPath = new HashMap<>();
        for (final FileTouch touch : facts.touches()) {
            touchesByPath.computeIfAbsent(touch.absolutePath(), k -> new ArrayList<>()).add(touch);
        }
        final List<ShellEvidence> shell = facts.shell().stream()
                .sorted(Comparator.comparingInt(ShellEvidence::seq))
                .toList();

        final List<Finding> findings = new ArrayList<>();
        for (final ErrorEvent error : facts.errors()) {
            if (!CODE.equals(error.code())) {
                continue;
            }
            findings.add(attribute(error, touchesByPath, shell));
        }
        return findings;
    }

    private Finding attribute(final ErrorEvent error,
                              final Map<String, List<FileTouch>> touchesByPath,
                              final List<ShellEvidence> shell) {
        final FileTouch stale = lastTouchBefore(touchesByPath.get(error.absolutePath()), error.seq());
        if (error.absolutePath() == null || stale == null) {
            // No derived stale touch means no bounded window, and an unbounded window is a guess.
            return external(error, null, "no prior file-tool operation on this path");
        }
        // Chronology decides, not verb class. A direct mutation and a version-control restore
        // both write the file, so the cause is whichever came first after the stale touch — the
        // one that actually moved the stamp. Scanning for MUTATING before VCS_RESTORE, as an
        // earlier version did, lets a later `sed -i` claim a stamp that an earlier `git checkout`
        // had already broken, and the seqs on screen would then disagree with the summary.
        final Match cause = firstInWindow(shell, stale, error, CAUSAL_VERBS);
        if (cause != null) {
            return cause.evidence().verbClass() == VerbClass.VCS_RESTORE
                    ? vcsRestore(error, stale, cause)
                    : directMutation(error, stale, cause);
        }
        final Match mention = firstInWindow(shell, stale, error, null);
        // READ_ONLY and OTHER are never a cause: a mention is not a write (§5.3). The finding
        // stays visible, but the mention is discarded, not stored as evidence.
        return external(error, stale.seq(), mention == null
                ? "no shell command in the window referenced this path"
                : "in-window mention at seq " + mention.evidence().seq() + " is not a mutation");
    }

    private Finding directMutation(final ErrorEvent error, final FileTouch stale, final Match cause) {
        return new Finding(ID, Plane.GUARD, Category.DIRECT_MUTATION, error.code(),
                cause.absolute() ? Confidence.HIGH : Confidence.MEDIUM, error.absolutePath(),
                error.seq(), stale.seq(), cause.evidence().seq(), error.occurredAt(),
                ("%s refused: stamp stale since seq %d (%s); consistent with a mutating command "
                        + "at seq %d (%s path match)").formatted(
                                base(error.absolutePath()), stale.seq(), stale.op(),
                                cause.evidence().seq(),
                                cause.absolute() ? "absolute" : "basename"),
                List.of(cause.evidence()));
    }

    private Finding vcsRestore(final ErrorEvent error, final FileTouch stale, final Match cause) {
        return new Finding(ID, Plane.GUARD, Category.VCS_RESTORE, error.code(),
                Confidence.HIGH, error.absolutePath(), error.seq(), stale.seq(),
                cause.evidence().seq(), error.occurredAt(),
                ("%s refused: stamp stale since seq %d; a version-control restore at seq %d is "
                        + "legitimate work the stamp cannot know about").formatted(
                                base(error.absolutePath()), stale.seq(), cause.evidence().seq()),
                List.of(cause.evidence()));
    }

    /**
     * The open interval (stale.seq, error.seq), scanned in seq order. The first evidence whose
     * verb is in {@code causal} and which references the path (absolutely or by basename) wins.
     * A null set means any verb, used only to name a mention in the external summary.
     *
     * <p>One chronological pass, never one pass per verb: two passes would report the first
     * mutation and the first restore independently and let whichever verb class is checked first
     * win a race it lost on the clock.
     */
    private Match firstInWindow(final List<ShellEvidence> shell, final FileTouch stale,
                                final ErrorEvent error, final Set<VerbClass> causal) {
        for (final ShellEvidence evidence : shell) {
            if (evidence.seq() <= stale.seq() || evidence.seq() >= error.seq()) {
                continue;
            }
            if (causal != null && !causal.contains(evidence.verbClass())) {
                continue;
            }
            for (final String referenced : evidence.referencedPaths()) {
                if (referenced.equals(error.absolutePath())) {
                    return new Match(evidence, true);
                }
                if (base(referenced).equals(base(error.absolutePath()))) {
                    return new Match(evidence, false);
                }
            }
        }
        return null;
    }

    private FileTouch lastTouchBefore(final List<FileTouch> touches, final int seq) {
        if (touches == null) {
            return null;
        }
        return touches.stream().filter(t -> t.seq() < seq)
                .max(Comparator.comparingInt(FileTouch::seq)).orElse(null);
    }

    private Finding external(final ErrorEvent error, final Integer staleSeq, final String reason) {
        return new Finding(ID, Plane.GUARD, Category.EXTERNAL, error.code(), null,
                error.absolutePath(), error.seq(), staleSeq, null, error.occurredAt(),
                "%s refused: stamp stale%s; %s".formatted(
                        base(error.absolutePath()),
                        staleSeq == null ? " (stale touch not derivable)" : " since seq " + staleSeq,
                        reason),
                List.of());
    }

    private static String base(final String path) {
        if (path == null) {
            return "";
        }
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private record Match(ShellEvidence evidence, boolean absolute) {
    }
}
