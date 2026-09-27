package inspector.detect;

import inspector.ingest.FileTouch;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import inspector.ingest.VerbClass;
import inspector.ingest.WriteKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A shell command that rewrote the content of a file the file tools were already tracking —
 * the event that makes the stamp stale, counted whether or not a refusal ever follows it.
 *
 * <p>Stamp-guard sees this only when the model later comes back to the same file through a file
 * tool and is refused, which makes it a lagging and partial signal. The harness's own
 * instruction file asks the model to keep file changes in the file tools; this is the rate that
 * says whether it does, and so the one to watch when that instruction changes.
 *
 * <p>Only content-rewriting forms count ({@link inspector.ingest.WriteKind#rewritesContent()}):
 * a redirect, {@code sed -i}, {@code tee}, a script-level write, {@code patch}. A copy, move,
 * delete or permission change is left out on purpose — it is not the model editing a file
 * behind the tools' back, and folding it in would make the rate vague. When the command names
 * its target (a redirect does), only the target is matched; otherwise every path it mentions is.
 *
 * <p>One finding per shell command. {@code HIGH} when the command names what it writes (a
 * redirect target, a script write call's literal path) and that name is the tracked path as the
 * file tools wrote it, or a multi-segment suffix of it ({@code src/App.java}, not
 * {@code ./App.java}); {@code MEDIUM} for a basename match, or for a match among the paths a
 * script merely mentions — the same
 * evidence rule as stamp-guard (§5.3). The detail is the write form, so the rate can be split
 * by how the model wrote.
 */
@Component
public final class ShellEditDetector implements Detector {

    public static final String ID = "shell-edit";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        // A refused or failed file-tool call made no stamp: tracking starts at an operation that
        // went through. (A refused edit used to count, and a finding then claimed the tools "had
        // tracked" a file they had just refused to touch.)
        final Set<Integer> failed = new java.util.HashSet<>();
        facts.toolCalls().stream().filter(c -> c.errorCode() != null).forEach(c -> failed.add(c.seq()));
        final List<FileTouch> touches = facts.touches().stream()
                .filter(t -> !failed.contains(t.seq()))
                .sorted(Comparator.comparingInt(FileTouch::seq))
                .toList();
        if (touches.isEmpty()) {
            return List.of();
        }
        final Map<Integer, Long> callTime = new HashMap<>();
        for (final ToolCallRecord call : facts.toolCalls()) {
            if (call.startedAt() != null) {
                callTime.putIfAbsent(call.seq(), call.startedAt());
            }
        }

        final List<Finding> findings = new ArrayList<>();
        for (final ShellEvidence shell : facts.shell()) {
            if (shell.verbClass() != VerbClass.MUTATING || shell.writeKind() == null
                    || !shell.writeKind().rewritesContent()) {
                continue;
            }
            final Set<String> candidates = shell.writeTargets().isEmpty()
                    ? shell.referencedPaths() : shell.writeTargets();
            final Match match = firstTracked(touches, shell.seq(), candidates);
            if (match == null) {
                continue;
            }
            // HIGH needs both halves: the command's own text names what it writes, and that name is
            // the tracked file. A match found only among the paths a script mentions — its write
            // went through a variable — is a mention, and a mention is never better than MEDIUM.
            // Every form contributes its own operands to the targets (ShellAnalyzer.writeTargets), so
            // "named" is simply "the text says what it writes". It used to count every path a sed,
            // tee or patch command mentioned as named, and `node x.js | tee log.txt` became a HIGH
            // edit of x.js.
            final boolean named = !shell.writeTargets().isEmpty();
            // the form that wrote this file, when the text says; a command can redirect its log
            // and have its script write the tracked file
            final WriteKind kind = named ? shell.kindOf(match.candidate) : shell.writeKind();
            findings.add(new Finding(ID, Plane.MODEL_MISUSE, Category.DIRECT_MUTATION, null,
                    kind.name(), match.exact && named ? Confidence.HIGH : Confidence.MEDIUM,
                    match.touch.absolutePath(), shell.seq(), match.touch.seq(), null,
                    callTime.getOrDefault(shell.seq(), facts.session().startedAt()),
                    ("%s rewritten from the shell (%s) at seq %d; the file tools had tracked it since "
                            + "seq %d (%s path match)").formatted(base(match.touch.absolutePath()),
                            kind.name().toLowerCase(java.util.Locale.ROOT), shell.seq(),
                            match.touch.seq(), match.exact ? "full" : "basename"),
                    List.of(shell)));
        }
        return findings;
    }

    private record Match(FileTouch touch, boolean exact, String candidate) {
    }

    /**
     * The tracked file a candidate names, preferring a full or suffix match over a basename one.
     * The touch returned is the last file-tool operation on that path before the command — the
     * stamp the command invalidated.
     */
    private static Match firstTracked(final List<FileTouch> touches, final int seq,
                                      final Set<String> candidates) {
        Match basenameOnly = null;
        for (final String candidate : candidates.stream().sorted().toList()) {
            FileTouch exact = null;
            FileTouch byBase = null;
            for (final FileTouch touch : touches) {
                if (touch.seq() >= seq) {
                    break;
                }
                final String relative = stripDot(candidate);
                if (touch.absolutePath().equals(candidate)
                        || (relative.contains("/") && touch.absolutePath().endsWith("/" + relative))) {
                    exact = touch;
                } else if (!candidate.startsWith("/") && base(touch.absolutePath()).equals(base(candidate))) {
                    // an absolute path that is not the tracked one names another file of the same
                    // name; only a relative or bare name can be the tracked file seen from elsewhere
                    byBase = touch;
                }
            }
            if (exact != null) {
                return new Match(exact, true, candidate);
            }
            if (byBase != null && basenameOnly == null) {
                basenameOnly = new Match(byBase, false, candidate);
            }
        }
        return basenameOnly;
    }

    private static String stripDot(final String path) {
        return path.startsWith("./") ? path.substring(2) : path;
    }

    private static String base(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
