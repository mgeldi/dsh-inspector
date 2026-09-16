package inspector.store;

/**
 * The one function that decides what a path looks like once it is stored, and it is a privacy
 * boundary, not a formatting helper (DESIGN.md §4.1): a value that reaches a {@code path_hint}
 * column has already been through here.
 *
 * <p>Named for what it produces. It used to be {@code Paths}, which shadowed
 * {@code java.nio.file.Paths} inside {@link IndexWriter} — the file that also imports
 * {@code java.nio.file.Path}, so every unqualified {@code Paths.x(...)} in it resolved to this
 * class by luck of the import list rather than by intent.
 */
public final class PathHints {

    private PathHints() {
    }

    /** Absolute under the project root becomes project-relative; anything else keeps its last
     *  segment only, so a home directory or another user's path can never reach the database. */
    public static String hint(final String absolute, final String projectRoot) {
        if (absolute == null || absolute.isBlank()) {
            return null;
        }
        if (projectRoot != null && !projectRoot.isBlank()) {
            final String root = projectRoot.endsWith("/") ? projectRoot : projectRoot + "/";
            if (absolute.startsWith(root)) {
                return absolute.substring(root.length());
            }
        }
        final int slash = absolute.lastIndexOf('/');
        return slash < 0 ? absolute : absolute.substring(slash + 1);
    }
}
