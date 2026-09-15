package inspector.ingest;

/**
 * A file-tool operation on a path. op is READ or WRITE; DESIGN.md §5.3 step 1 needs the
 * operation, not just "was read", because on most real rejections the last touch was an edit
 * or a write and a read-only tracker finds nothing.
 */
public record FileTouch(int seq, String absolutePath, String op) {

    public static final String READ = "read";
    public static final String WRITE = "write";
}
