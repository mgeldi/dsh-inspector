package inspector.ingest;

import java.util.Set;

/**
 * An observation, not a conclusion: referencedPaths is plural and unmatched, because the path
 * that failed is not known until a rejection arrives later in the stream. DESIGN.md §4.1.
 */
public record ShellEvidence(int seq, Set<String> referencedPaths, VerbClass verbClass,
                            RedactedExcerpt excerpt) {
}
