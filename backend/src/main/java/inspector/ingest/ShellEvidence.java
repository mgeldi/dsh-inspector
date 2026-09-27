package inspector.ingest;

import java.util.Map;
import java.util.Set;

/**
 * An observation, not a conclusion: referencedPaths is plural and unmatched, because the path
 * that failed is not known until a rejection arrives later in the stream. DESIGN.md §4.1.
 *
 * <p>{@code writeKind} is set for a {@link VerbClass#MUTATING} command and null otherwise: the
 * first content form the command shows. {@code targetKinds} maps each path the command's text
 * names as written — a redirect's target, an in-place or {@code tee} operand, the literal path a
 * script's write call names — to the form that names it, and is empty when the text does not say
 * (a write through a variable). One command can write two files two ways, and a finding is about
 * one of them.
 */
public record ShellEvidence(int seq, Set<String> referencedPaths, VerbClass verbClass,
                            RedactedExcerpt excerpt, WriteKind writeKind, Map<String, WriteKind> targetKinds) {

    public ShellEvidence {
        targetKinds = targetKinds == null ? Map.of() : Map.copyOf(targetKinds);
    }

    /** Targets all written the command's one way. */
    public ShellEvidence(final int seq, final Set<String> referencedPaths, final VerbClass verbClass,
                         final RedactedExcerpt excerpt, final WriteKind writeKind, final Set<String> writeTargets) {
        this(seq, referencedPaths, verbClass, excerpt, writeKind, byKind(writeTargets, writeKind));
    }

    /** An observation with no write classification. */
    public ShellEvidence(final int seq, final Set<String> referencedPaths, final VerbClass verbClass,
                         final RedactedExcerpt excerpt) {
        this(seq, referencedPaths, verbClass, excerpt, null, Map.of());
    }

    /** The paths the command's text names as written. */
    public Set<String> writeTargets() {
        return targetKinds.keySet();
    }

    /** How the command wrote {@code target}: the form that named it, else the command's own kind. */
    public WriteKind kindOf(final String target) {
        return targetKinds.getOrDefault(target, writeKind);
    }

    private static Map<String, WriteKind> byKind(final Set<String> targets, final WriteKind kind) {
        if (targets == null || targets.isEmpty() || kind == null) {
            return Map.of();
        }
        final Map<String, WriteKind> out = new java.util.HashMap<>();
        targets.forEach(t -> out.put(t, kind));
        return out;
    }
}
