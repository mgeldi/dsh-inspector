package inspector.ingest;

/**
 * How a mutating shell command writes, as far as its text says. The verb class answers "could
 * this have moved a file's stamp?"; this answers the narrower "did it rewrite a file's
 * content?", which is what the shell-edit detector counts. A copy, a move, a delete or a
 * permission change can invalidate a stamp too, but it is not the model editing a file behind
 * the file tools' back, and counting it as one would turn a precise rate into a vague one.
 */
public enum WriteKind {
    /** {@code > file}, {@code >> file}, heredocs included. The redirect target is known. */
    REDIRECT,
    /** {@code sed -i}, {@code perl -i}. */
    IN_PLACE,
    /** {@code tee file}. */
    TEE,
    /** A script-level write: {@code open(…, 'w')}, {@code writeFileSync}, {@code .write(…)}. */
    SCRIPT,
    /** {@code truncate}, {@code patch}, {@code dd}. */
    CONTENT_TOOL,
    /** {@code cp}, {@code mv}, {@code rm}, {@code touch}, {@code chmod} and the rest of the file operations. */
    FILE_OP;

    public boolean rewritesContent() {
        return this != FILE_OP;
    }
}
