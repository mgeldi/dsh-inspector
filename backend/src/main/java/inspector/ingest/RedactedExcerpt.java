package inspector.ingest;

/**
 * A short, credential-masked view of a command. The only content-like value that may leave
 * ingest, and it stays inside ShellEvidence. DESIGN.md §4.1.
 */
public final class RedactedExcerpt {

    private final String value;

    public RedactedExcerpt(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("excerpt must be non-blank");
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
