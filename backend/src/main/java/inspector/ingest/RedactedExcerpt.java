package inspector.ingest;

/**
 * A short, credential-masked view of a command. The only content-like value that may leave
 * ingest, and it stays inside ShellEvidence. DESIGN.md §4.1.
 *
 * <p>A record, because it is a component of the {@link ShellEvidence} record: as a plain class
 * with no {@code equals}, two excerpts of the same command were unequal, so {@code
 * ShellEvidence.equals} — which is how the deduplication tests compare findings — was
 * identity-based on this one field while being value-based on every other.
 */
public record RedactedExcerpt(String value) {

    public RedactedExcerpt {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("excerpt must be non-blank");
        }
    }

    /**
     * The command text and nothing else. The generated form would wrap it in
     * {@code RedactedExcerpt[value=...]}, and this value does appear in test output and log
     * lines, where the wrapper is noise around the thing being looked at.
     */
    @Override
    public String toString() {
        return value;
    }
}
