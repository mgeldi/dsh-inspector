package inspector.ingest;

/**
 * A file-tool operation on a path. op is READ or WRITE; DESIGN.md §5.3 step 1 needs the
 * operation, not just "was read", because on most real rejections the last touch was an edit
 * or a write and a read-only tracker finds nothing. {@code partial} marks a read of a range
 * (offset/limit): on the measured corpus 43 of the 44 edits that missed right after a read
 * followed a partial one — the model quoting text it had not been shown.
 */
public record FileTouch(int seq, String absolutePath, String op, boolean partial) {

    public static final String READ = "read";
    public static final String WRITE = "write";

    /** A touch of the whole file — every write, and a read with no range. */
    public FileTouch(final int seq, final String absolutePath, final String op) {
        this(seq, absolutePath, op, false);
    }
}
